# Phase 2 — Instrument the app, propagate through RabbitMQ

Phase 1 proved the pipe. This phase puts something real into it. The backend
(`tempo/`, `loki/`, `prometheus/`, `grafana/`) and the Collector config are
byte-identical to phase 1; what is new is a two-service Spring Boot app, three
single-node infrastructure containers for it to talk to, and one connector in
the Collector.

That the Collector needed no change is the phase's quietest lesson. The app
pushes OTLP; the Collector receives OTLP. It cannot tell which language or SDK
produced it, and it does not need to.

Infrastructure here is deliberately trivial — one Mongo, one RabbitMQ, one
MinIO, none of them observed. Clustering them and scraping them is phase 3.

Read [`docs/phase-2-instrument-the-app.html`](../docs/phase-2-instrument-the-app.html) first.

```
POST /orders ──▶ order-api ──publish──▶ RabbitMQ ──consume──▶ order-worker ──▶ MongoDB
                                                                          └──▶ MinIO
                                                                          └──▶ MongoDB
```

## Run

```sh
docker compose up -d          # builds ../app on first run, ~2 min
open http://localhost:3000
```

`loadgen` starts once `order-api` reports healthy: one order per second, of
which ~10% are rejected at the API with a 400 and ~10% fail in the worker after
the receipt is written.

## Verify

Health, and the app actually serving:

```sh
curl -s localhost:13133
curl -s -XPOST localhost:3001/orders -H 'content-type: application/json' \
  -d '{"sku":"SKU-1","qty":2}'
```

```
{"status":"Server available","upSince":"2026-09-04T08:03:42Z","uptime":"2m11s"}
{"id":"6d5d74b1-377d-492e-9bc0-d3d9f1874ede"}
```

**One trace, two services, across the broker.** Find a trace that reached the
final Mongo update and print its waterfall:

```sh
TID=$(curl -sG localhost:3200/api/search \
       --data-urlencode 'q={name="update shop.orders"}' --data-urlencode 'limit=1' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["traces"][0]["traceID"])')
curl -s "localhost:3200/api/v2/traces/$TID"
```

```
trace_id = f20d250c01fadb299afd5787948ca671
   +0.0ms  order-api     SERVER    POST /orders             4.56ms   tomcat-10.0
   +2.2ms  order-api     PRODUCER  orders publish           0.39ms   rabbitmq-2.7
   +4.2ms  order-worker  CONSUMER  orders process           0.09ms   rabbitmq-2.7
   +4.5ms  order-worker  CONSUMER  orders.created process  10.45ms   spring-rabbit-1.0
   +6.3ms  order-worker  CLIENT    insert shop.orders       0.66ms   mongo-4.0
   +8.7ms  order-worker  CLIENT    PUT                      4.36ms   okhttp-3.0
  +14.0ms  order-worker  CLIENT    update shop.orders       0.45ms   mongo-4.0
```

Seven spans, two services, one `trace_id`, and the join happens over an AMQP
message header. Nothing in `ApiController.java` or `OrderListener.java` mentions
a trace, a span, a context or a propagator.

The last column is the instrumentation scope — which of the agent's modules
emitted the span. It is worth reading, because two of the rows are surprising.

**Two CONSUMER spans for one message.** `rabbitmq-2.7` instruments the raw AMQP
client; `spring-rabbit-1.0` instruments the `@RabbitListener` on top of it. Both
fire. They are not duplicates in the useless sense — they measure different
things, and only one of them is load-bearing:

| | `orders process` | `orders.created process` |
|---|---|---|
| module | `rabbitmq-2.7` | `spring-rabbit-1.0` |
| duration | 0.09 ms — the delivery | 10.45 ms — the listener method |
| children | none | the Mongo and MinIO spans |
| carries `traceparent` | **yes** | no |

Setting `OTEL_INSTRUMENTATION_RABBITMQ_ENABLED=false` to remove the shallow span
was tried; it breaks the trace completely. The PRODUCER span disappears and the
worker's spans become separate roots, because header injection lives in the
low-level module, not the Spring one. **The instrumentation that wraps your code
is not necessarily the instrumentation that propagates context.** Leave both on.

**`PUT`, not `S3.PutObject`.** The receipt write goes through the MinIO Java
client, which is not the AWS SDK, so the agent has no S3-aware instrumentation
to apply. It falls through to OkHttp underneath and you get a transport span:

