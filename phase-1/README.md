# Phase 1 — A real backend: Grafana LGTM

The `debug` exporter is replaced with Tempo, Loki and Prometheus. Nothing else
changes: the receiver, the processors and `send-telemetry.sh` are byte-identical
to phase 0. That is the point — OTLP is the seam.

Read [`docs/phase-1-grafana-lgtm.html`](../docs/phase-1-grafana-lgtm.html) first.

## Run

```sh
docker compose up -d
./send-telemetry.sh
open http://localhost:3000
```

Grafana has anonymous admin access enabled; there is no login.

## Verify

```sh
curl -s localhost:13133                       # Server available
./send-telemetry.sh                           # three 200s, prints the trace_id
```

Traces — the whole trace, both spans, parent set on the child:

```sh
curl -s localhost:3200/api/traces/<trace_id> | python3 -m json.tool | head -40
```

Logs — the log record carries the same `trace_id` as structured metadata:

```sh
curl -sG localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service_name="order-api"}'
```

Metrics — note the name is **not** `orders.created`:

```sh
curl -s 'localhost:9090/api/v1/query?query=orders_created_total'
```

Datasources, provisioned from `grafana/provisioning/datasources/`:

```sh
for uid in prometheus loki tempo; do
  curl -s "localhost:3000/api/datasources/uid/$uid/health"; echo
done
```

Then in Grafana Explore: paste the trace ID into Tempo, see the waterfall;
switch to Loki, run `{service_name="order-api"}`, expand the line and click
**View trace**; from a span in Tempo click **Logs for this span**.

## Breaks

| Change | Predict, then check |
|---|---|
| `docker stop tempo`, resend | Collector retries with backoff; logs and metrics unaffected; `docker start tempo` within 60 s and the trace arrives anyway |
| `otlp_http/loki` endpoint → `http://loki:3100/otlp/v1/logs` | 404 on `/otlp/v1/logs/v1/logs` — the exporter appends the suffix itself |
| Delete `tls.insecure: true` from `otlp_grpc/tempo` | `tls: first record does not look like a TLS handshake` |

After each edit: `docker restart otelcol`.

## Tear down

```sh
docker compose down -v
```

## Ports

| | |
|---|---|
| 4317 / 4318 | Collector OTLP gRPC / HTTP |
| 13133 | Collector health check |
| 3000 | Grafana |
| 3200 | Tempo HTTP API |
| 3100 | Loki |
| 9090 | Prometheus |
