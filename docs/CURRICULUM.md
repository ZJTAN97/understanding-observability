# Observability with OpenTelemetry — full build plan

A six-phase, self-hosted curriculum that ends with a MongoDB replica set, an HA
RabbitMQ cluster and MinIO fully observed on Kubernetes, with the same telemetry
pipeline pointed at both Grafana and Elastic for comparison.

This file is the spec. It is written to be handed to another agent (or another
person) who has not seen the conversation that produced it. Read
[Conventions](#conventions) and [Definition of done](#definition-of-done) before
starting any phase.

---

## Contents

- [The premise](#the-premise)
- [Stack decisions and why](#stack-decisions-and-why)
- [Hardware budget](#hardware-budget)
- [Conventions](#conventions)
- [Definition of done](#definition-of-done)
- [Phase 0 — Signals, OTLP, the Collector](#phase-0--signals-otlp-the-collector) ✅ done
- [Phase 1 — A real backend: Grafana LGTM](#phase-1--a-real-backend-grafana-lgtm) ✅ done
- [Phase 2 — Instrument the app, propagate through RabbitMQ](#phase-2--instrument-the-app-propagate-through-rabbitmq) ✅ done
- [Phase 3 — Infrastructure signals](#phase-3--infrastructure-signals)
- [Phase 4 — Swap in Elasticsearch and Kibana](#phase-4--swap-in-elasticsearch-and-kibana)
- [Phase 5 — Kubernetes on k3d](#phase-5--kubernetes-on-k3d)
- [Phase 6 — Production concerns](#phase-6--production-concerns)
- [Reference: the gotcha list](#reference-the-gotcha-list)

---

## The premise

Three facts shape everything below. An agent that misses these will build the
wrong thing.

**1. OpenTelemetry is instrumentation plus a wire protocol plus a pipe. It is not
storage and not a UI.** Layers:

```
Instrumentation   SDKs, auto-instrumentation, Prometheus exporters   ┐
OTLP              gRPC :4317 / HTTP :4318                            ├ OpenTelemetry
Collector         receive → process → export                         ┘
─────────────────────────────────────────────────────────────────────
Storage           Mimir/Prometheus · Loki · Tempo   —or—  Elasticsearch
Query & visualise Grafana                           —or—  Kibana
```

Because OTLP is the seam, swapping the bottom two layers costs a few lines of
exporter config and zero application changes. Phase 4 exists to prove that.

**2. MongoDB, RabbitMQ and MinIO do not speak OTLP and never will.** They expose
native formats and the Collector's job is to fetch and translate:

| System | Metrics | Logs | Traces |
|---|---|---|---|
| MongoDB | `serverStatus` / `replSetGetStatus` via the `mongodb` receiver; Percona `mongodb_exporter` for replica-set detail | structured JSON on stdout | **none** |
| RabbitMQ | `rabbitmq_prometheus` plugin on `:15692` (native, excellent) | stdout | **none** |
| MinIO | `/minio/v2/metrics/{cluster,node,bucket,resource}` | stdout + audit-event webhook | **none** |

**3. Traces exist only because we write an application.** Spans for these three
systems are emitted by the *client libraries* inside the demo app
(`mongodb`, `amqplib`, `@aws-sdk/client-s3`), never by the servers. Without the
demo app the project is a metrics-and-logs exercise. The app is not optional.

---

## Stack decisions and why

| Choice | Decision | Rationale |
|---|---|---|
| Collector distribution | `otel/opentelemetry-collector-contrib` | Every receiver we need (`prometheus`, `filelog`, `mongodb`, `rabbitmq`, `k8sattributes`, `fluentforward`) is contrib-only. The `core` image will fail with `unknown type`. |
| Backend A | Grafana + Prometheus/Mimir + Loki + Tempo | Lighter than Elastic. Its three query languages force you to learn what actually distinguishes the three signals. |
| Backend B | Elasticsearch + Kibana | One engine for all signals, search-first model. Ships in Phase 4 so the comparison is grounded in real use, not a feature matrix. |
| Demo app | Spring Boot 3.5 on Java 21 | Instrumented by the OTel Java agent, which covers Tomcat, Spring AMQP, the AMQP client, the Mongo driver, OkHttp and Logback — the whole path — with no code change and no OTel dependency for tracing. |
| Local runtime, phases 0–4 | Docker Compose | Kubernetes adds a second learning axis (operators, CRDs, RBAC, DaemonSets) that obscures the OTEL concepts. |
| Local runtime, phase 5 | **k3d** | ~500 MB overhead vs ~1 GB for kind and ~2 GB for minikube. Ships Traefik and a LoadBalancer so `http://grafana.localhost` works with no port-forward. Diverges slightly from upstream (sqlite instead of etcd) — irrelevant here. |
| Container runtime | OrbStack recommended over Docker Desktop | Roughly half the idle RAM, faster disk, same CLI. On 16 GB this is not cosmetic. |

---

## Hardware budget

Target machine: **Apple Silicon MacBook, 16 GB RAM, 8 cores.** This is the
binding constraint on the whole project.

| Component | Approx RSS |
|---|---|
| MongoDB replica set, 3 nodes | 1.5 GB |
| RabbitMQ cluster, 3 nodes | 1.5 GB |
| MinIO, 4 drives | 2.0 GB |
| Demo app (api + worker) | 0.4 GB |
| Collector | 0.3 GB |
| Grafana + Prometheus + Loki + Tempo | 2.0 GB |
| Elasticsearch + Kibana | 3.0 GB |
| k3s control plane (phase 5) | 0.5 GB |

**Rules that follow from this:**

- Never run the Grafana stack and the Elastic stack at the same time, except for
  the single deliberate fan-out exercise in Phase 4 — and then with the demo app
  and one infra system only.
- Set `mem_limit` on every Compose service and `resources.limits` on every
  Kubernetes pod. An unbounded Elasticsearch will take the machine down.
- Elasticsearch heap must be pinned: `ES_JAVA_OPTS=-Xms1g -Xmx1g`.
- In Phase 5, `k3d cluster create --agents 2` is enough. Three agents plus all
  workloads will not fit.

---

## Conventions

Follow these so phases stay consistent and another agent can pick up mid-stream.

### Repository layout

```
README.md                     roadmap + how to run a phase
docs/
  CURRICULUM.md               this file
  phase-N-<slug>.html         the explainer for phase N
phase-N/
  docker-compose.yaml         (phases 0–4)
  otel-collector-config.yaml
  <service>/                  per-service config, e.g. tempo/, loki/, grafana/
  README.md                   run instructions + verification for this phase only
app/                          the Spring Boot demo app (created in phase 2)
k8s/                          manifests and Helm values (created in phase 5)
```

### Version pinning

Pin every image tag. Never use `latest`. Verify a tag exists before writing it
into a file:

```sh
docker manifest inspect <image>:<tag> >/dev/null && echo ok
```

Known-good at time of writing: `otel/opentelemetry-collector-contrib:0.149.0`.
For every other image, resolve the current tag at build time and pin it.

### Collector config style

Order the top-level keys `receivers`, `processors`, `exporters`, `extensions`,
`service` — always. Comment each block with what it is for. Use named component
instances (`otlp/tempo`, `otlphttp/loki`) rather than relying on defaults, so
the pipeline wiring reads unambiguously.

Processor order inside a pipeline is significant and always:

```
memory_limiter → <detection/enrichment> → <filtering/transform> → batch
```

`memory_limiter` first so it can shed load before anything expensive runs;
`batch` last so it batches the final shape.

### Explainer HTML

Each phase ships one standalone HTML file in `docs/`. It is opened directly from
the filesystem, so it must be a complete document with no build step and no
local asset references.

Established design system — reuse it exactly, do not reinvent per phase:

| | |
|---|---|
| Fonts | Instrument Serif (display), IBM Plex Sans (body), IBM Plex Mono (code/labels), loaded from Google Fonts |
| Signal colours | metrics `--metric` blue, logs `--log` green, traces `--trace` violet — used consistently in every diagram across every phase |
| Structural colour | `--struct` indigo for OTEL-owned components |
| Warning colour | `--warn` for the thing that will bite you |
| Neutrals | cool-grey paper with a slight blue bias, not pure grey |
| Themes | full light and dark token sets; declare every token on bare `:root`, redefine under `@media (prefers-color-scheme: dark)` guarded by `:root:not([data-theme="light"])`, and again under `:root[data-theme="dark"]` |
| Diagrams | hand-authored inline SVG, `viewBox` sized to content, colours via `var(--token)`, labelled arrows, `role="img"` + `aria-label`, wrapped in `<figure>` with a `<figcaption>` that states the claim |

Copy the `<style>` block from `docs/phase-0-signals-and-collector.html` verbatim.

Required sections in every explainer:

1. Concepts, diagram-led — big pictures, few words
2. The config, annotated inline with `<span class="c-com">` comments
3. **Do it** — numbered steps with real, copied-from-terminal output
4. **Break it on purpose** — a table of changes with a "predict, then check" column
5. **Answer these before Phase N+1** — six questions

Every terminal output shown must be real output that was actually produced. Do
not invent plausible-looking log lines.

---

## Definition of done

A phase is not finished until all six hold:

1. `docker compose up -d` (or the k3d equivalent) comes up clean from an empty state.
2. The verification steps in the phase's `README.md` pass, and the output was
   pasted into the explainer.
3. Every deliberate-break scenario has been run and the observed behaviour
   matches the prediction written in the table.
4. Every image tag is pinned and verified to exist.
5. Memory limits are set on every service and the whole stack fits the budget.
6. `docker compose down -v` leaves nothing behind.

---

## Phase 0 — Signals, OTLP, the Collector

**Status: complete.** `docs/phase-0-signals-and-collector.html`, `phase-0/`.

**Objective.** Understand what is on the wire before touching any backend.

**Concepts.** The five-layer stack and where OTEL stops · the three signals and
which question each answers · the OTLP envelope (`resource` → `scope` →
records) · semantic conventions as the join key · `trace_id` on log records ·
cardinality arithmetic · the four Collector config blocks · why nothing is active
until a pipeline references it.

**Deliverables.**

```
phase-0/docker-compose.yaml         collector only, ports 4317/4318/13133
phase-0/otel-collector-config.yaml  otlp receiver → memory_limiter,batch → debug
phase-0/send-telemetry.sh           curl a trace (2 spans) + correlated log + metric
```

**Key teaching artefact.** `send-telemetry.sh` sends raw OTLP JSON with no SDK.
It emits a root SERVER span, a child PRODUCER span, a log record carrying the
same `trace_id`/`span_id` as the child, and a cumulative monotonic sum. Reading
that script *is* the OTLP data model lesson.

**Verification.** `curl -s localhost:13133` returns `Server available`;
`./send-telemetry.sh` returns three `200`s; `docker compose logs` shows the
resource attributes once, the scope once, then both spans with
`Parent ID` empty on the root and set to the root's ID on the child.

**Breaks.** Remove `debug` from `service.pipelines.traces.exporters` (traces
vanish, others survive) · remove the whole `traces:` pipeline (`POST /v1/traces`
returns 404) · misspell `batch` as `batchh` (startup fails fatally).

---

## Phase 1 — A real backend: Grafana LGTM

**Status: complete.** `docs/phase-1-grafana-lgtm.html`, `phase-1/`.

**Objective.** Replace the `debug` exporter with real storage and a real UI,
changing nothing else. Prove the seam.

**Concepts.**

- Exporters: `otlp` (gRPC) vs `otlphttp`, `tls.insecure`, `sending_queue`,
  `retry_on_failure`
- Fan-out: one pipeline, several exporters
- Grafana datasource provisioning as code
- Trace ↔ log correlation configured in the datasource, not in the data
- The three query languages: PromQL, LogQL, TraceQL — and what each cannot do

**Components.**

| Service | Role | Note |
|---|---|---|
| Tempo | traces | Native OTLP receiver on 4317. Needs `tempo.yaml` with a `local` storage backend. |
| Loki | logs | Native OTLP endpoint. Single-binary mode, filesystem storage. |
| Prometheus | metrics | Run with `--web.enable-otlp-receiver` and ingest OTLP directly at `/api/v1/otlp/v1/metrics`. Simpler and much lighter than Mimir locally. |
| Grafana | UI | Datasources provisioned from `grafana/provisioning/datasources/*.yaml`. |

Mimir is the horizontally-scalable replacement for Prometheus. Note the
difference in the explainer; do not run it locally — Prometheus is enough and
costs a fraction of the RAM.

**Collector exporter block.** Collector 0.149 renamed the `otlp` and `otlphttp`
exporters to `otlp_grpc` and `otlp_http`. The old ids still load but warn.

```yaml
exporters:
  otlp_grpc/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true            # local only, never in production
  otlp_http/loki:
    endpoint: http://loki:3100/otlp     # NOT /otlp/v1/logs — see below
  otlp_http/prometheus:
    endpoint: http://prometheus:9090/api/v1/otlp
```

**Correlation setup.** In the Tempo datasource, `tracesToLogsV2` pointing at
Loki, matching on `service.name` and filtering by `trace_id`. In the Loki
datasource, a `derivedField` that extracts `trace_id` and links to Tempo. This
is what turns the `trace_id` stamped on log records in Phase 0 into a clickable
link.

**Verification.** Run the unchanged `send-telemetry.sh` from Phase 0. Then in
Grafana Explore: find the trace by ID in Tempo, see two spans in a waterfall;
switch to Loki, find the log line, click through to the trace; query the metric
in Prometheus and note the **name has changed** (see gotchas).

**Breaks.** Stop Tempo and resend — the Collector logs export failures and
retries; observe `sending_queue` behaviour and that logs and metrics are
unaffected · point `otlphttp/loki` at `http://loki:3100/otlp/v1/logs` and watch
the 404 · remove `tls.insecure` and watch the TLS handshake fail.

**Gotchas.**

- The `otlphttp` exporter appends `/v1/logs` itself. Loki's base path is
  `/otlp`, so the endpoint is `http://loki:3100/otlp`. Doubling the suffix is
  the single most common Loki-OTLP mistake.
- Prometheus translates OTLP metric names: `orders.created` with a
  `{order}` unit and monotonic sum becomes `orders_created_total`. Dots become
  underscores, units and `_total` get appended. Expect to hunt for your metric
  the first time.
- Resource attributes are **not** promoted to Prometheus labels by default. Set
  `otlp.promote_resource_attributes: [service.name, service.namespace, deployment.environment]`
  in `prometheus.yml`, or `service.name` will not be queryable.
- Tempo refuses to start without a valid `tempo.yaml`; there is no useful
  default. Same for Loki.
- Tempo 3.x dropped the `ingester:` and `compactor:` config blocks in favour of
  a live-store/block-builder architecture. Every pre-3.0 example config fails
  with `field ingester not found in type app.Config`.
- Tempo's OTLP receiver binds `127.0.0.1` by default. Set
  `distributor.receivers.otlp.protocols.grpc.endpoint: 0.0.0.0:4317` or the
  Collector cannot reach it.
- Grafana provisioning files are read once at startup. Restart Grafana after
  editing them.

---

## Phase 2 — Instrument the app, propagate through RabbitMQ

**Status: complete.** `docs/phase-2-instrument-the-app.html`, `phase-2/`, `app/`.

**Objective.** Produce real distributed traces. This is the phase where
OpenTelemetry stops being plumbing and starts being useful.

Infrastructure in this phase is deliberately trivial — **single-node** MongoDB,
RabbitMQ and MinIO. Clustering them is Phase 3's job. Do not conflate the two.

**Concepts.**

- The Java agent: `-javaagent`, `premain`, bytecode transformation at class load
- Why instrumentation must be installed *before* application code, and why Java
  makes that structurally impossible to get wrong where Node does not
- Automatic vs manual instrumentation; when to add a custom span
- Span kinds: SERVER, CLIENT, PRODUCER, CONSUMER
- **Context propagation across a message queue** — the hard case. `traceparent`
  travels in AMQP message headers, injected on publish and extracted on consume.
- Instrumentation scope: which module emitted a span, and why two modules
  covering the same protocol both fire
- Span links vs parent-child, and why batch consumers need links
- Log correlation at source: Logback + the agent's MDC instrumentation stamping
  `trace_id`/`span_id` into every log line
- Custom metrics: a counter and a histogram, and choosing their labels

**Application shape.**

```
POST /orders  ──▶  api (Spring MVC)
                     │  validate
                     │  publish → exchange "orders", routing key "orders.created"
                     ▼
                  RabbitMQ
                     │
                     ▼
                   worker (@RabbitListener)
                     │  insert document      → MongoDB
                     │  put receipt object   → MinIO
                     │  update document      → MongoDB
```

Two separate services with two different `service.name` values — `order-api`
and `order-worker`. That is what makes the Grafana service graph non-trivial.
One jar runs as both: `SPRING_PROFILES_ACTIVE` selects the half,
`OTEL_SERVICE_NAME` selects the identity, and neither is in the code.

**Instrumentation choice.** Three paths exist for Spring Boot, and they are not
interchangeable:

| | owner | wiring | coverage |
|---|---|---|---|
| **OTel Java agent** ← *this phase* | OpenTelemetry | `-javaagent:` flag | ~130 libraries |
| OTel Spring Boot starter | OpenTelemetry | one dependency | narrower; works with native image |
| Micrometer + `micrometer-tracing-bridge-otel` | **Spring** | dependency + `management.*` properties | only what Spring instruments |

OpenTelemetry recommends the agent and treats the starter as the fallback for
when an agent cannot run. Spring recommends neither, and points at Micrometer
Observation — their objections are the agent's alpha jars, its incompatibility
with GraalVM native image and the AOT cache, and version-mismatch diagnosis. In
Spring Boot 4.0 their position ships as `org.springframework.boot:spring-boot-starter-opentelemetry`,
confusingly close in name to OpenTelemetry's own.

The agent is chosen here for three reasons, in order: it reads the same `OTEL_*`
environment variables as every other SDK in this curriculum, so one
configuration vocabulary covers all phases; it is the only zero-code path that
instruments non-Spring client libraries such as the AWS SDK; and `-javaagent` is
the direct analogue of the `--require` hook that phase 2 exists to teach.

The Micrometer path's two concrete gaps are worth knowing even if unused: it
does not instrument the AWS SDK at all, and Spring AMQP ships with
`observationEnabled = false`, so `traceparent` is never injected until you set
it explicitly on both `RabbitTemplate` and the listener container factory.

**Dependencies.**

```
org.springframework.boot:spring-boot-starter-web
org.springframework.boot:spring-boot-starter-amqp
org.springframework.boot:spring-boot-starter-data-mongodb
org.springframework.boot:spring-boot-starter-actuator
io.minio:minio                          8.5.17 — 8.6.0+ needs OkHttp 5, which does not build under Maven
io.opentelemetry:opentelemetry-api      API only, for the two hand-written metrics
```

The agent jar is **not** a dependency. It is downloaded in the Dockerfile at a
pinned version and attached to the JVM.

**Configuration by environment variable, not code.** The agent reads these:

```sh
OTEL_SERVICE_NAME=order-api
OTEL_RESOURCE_ATTRIBUTES=service.namespace=shop,deployment.environment=local,service.version=0.1.0
OTEL_EXPORTER_OTLP_ENDPOINT=http://otelcol:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_SAMPLER=always_on          # phase 6 changes this
OTEL_LOGS_EXPORTER=otlp
```

Nothing in `application.yaml` mentions OpenTelemetry.

**Load generator.** A shell loop or `k6` script producing steady traffic plus an
occasional error, so dashboards have something to show. Gate it on the API's
healthcheck, not on container start — a JVM needs ~5 s to serve.

**Verification.** One trace in Tempo containing, in order: `POST /orders`
(SERVER, order-api) → `orders publish` (PRODUCER, order-api) →
`orders.created process` (CONSUMER, order-worker) → `insert shop.orders`
(CLIENT) → `PUT` (CLIENT, the MinIO write) → `update shop.orders` (CLIENT).
Seven spans, two services, one `trace_id`. The service graph shows
`order-api → order-worker`. A `{status=error}` search in Tempo returns the
oversized orders, with an `exception` event carrying type, message and stack
trace on the CONSUMER span.

**Breaks.**

- Remove `-javaagent` from `order-api` — the service produces nothing, logs no
  error, and loses `trace_id` from every log line while continuing to serve
  traffic normally. This is the number-one real-world OTEL failure and must be
  experienced once.
- Consume messages into a `BlockingQueue` and process them on a thread you
  started yourself — context is lost and the worker's spans become orphan roots.
- Set `OTEL_SERVICE_NAME` identically for api and worker — the service graph
  collapses into a self-edge and correlation becomes useless.

**Gotchas.**

- The agent must be attached at JVM start. There is no equivalent of Node's
  "imported the app first" mistake, because `premain` runs before `main` by
  construction — but there is no warning either when the flag is simply absent.
- Pin the agent version. Every tutorial links
  `releases/latest/download/opentelemetry-javaagent.jar`, which silently changes
  your instrumentation between two builds of the same commit.
- Two instrumentation modules cover RabbitMQ: `rabbitmq-2.7` (the AMQP client)
  and `spring-rabbit-1.0` (the `@RabbitListener`). Both emit a CONSUMER span for
  one message. Disabling the shallow-looking one destroys the trace, because
  header injection lives there and not in the Spring module.
- The MinIO Java client is not the AWS SDK. Its spans come from OkHttp and are
  named `PUT`, with no `aws.s3.*` attributes — the bucket and key survive only
  inside `url.full`. Use `software.amazon.awssdk:s3` if you want S3 semantics.
- **A caught exception is invisible to the agent.** Instrumentation marks a span
  ERROR only when the method it wrapped throws. A `try/catch` inside your own
  method never crosses a library boundary, so a service that degrades gracefully
  degrades silently in its traces. Measured here: 29 logged failures, 0 error
  spans. The remedy is two lines of the OTel API —
  `Span.current().recordException(e)` and `setStatus(StatusCode.ERROR, ...)`.
  Letting the exception escape a `@RabbitListener` instead trades the silent
  failure for a requeue loop unless `default-requeue-rejected=false` or a DLQ is
  configured.
- MongoDB instrumentation can capture query documents
  (`otel.instrumentation.mongo.statement-sanitizer.enabled=false`). Useful
  locally, a PII and cardinality hazard elsewhere. Say so in the explainer.
- A JVM under `mem_limit: 256m` starts and is then OOM-killed under load. Budget
  512m per service and set `-XX:MaxRAMPercentage`.
- The AMQP client emits `exchange.declare`, `queue.declare`, `queue.bind` and a
  `basic.ack` root trace per message. Protocol bookkeeping, not application
  work, and the first candidate for a `filter` processor.

---

## Phase 3 — Infrastructure signals

**Objective.** Observe the three real systems. This is the phase the whole
project exists for.

Upgrade the Phase 2 infrastructure to its real topology: MongoDB **3-node
replica set**, RabbitMQ **3-node cluster with quorum queues**, MinIO **4-drive
erasure set**.

**Concepts.**

- The `prometheus` receiver: the Collector as a scraper, `scrape_configs`
  identical to Prometheus's own
- Purpose-built receivers (`mongodb`, `rabbitmq`) vs scraping an exporter — what
  each gives up
- Log collection on Docker Desktop, where `/var/lib/docker/containers` is not
  reachable from the host
- OTTL: the `transform` and `filter` processors, and dropping high-cardinality
  series *at the pipe* rather than at storage
- `resourcedetection` and `attributes` processors for consistent resource
  attributes across scraped and pushed telemetry
- Reading a replica-set election, a queue backlog and a degraded erasure set
  from metrics alone

**Metric sources.**

| System | How | Requires |
|---|---|---|
| MongoDB, basics | `mongodb` receiver | a user with the `clusterMonitor` role |
| MongoDB, replica set detail | `prometheus` receiver scraping Percona `mongodb_exporter` | `--collect-all --compatible-mode` |
| RabbitMQ | `prometheus` receiver scraping `:15692/metrics` and `/metrics/detailed` | `rabbitmq_prometheus` plugin enabled |
| MinIO | `prometheus` receiver scraping `/minio/v2/metrics/cluster` and `/node` | see auth gotcha below |

The built-in `mongodb` receiver does not expose replication lag or oplog window.
Those are the two metrics that matter most for a replica set, which is why the
Percona exporter is also needed. Explain that trade-off rather than hiding it.

**Metrics that must appear on the dashboards.**

- *MongoDB* — replication lag per secondary, oplog window (hours), primary
  elections, `connections.current` against `available`, opcounters by type,
  WiredTiger cache dirty/used bytes, queued readers/writers, page faults
- *RabbitMQ* — messages ready and unacknowledged per queue, publish and deliver
  rates, consumer count, memory and disk alarms, quorum-queue leader per queue,
  network partitions, file descriptors used
- *MinIO* — drives online vs offline, capacity used vs free, S3 request rate and
  error rate by API, TTFB distribution, healing status, per-bucket usage

**Log collection.** On macOS with Docker Desktop or OrbStack, bind-mounting
`/var/lib/docker/containers` does not work — the path lives inside the VM. Use
the Docker `fluentd` logging driver pointed at the Collector's `fluentforward`
receiver:

```yaml
# on each service
logging:
  driver: fluentd
  options:
    fluentd-address: localhost:24224
    tag: "{{.Name}}"
```

```yaml
# collector
receivers:
  fluentforward:
    endpoint: 0.0.0.0:24224
```

This is portable and works identically on Linux. The `filelog` receiver is still
worth demonstrating on a bind-mounted MongoDB `--logpath` file, because
`filelog` is what Phase 5 uses on Kubernetes.

**Cardinality work.** MongoDB emits per-collection and per-index metrics; MinIO
emits per-bucket metrics. Both grow with usage. Use `filter/metrics` with OTTL
to drop what is not on a dashboard, and say in the explainer how many series
that removed.

**Verification.** A Grafana dashboard per system, all populated. Log lines from
all three systems queryable in Loki with correct `service.name`. A trace from
the demo app that lands in the same time window as a MongoDB log line, found by
correlation.

**Breaks — these are the real exercise.**

| Do this | Observe |
|---|---|
| `docker kill` the MongoDB primary | election in the metrics, a new primary, lag spike on the returning node, oplog catch-up |
| Stop the worker, keep traffic running | RabbitMQ `messages_ready` climbs, consumer count drops to zero, memory alarm eventually |
| `docker kill` one MinIO drive container | drives-offline metric, cluster stays readable, healing starts on return |
| Add `user_id` as a metric label in the app | watch series count climb in Prometheus `/status` — the Phase 0 lesson, in production |

---

## Phase 4 — Swap in Elasticsearch and Kibana

**Objective.** Prove the seam claim from Phase 0 and form a real opinion about
the two backends. The application and every receiver stay untouched; only the
`exporters` and `service.pipelines` blocks change.

**Components.** Elasticsearch single-node with security disabled for local use,
plus Kibana. Use the Collector's `elasticsearch` exporter with
`mapping.mode: otel`, which writes to the OTel-native data streams
(`traces-generic.otel-default`, `logs-generic.otel-default`,
`metrics-generic.otel-default`) that Kibana's Observability app understands.

```yaml
exporters:
  elasticsearch:
    endpoints: [http://elasticsearch:9200]
    mapping:
      mode: otel
    logs_dynamic_index:    { enabled: true }
    traces_dynamic_index:  { enabled: true }
    metrics_dynamic_index: { enabled: true }
```

Elastic also ships EDOT, its own Collector distribution. Mention it; do not use
it — using the same stock contrib Collector is precisely what demonstrates the
point.

**The fan-out exercise.** For one session only, add both backends to every
pipeline:

```yaml
service:
  pipelines:
    traces:
      exporters: [otlp/tempo, elasticsearch]
```

Identical data, two stores, side by side. Run it with the demo app and one infra
system only — the RAM will not take more.

**Comparison to fill in with measurements, not opinions.**

| Dimension | Measure it by |
|---|---|
| Storage footprint | `GET _cat/indices?v` bytes vs `du -sh` on the Loki and Tempo volumes, same ingest |
| Memory | `docker stats` steady-state RSS for each stack |
| Query ergonomics | write the same three questions in PromQL/LogQL/TraceQL and in ES\|QL/KQL, time yourself |
| Correlation UX | trace → logs and log → trace, click count in each UI |
| Ad-hoc search | "find every occurrence of this stack trace in the last 24h" — Loki without a matching label vs Elasticsearch |
| Alerting | build one identical alert in both |
| Cost model | index-everything vs index-labels-only, and what that means at 100× the volume |

Expect the honest answer to be: Elastic wins ad-hoc search decisively, Grafana
wins metrics ergonomics and cost decisively, and correlation is roughly a tie.

**Gotchas.**

- `xpack.security.enabled=false` and `discovery.type=single-node` for local, and
  say clearly in the explainer that both are unacceptable in production.
- Pin the heap: `ES_JAVA_OPTS=-Xms1g -Xmx1g`. Elasticsearch will otherwise size
  itself to the host and evict everything else.
- `vm.max_map_count` must be at least 262144. On Docker Desktop and OrbStack it
  is set inside the VM, not on macOS.
- Kibana must match the Elasticsearch major version exactly.
- Elasticsearch is slow to become healthy. Give Kibana a `depends_on` with
  `condition: service_healthy` or it will crash-loop on first boot.

---

## Phase 5 — Kubernetes on k3d

**Objective.** Move everything onto Kubernetes and learn the two-tier Collector
topology that real clusters use.

**Cluster.**

```sh
k3d cluster create obs \
  --agents 2 \
  --port "8080:80@loadbalancer" \
  --k3s-arg "--disable=metrics-server@server:0"
```

Import the locally-built demo app image with `k3d image import` — there is no
registry.

**Concepts.**

- **Agent vs gateway.** A DaemonSet Collector on every node collects
  node-local data (pod logs, kubelet stats, host metrics) and forwards OTLP to a
  Deployment Collector that does cluster-wide work (enrichment, tail sampling,
  export). Draw this; it is the single most important diagram of the phase.
- The **OpenTelemetry Operator** (`OpenTelemetryCollector` and `Instrumentation`
  CRDs, auto-instrumentation by pod annotation) versus the plain Helm chart.
  The Operator needs cert-manager. Show the Operator, but be explicit that the
  Helm chart alone is a legitimate choice.
- `k8sattributes` processor — how pod IP to pod metadata lookup works, the RBAC
  it needs, and why every signal should carry `k8s.namespace.name`,
  `k8s.pod.name`, `k8s.deployment.name`
- `filelog` on `/var/log/pods/*/*/*.log` with the `container` parser
- `kubeletstats` receiver for pod and container resource metrics
- The Target Allocator, and how Prometheus `ServiceMonitor` CRDs get discovered
  and sharded across Collector replicas

**Workloads.** Use operators or charts that produce genuine HA topologies:
MongoDB Community Operator or a Bitnami chart in replica-set mode, the RabbitMQ
Cluster Operator, and the MinIO Operator or tenant chart. Grafana, Prometheus,
Loki and Tempo as individual charts with explicit small resource limits — not
`kube-prometheus-stack`, which will not fit.

**RBAC.** `k8sattributes` needs `get`/`list`/`watch` on `pods` and `namespaces`
cluster-wide; `kubeletstats` needs `nodes/stats` and `nodes/proxy`. Getting a
403 here and reading the Collector's own logs to find it is a worthwhile
exercise — leave it as a deliberate break.

**Verification.** Every span, metric and log carries the correct
`k8s.pod.name` and `k8s.namespace.name`. Killing a Mongo pod shows the election
in Grafana *and* attributes the change to the right pod. `http://grafana.localhost:8080`
resolves through Traefik with no port-forward.

---

## Phase 6 — Production concerns

**Objective.** Everything that separates a demo from something you would run.

**Sampling.**

- Head sampling: `OTEL_TRACES_SAMPLER=parentbased_traceidratio` — cheap, decided
  at the first span, and therefore blind to whether the trace turned out to be
  interesting.
- Tail sampling: the `tailsampling` processor with `latency`, `status_code`,
  `probabilistic` and `rate_limiting` policies composed together — keeps every
  error and every slow trace, drops most of the boring ones.
- The consequence that catches people out: tail sampling requires **all spans of
  a trace to reach the same Collector instance**. That forces a gateway tier and
  a `loadbalancing` exporter with `routing_key: traceID` in front of it. Draw
  this; it is why Phase 5's two-tier topology exists.

**Cardinality and cost control.** `filter` and `transform` (OTTL) to drop series
and redact attributes · `deltatocumulative` where a backend needs cumulative ·
metric renaming and aggregation with `metricstransform` · a written policy for
what may and may not be a metric label.

**Reliability of the pipeline itself.** `sending_queue` backed by the
`file_storage` extension so a backend restart does not lose data ·
`retry_on_failure` tuning · `memory_limiter` sizing · what actually happens when
the queue fills.

**Observing the Collector.** It emits its own metrics. Scrape them and alert on
them:

```
otelcol_receiver_accepted_spans / otelcol_receiver_refused_spans
otelcol_exporter_sent_spans     / otelcol_exporter_send_failed_spans
otelcol_processor_dropped_metric_points
otelcol_exporter_queue_size     / otelcol_exporter_queue_capacity
```

An unmonitored telemetry pipeline that silently drops data is worse than no
pipeline, because it produces confident, wrong dashboards.

**Alerting and SLOs.** Define an SLO for the demo app, implement multi-window
multi-burn-rate alerts in Grafana, and build the same alert in Kibana. Alert on
symptoms (latency, error rate, queue depth) rather than causes (CPU).

**Also cover.** Secure OTLP with TLS and auth headers · semantic-convention
drift and schema URLs · what to do when the Collector is the bottleneck.

---

## Reference: the gotcha list

Consolidated, in the order they will be hit.

1. Using `otel/opentelemetry-collector` instead of `-contrib`. Most receivers
   are missing. Symptom: `unknown type: "filelog"`.
2. Defining a component but never listing it in `service.pipelines`. It is
   silently inert. Always the first thing to check when data does not arrive.
3. Doubling the OTLP path suffix on Loki. The exporter appends `/v1/logs`, so
   the endpoint is `http://loki:3100/otlp`.
4. Expecting your OTLP metric name in Prometheus unchanged. `orders.created`
   becomes `orders_created_total`.
5. Expecting resource attributes to be Prometheus labels. They are not, unless
   promoted explicitly.
6. Starting the JVM without `-javaagent`, or pinning it to
   `releases/latest/download/`. Symptom: zero spans and no error, or
   instrumentation that changes version between two builds of one commit.
7. Losing context in a RabbitMQ consumer by handing the message to a thread the
   agent does not recognise. Symptom: orphan root spans in the worker.
8. Reusing one `service.name` across two services. Correlation and the service
   graph both degrade silently.
9. MinIO metrics returning 401. Either set
   `MINIO_PROMETHEUS_AUTH_TYPE=public` for local, or generate a bearer token
   with `mc admin prometheus generate` and put it in the scrape config.
10. Bind-mounting `/var/lib/docker/containers` on macOS. It is not on the host.
    Use the `fluentd` logging driver into the `fluentforward` receiver.
11. Elasticsearch with no heap limit. It sizes to the host and starves
    everything else.
12. Unbounded metric labels — `user_id`, request IDs, full URLs, raw queries.
    Four labels at realistic cardinality is 14,400 series; one user ID label
    makes it 144,000,000.
13. Not calling `sdk.shutdown()` on `SIGTERM`. The final batch is lost on every
    deploy.
14. Tail sampling behind a plain round-robin load balancer. Spans of one trace
    land on different Collectors and traces come out incomplete.
15. Semantic conventions that moved — `http.method` became
    `http.request.method`. An empty dashboard panel is usually a renamed
    attribute.