```
url.full                  http://minio:9000/receipts/7a924970-....json
http.request.method       PUT
http.response.status_code 200
server.address            minio
```

Correct, and thinner than it looks. There is no `aws.s3.bucket`, no
`rpc.method`, no operation name — the bucket and object key survive only inside
`url.full`, where no backend can aggregate on them. Swap in
`software.amazon.awssdk:s3` and the same call produces `S3.PutObject` with
proper S3 attributes. Span quality is a property of the library you chose, not
of OpenTelemetry.

**Failed orders are findable as traces.** ~10% of orders exceed the per-order
limit and fail in the worker after the receipt is written:

```sh
curl -sG localhost:3200/api/search --data-urlencode 'q={status=error}' --data-urlencode 'limit=5'
```

```
orders.created process   status=STATUS_CODE_ERROR   events=['exception']
  exception.type     java.lang.IllegalStateException
  exception.message  qty 95 exceeds per-order limit
  exception.stacktrace  java.lang.IllegalStateException: qty 95 ...
```

This does not happen by itself. `OrderListener.handle()` catches the exception,
so `onOrder` returns normally and — as far as `spring-rabbit` can tell — the
delivery succeeded. **An agent can only mark a span failed when the method it
instrumented throws.** Before the three lines below were added, the worker logged
29 processing failures in five minutes while Tempo held zero error spans:

```java
} catch (Exception e) {
  status = "error";
  Span span = Span.current();
  span.recordException(e);                            // type, message, stacktrace
  span.setStatus(StatusCode.ERROR, e.getMessage());   // what {status=error} queries
  log.error("order processing failed order.id={}", order.id(), e);
}
```

Why this happens, with diagrams: [`invisible-exception.html`](invisible-exception.html).

The sibling CLIENT spans stay `UNSET`, correctly — the Mongo insert and the MinIO
write both succeeded. The failure is business logic, and it belongs on the span
that owns the business logic. The `servicegraph` connector picks it up for free:

```sh
curl -sG localhost:9090/api/v1/query --data-urlencode 'query=traces_service_graph_request_failed_total'
```

```
order-api -> order-worker  7
```

Flat zero before the fix.

**Both services' logs, one trace:**

```sh
curl -sG localhost:3100/loki/api/v1/query_range \
  --data-urlencode "query={service_name=~\"order-.*\"} | trace_id=\"$TID\""
```

```
order-api     published orders.created order.id=6d5d74b1-... order.sku=SKU-5
order-worker  order receipted order.id=6d5d74b1-... receipt.key=6d5d74b1-....json
```

`trace_id` is on the log record because the agent's Logback instrumentation puts
it into MDC and mirrors every line into the OTLP logs pipeline. The console
pattern in `application.yaml` reads the same MDC keys, which is why
`docker compose logs` shows them too:

```
INFO [84edec4df388cc69ec8d62bd7866d215,b2394fce0dab74b1] --- shop.orders.OrderListener : order receipted
```

**Custom metrics** — note the renaming, exactly as in phase 1. `orders.created`
(monotonic sum, unit `{order}`) and `order.processing.duration` (histogram,
unit `s`) arrive as:

```sh
curl -sG localhost:9090/api/v1/query --data-urlencode 'query=sum(orders_created_total) by (channel)'
curl -sG localhost:9090/api/v1/query --data-urlencode 'query=sum(order_processing_duration_seconds_count) by (status)'
```

```
{'channel': 'web'}     692
{'channel': 'partner'}  94
{'status': 'ok'}       605
{'status': 'error'}     87
```

The app defines two instruments and twenty-one series names arrive. The rest are
free from the agent — `jvm_memory_used_bytes`, `jvm_gc_duration_seconds`,
`jvm_thread_count`, `jvm_cpu_time_seconds_total`, plus
`http_server_request_duration_seconds` for every route. Free at ingest, not free
at storage; phase 6 is where that bill comes due.

**Service graph**, derived by the `servicegraph` connector from the trace
pipeline — no application change, no extra scrape:

```sh
curl -sG localhost:9090/api/v1/query \
  --data-urlencode 'query=sum(rate(traces_service_graph_request_total[2m])) by (client,server,connection_type)'
```

```
user         -> order-api     virtual_node      1.183/s
order-api    -> order-worker  messaging_system  0.950/s
order-worker -> shop          database          1.717/s
order-worker -> unknown       virtual_node      1.900/s
```

`order-worker -> shop` is the Mongo edge; the connector reads `db.name` off the
CLIENT spans and models the database as a node. `order-worker -> unknown` is the
honest part: those are CLIENT spans to MinIO with no matching SERVER span,
because MinIO emits none. That is premise 3 of the curriculum, visible as a
metric.

Use `rate()`, not the raw counter. The counter is cumulative and keeps its value
long after the traffic that produced it has stopped — which matters when reading
the breaks below.

In Grafana: **Explore → Tempo → Service Graph** draws the same thing.

## Breaks

Each was run; the observed column is what actually happened.

| Change | Predict, then check | Observed |
|---|---|---|
| remove `-javaagent:/otel/opentelemetry-javaagent.jar` from `order-api` | zero spans from the api, no error anywhere | **0** api traces in a 60 s window vs 32 before, and 81 worker traces still flowing; zero lines matching `error` in the api log; the `otel.javaagent` startup banner is gone; log lines show `[,]` where `trace_id,span_id` were; `orders_created_total` stops having a series at all; worker traces become roots at `orders.created process` |
| `APP_DETACH_CONTEXT: "true"` on `order-worker` | worker spans orphan | `POST /orders` traces stop dead at the two CONSUMER spans with no children; in one 60 s window the Mongo and MinIO calls reappear as **73 separate root traces** — 25 `PUT`, 25 `insert shop.orders`, 23 `update shop.orders`; log lines from the `detached-drain` thread lose `trace_id` |
| `OTEL_SERVICE_NAME: order-api` on `order-worker` | the service graph collapses | a self-edge `order-api -> order-api messaging_system` appears at 0.900/s while the real `order-api -> order-worker` edge decays to 0.000/s; `basic.ack` — a worker-only span — starts reporting `order-api`; every trace remains structurally perfect |

Break 2 is worth sitting with. In the Node version of this app the Mongo
instrumentation emitted **nothing** when context was lost, while the AWS SDK
started new roots — two libraries reacting to the same bug in opposite ways. The
Java agent is uniform: every detached call becomes a root. Uniform is easier to
debug, but the underlying trap is identical, and the agent's willingness to
propagate context across thread pools it recognises makes it easier to believe
you are safe when you are not. A `BlockingQueue` drained by a thread you started
yourself is not a thread pool the agent recognises.

After each edit: `docker compose up -d <service>`.

## Tear down

```sh
docker compose down -v
```

Leaves zero containers and zero volumes with the `obs-phase-2` project label.

## Ports

| | |
|---|---|
| 3001 | order-api |
| 4317 / 4318 / 13133 | Collector OTLP gRPC / HTTP / health |
| 3000 | Grafana |
| 3200 / 3100 / 9090 | Tempo / Loki / Prometheus |
| 5672 / 15672 | RabbitMQ AMQP / management (guest:guest) |
| 27017 | MongoDB |
| 9000 / 9001 | MinIO S3 / console (minioadmin:minioadmin) |

## Memory

`docker stats` steady state, ~1.9 GB total. Two JVMs cost roughly four times
what the two Node processes did, which is the honest price of this phase:

```
grafana     308MiB   loki      92MiB   minio     117MiB   mongodb   154MiB
order-api   257MiB   worker   276MiB   otelcol    74MiB   prometheus 38MiB
rabbitmq    201MiB   tempo    423MiB   loadgen  0.7MiB
```

Both app containers run with `-XX:MaxRAMPercentage=70` under `mem_limit: 512m`.
Drop the limit to 256m and the JVM will start and then be OOM-killed under load.

## Notes

- The `mongodb` service has no `container_name`. That name collides with other
  projects on a shared machine; the compose service name is the DNS name inside
  the network, which is all the app needs. Use `docker compose logs mongodb`.
- `loadgen` waits on `order-api`'s healthcheck rather than merely on the
  container starting. A JVM takes ~5 s to reach a serving state, and without the
  health condition the first several seconds of load are connection refusals.
- The Collector's `debug` exporter from phase 1 is gone. At one order per second
  its output is unreadable; the `Do it` section shows how to put it back for one
  restart.
- The RabbitMQ client also emits `exchange.declare`, `queue.declare`,
  `queue.bind` and one `basic.ack` root trace per message. They are startup and
  protocol bookkeeping, not application work, and they are the first thing you
  would drop with a `filter` processor in phase 6.
