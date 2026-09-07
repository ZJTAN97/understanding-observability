# Observability with OpenTelemetry — full build plan

An eight-phase, self-hosted curriculum that ends with a Spring Boot application
on Kubernetes — auto-instrumented by the OpenTelemetry Operator — plus a
RabbitMQ cluster in-cluster and a MongoDB replica set and MinIO deliberately
*outside* the cluster, all observed in Elasticsearch and Kibana.

**Elastic is the destination.** Phases 1–3 build the same pipeline against
Grafana first, because its three query languages force you to learn what
actually distinguishes the three signals — a lesson Elastic's one-engine model
hides. Phase 4 repoints the pipeline at Elastic and keeps it there.

The target environment being mirrored, from Phase 5 onwards:

| | |
|---|---|
| Kubernetes | Spring Boot services, instrumented by `instrumentation.opentelemetry.io/inject-java` — no code and no image change |
| Kubernetes | RabbitMQ as a Helm chart |
| Outside the cluster | MongoDB and MinIO on their own machines |
| Backend | Elasticsearch with Kibana APM / Observability |

Phase 7 ends with three documents a colleague can follow without reading this
file. That is the actual point of the project.

This file is the spec. It is written to be handed to another agent (or another
person) who has not seen the conversation that produced it. Read
[Conventions](#conventions) and [Definition of done](#definition-of-done) before
starting any phase.

---

## Contents

- [Observability with OpenTelemetry — full build plan](#observability-with-opentelemetry--full-build-plan)
  - [Contents](#contents)
  - [The premise](#the-premise)
  - [Stack decisions and why](#stack-decisions-and-why)
  - [Hardware budget](#hardware-budget)
  - [Conventions](#conventions)
    - [Repository layout](#repository-layout)
    - [Version pinning](#version-pinning)
    - [Collector config style](#collector-config-style)
    - [Explainer HTML](#explainer-html)
  - [Definition of done](#definition-of-done)
  - [Phase 0 — Signals, OTLP, the Collector](#phase-0--signals-otlp-the-collector)
  - [Phase 1 — A real backend: Grafana LGTM](#phase-1--a-real-backend-grafana-lgtm)
  - [Phase 2 — Instrument the app, propagate through RabbitMQ](#phase-2--instrument-the-app-propagate-through-rabbitmq)
  - [Phase 3 — Infrastructure signals](#phase-3--infrastructure-signals)
  - [Phase 4 — Elastic as the destination](#phase-4--elastic-as-the-destination)
  - [Phase 5 — Kubernetes and the OpenTelemetry Operator](#phase-5--kubernetes-and-the-opentelemetry-operator)
  - [Phase 6 — The hybrid boundary: infrastructure outside the cluster](#phase-6--the-hybrid-boundary-infrastructure-outside-the-cluster)
  - [Phase 7 — Production concerns and team practices](#phase-7--production-concerns-and-team-practices)
  - [Reference: the gotcha list](#reference-the-gotcha-list)

---

## The premise

Four facts shape everything below. An agent that misses these will build the
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
exporter config and zero application changes. Phase 4 proves that by moving from
Grafana to Elastic without touching the application or a single receiver.

**2. MongoDB, RabbitMQ and MinIO do not speak OTLP and never will.** They expose
native formats and the Collector's job is to fetch and translate:

| System   | Metrics                                                                                                           | Logs                         | Traces   |
| -------- | ----------------------------------------------------------------------------------------------------------------- | ---------------------------- | -------- |
| MongoDB  | `serverStatus` / `replSetGetStatus` via the `mongodb` receiver; Percona `mongodb_exporter` for replica-set detail | structured JSON on stdout    | **none** |
| RabbitMQ | `rabbitmq_prometheus` plugin on `:15692` (native, excellent)                                                      | stdout                       | **none** |
| MinIO    | `/minio/v2/metrics/{cluster,node,bucket,resource}`                                                                | stdout + audit-event webhook | **none** |

**3. Traces exist only because we write an application.** Spans for these three
systems are emitted by the *client libraries* inside the demo app — the MongoDB
Java driver, Spring AMQP, the AWS SDK v2 S3 client — never by the servers.
Without the demo app the project is a metrics-and-logs exercise. The app is not
optional.

**4. The estate spans an administrative boundary, and that is deliberate.** From
Phase 5 the application and RabbitMQ run on Kubernetes; from Phase 6 MongoDB and
MinIO run outside it. Half the telemetry therefore has no `k8s.*` identity, is
not discoverable by Kubernetes service discovery, and belongs to credentials
someone else owns. Every published tutorial assumes a single-world cluster.
Phase 6 exists because the real environment is not one, and an agent that
quietly moves Mongo and MinIO into the cluster has deleted the hardest and most
valuable phase.

---

## Stack decisions and why

| Choice                    | Decision                                  | Rationale                                                                                                                                                                                                                         |
| ------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Collector distribution    | `otel/opentelemetry-collector-contrib`    | Every receiver we need (`prometheus`, `filelog`, `mongodb`, `rabbitmq`, `k8sattributes`, `fluentforward`) is contrib-only. The `core` image will fail with `unknown type`.                                                        |
| Teaching backend, phases 1–3 | Grafana + Prometheus/Mimir + Loki + Tempo | Lighter than Elastic. Its three query languages force you to learn what actually distinguishes the three signals — which is precisely what a single search engine lets you skip. A scaffold, removed in Phase 4.              |
| **Destination backend, phases 4–7** | **Elasticsearch + Kibana**       | The backend the target environment runs. One engine for all signals, search-first, with APM as the trace UI. Everything from Phase 4 on assumes it.                                                                             |
| Route into Elastic       | contrib Collector, `elasticsearch` exporter | Keeps one pipeline shape across both backends, which is what makes the seam claim demonstrable. Phase 4 documents the OTLP-to-APM-Server and EDOT alternatives and when each is right.                                        |
| Demo app                  | Spring Boot 3.5 on Java 21                | Instrumented by the OTel Java agent, which covers Tomcat, Spring AMQP, the AMQP client, the Mongo driver, OkHttp and Logback — the whole path — with no code change and no OTel dependency for tracing.                           |
| Java instrumentation delivery | baked `-javaagent` in phases 2–4, Operator injection from phase 5 | Both survive side by side in Phase 5 so the trade-off table is written from experience. Injection is what the target environment uses; the baked agent is what you fall back to when the webhook is not there. |
| Local runtime, phases 0–4 | Docker Compose                            | Kubernetes adds a second learning axis (operators, CRDs, RBAC, DaemonSets) that obscures the OTEL concepts.                                                                                                                       |
| Local runtime, phases 5–7 | **k3d**                                   | ~500 MB overhead vs ~1 GB for kind and ~2 GB for minikube. Ships Traefik and a LoadBalancer so a hostname works with no port-forward. Diverges slightly from upstream (sqlite instead of etcd) — irrelevant here.               |
| Off-cluster infra, phases 6–7 | plain Docker on the host, outside the k3d network | Reproduces the network and identity boundary — no `k8s.*` attributes, no Kubernetes discovery, `host.k3d.internal` to reach it — at near-zero RAM cost. Real VMs would add systemd, host metrics and OS patching; that gap is stated in the Phase 6 explainer rather than papered over. |
| Container runtime         | OrbStack recommended over Docker Desktop  | Roughly half the idle RAM, faster disk, same CLI. On 16 GB this is not cosmetic.                                                                                                                                                  |

---

## Hardware budget

Target machine: **Apple Silicon MacBook, 16 GB RAM, 8 cores.** This is the
binding constraint on the whole project.

| Component                           | Approx RSS |
| ----------------------------------- | ---------- |
| MongoDB replica set, 3 nodes        | 1.5 GB     |
| RabbitMQ cluster, 3 nodes           | 1.5 GB     |
| MinIO, 4 drives                     | 2.0 GB     |
| Demo app (api + worker)             | 0.4 GB     |
| Collector                           | 0.3 GB     |
| Grafana + Prometheus + Loki + Tempo | 2.0 GB     |
| Elasticsearch + Kibana              | 3.0 GB     |
| k3s control plane (phases 5–7)      | 0.5 GB     |
| Collector fleet (agent DaemonSet ×3 + gateway + 2 off-cluster) | 0.6 GB |

**Rules that follow from this:**

- Never run the Grafana stack and the Elastic stack at the same time, except for
  the single deliberate fan-out exercise in the Phase 4 appendix — and then with
  the demo app and one infra system only.
- Set `mem_limit` on every Compose service and `resources.limits` on every
  Kubernetes pod. An unbounded Elasticsearch will take the machine down.
- Elasticsearch heap must be pinned: `ES_JAVA_OPTS=-Xms1g -Xmx1g`.
- In Phase 5, `k3d cluster create --agents 2` is enough. Three agents plus all
  workloads will not fit.
- From Phase 5, Elasticsearch and Kibana stay on Compose rather than moving into
  the cluster. A real cluster does not host its own backend either, so this is
  fidelity as well as thrift.
- Phase 5 runs the app and RabbitMQ only. Phase 6 adds MongoDB and MinIO
  off-cluster. Do not attempt to run the Phase 3 Compose stack and the Phase 5
  cluster simultaneously — Phase 6's off-cluster containers replace it.

---

## Conventions

Follow these so phases stay consistent and another agent can pick up mid-stream.

### Repository layout

```
README.md                     roadmap + how to run a phase
docs/
  CURRICULUM.md               this file
  phase-N-<slug>.html         the explainer for phase N
  onboarding-a-service.md     team doc  (created in phase 7)
  attribute-policy.md         team doc  (created in phase 7)
  review-checklist.md         team doc  (created in phase 7)
phase-N/
  docker-compose.yaml         (phases 0–4)
  otel-collector-config.yaml
  <service>/                  per-service config, e.g. tempo/, loki/, grafana/
  README.md                   run instructions + verification for this phase only
phase-5/
  k8s/                        manifests, Helm values, Operator CRs
phase-6/
  k8s/                        gateway + agent Collector config for both designs
  off-cluster/                docker-compose.yaml for Mongo + MinIO, outside k3d
  off-cluster-collector/      the VM-side Collector config (design B)
app/                          the Spring Boot demo app (created in phase 2)
```

Phases 5–7 are Kubernetes phases and have no `docker-compose.yaml` of their own
for the cluster workloads, but each keeps one for the pieces that stay outside
it — Elasticsearch and Kibana, and from Phase 6 the off-cluster infra.

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

|                   |                                                                                                                                                                                                                    |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Fonts             | Instrument Serif (display), IBM Plex Sans (body), IBM Plex Mono (code/labels), loaded from Google Fonts                                                                                                            |
| Signal colours    | metrics `--metric` blue, logs `--log` green, traces `--trace` violet — used consistently in every diagram across every phase                                                                                       |
| Structural colour | `--struct` indigo for OTEL-owned components                                                                                                                                                                        |
| Warning colour    | `--warn` for the thing that will bite you                                                                                                                                                                          |
| Neutrals          | cool-grey paper with a slight blue bias, not pure grey                                                                                                                                                             |
| Themes            | full light and dark token sets; declare every token on bare `:root`, redefine under `@media (prefers-color-scheme: dark)` guarded by `:root:not([data-theme="light"])`, and again under `:root[data-theme="dark"]` |
| Diagrams          | hand-authored inline SVG, `viewBox` sized to content, colours via `var(--token)`, labelled arrows, `role="img"` + `aria-label`, wrapped in `<figure>` with a `<figcaption>` that states the claim                  |

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

A phase is not finished until all seven hold:

1. `docker compose up -d` (or the k3d equivalent) comes up clean from an empty state.
2. The verification steps in the phase's `README.md` pass, and the output was
   pasted into the explainer.
3. Every deliberate-break scenario has been run and the observed behaviour
   matches the prediction written in the table.
4. Every image tag is pinned and verified to exist.
5. Memory limits are set on every service and the whole stack fits the budget.
6. `docker compose down -v` (or `k3d cluster delete obs`) leaves nothing behind.
7. The six "answer these before Phase N+1" questions in the explainer can
   actually be answered from what was built, not from what was read.

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

| Service    | Role    | Note                                                                                                                                      |
| ---------- | ------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Tempo      | traces  | Native OTLP receiver on 4317. Needs `tempo.yaml` with a `local` storage backend.                                                          |
| Loki       | logs    | Native OTLP endpoint. Single-binary mode, filesystem storage.                                                                             |
| Prometheus | metrics | Run with `--web.enable-otlp-receiver` and ingest OTLP directly at `/api/v1/otlp/v1/metrics`. Simpler and much lighter than Mimir locally. |
| Grafana    | UI      | Datasources provisioned from `grafana/provisioning/datasources/*.yaml`.                                                                   |

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

|                                               | owner         | wiring                                 | coverage                          |
| --------------------------------------------- | ------------- | -------------------------------------- | --------------------------------- |
| **OTel Java agent** ← *this phase*            | OpenTelemetry | `-javaagent:` flag                     | ~130 libraries                    |
| OTel Spring Boot starter                      | OpenTelemetry | one dependency                         | narrower; works with native image |
| Micrometer + `micrometer-tracing-bridge-otel` | **Spring**    | dependency + `management.*` properties | only what Spring instruments      |

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

**Status: complete.** `docs/phase-3-infrastructure-signals.html`, `phase-3/`.

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

| System                      | How                                                                     | Requires                              |
| --------------------------- | ----------------------------------------------------------------------- | ------------------------------------- |
| MongoDB, basics             | `mongodb` receiver                                                      | a user with the `clusterMonitor` role |
| MongoDB, replica set detail | `prometheus` receiver scraping Percona `mongodb_exporter`               | `--collect-all --compatible-mode`     |
| RabbitMQ                    | `prometheus` receiver scraping `:15692/metrics` and `/metrics/detailed` | `rabbitmq_prometheus` plugin enabled  |
| MinIO                       | `prometheus` receiver scraping `/minio/v2/metrics/cluster` and `/node`  | see auth gotcha below                 |

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

| Do this                                    | Observe                                                                                 |
| ------------------------------------------ | --------------------------------------------------------------------------------------- |
| `docker kill` the MongoDB primary          | election in the metrics, a new primary, lag spike on the returning node, oplog catch-up |
| Stop the worker, keep traffic running      | RabbitMQ `messages_ready` climbs, consumer count drops to zero, memory alarm eventually |
| `docker kill` one MinIO drive container    | drives-offline metric, cluster stays readable, healing starts on return                 |
| Add `user_id` as a metric label in the app | watch series count climb in Prometheus `/status` — the Phase 0 lesson, in production    |

---
## Phase 4 — Elastic as the destination

**Objective.** Get the whole Phase 3 pipeline landing in Elasticsearch, and
learn Kibana's Observability and APM apps well enough to teach them. This is no
longer a comparison exercise — Elastic is where this project is going. The
Grafana comparison survives as an appendix at the end of the phase, because
having built both is exactly what lets you defend the choice to a team.

The application and every receiver stay untouched. Only `exporters` and
`service.pipelines` change. That is still the seam claim from Phase 0, and it is
still worth proving — you just prove it in the direction that matters.

**Concepts.**

- The three routes OTel data can take into Elastic, and which one a given
  cluster is using
- `mapping.mode: otel` vs `ecs` — the mapping decision, and why it is close to
  one-way
- Data streams, index templates, component templates, ILM: where telemetry
  physically lands and what ages it out
- ES|QL, and how it differs from writing three languages against three stores
- The APM app: services, transactions, dependencies, the service map — and what
  in the OTLP payload each view is actually reading
- Log ↔ trace correlation in Elastic, which is a field join (`trace.id`) rather
  than a datasource configuration

### The three routes in

Know all three. Your work cluster uses one of them, and which one changes who
owns the config.

| Route | How | When it is right |
| --- | --- | --- |
| **A. contrib Collector → `elasticsearch` exporter** | The stock `otel/opentelemetry-collector-contrib` image writes directly to `:9200` over the ES bulk API | Self-managed Elastic; you already run a Collector; you want one pipeline shape across every backend. **This is what this phase builds.** |
| **B. Collector → OTLP → APM Server / managed OTLP intake** | Export OTLP to Elastic's own OTLP endpoint and let Elastic do the mapping server-side | Elastic Cloud, or an existing APM Server. Fewer knobs, Elastic owns the mapping, version-coupled to the stack. |
| **C. EDOT — Elastic Distribution of OpenTelemetry** | Elastic's own Collector build and SDK distros, preconfigured for Elastic | Elastic-supported path with curated dashboards and defaults. Upstream OTel plus Elastic opinions. |

Build route A. Then read the EDOT collector's shipped config and diff it against
yours — that diff is a list of the opinions Elastic holds about telemetry, and
it is genuinely instructive. Note in the explainer which route the work cluster
uses and how you determined that.

**Components.** Elasticsearch single-node with security disabled for local use,
plus Kibana. Pin the heap.

```yaml
exporters:
  elasticsearch:
    endpoints: [http://elasticsearch:9200]
    mapping:
      mode: otel               # see the mapping decision below
    logs_dynamic_index:    { enabled: true }
    traces_dynamic_index:  { enabled: true }
    metrics_dynamic_index: { enabled: true }
```

`mapping.mode: otel` writes the OTel-native data streams
(`traces-generic.otel-default`, `logs-generic.otel-default`,
`metrics-generic.otel-default`) that Kibana's Observability app understands.

### The mapping decision

The single most consequential line in the exporter config.

| | `mode: otel` | `mode: ecs` |
| --- | --- | --- |
| Field names | OTel semconv, dots preserved (`http.request.method`) | Elastic Common Schema; ECS and semconv have been converging but are not identical |
| Attributes | nested under `attributes`, `resource.attributes`, `scope.attributes` | flattened to top-level ECS fields |
| Kibana APM | native support, the intended path | works, historically the better-supported path |
| Pre-existing ECS dashboards and Beats data | will not match | will match |
| Reversibility | reindexing is the only way back | same |

Pick `otel`, document why, and be explicit in the explainer that a team with
years of ECS dashboards and Filebeat data has a real reason to pick `ecs`
instead. Mixed modes in one deployment is the failure case: two field names for
one concept, and every dashboard works for half the data.

### Storage and lifecycle

The part Grafana users skip and then get billed for.

- Find the data streams: `GET _data_stream/*otel*`
- Find what shaped them: `GET _index_template/*otel*` and the component
  templates it composes
- Field mappings are the cost driver, not row count. `GET <index>/_mapping` and
  count the fields. An unbounded attribute key namespace is a mapping explosion,
  which is the Elastic-shaped version of the Phase 0 cardinality lesson —
  Phase 7 does the arithmetic.
- Attach an ILM policy with a short hot phase and a delete phase, then verify it
  applied. Untouched local defaults keep everything forever.

### Kibana

- **Observability → Logs** with `service.name` filters; confirm the
  `trace.id` field is populated on app logs (that is Phase 2's Logback MDC
  work arriving)
- **APM → Services**: `order-api` and `order-worker` as distinct services;
  transaction latency distribution; the dependency map showing MongoDB,
  RabbitMQ and MinIO as downstream dependencies inferred from client spans
- **The service map** — check whether the RabbitMQ hop connects `order-api`
  to `order-worker`. If it does not, context propagation broke; that is a
  Phase 2 regression and worth catching here.
- Click from a slow transaction to its logs and back. Count the clicks; you
  will compare that number in the appendix.
- Write three questions in ES|QL that you already wrote in PromQL, LogQL and
  TraceQL in Phase 3.

**Verification.** All three signals from all three infra systems plus the demo
app visible in Kibana. A trace opened from APM, its logs reachable in one click,
and the MongoDB log line from the same time window findable by free-text
search. `GET _cat/indices?v` shows the otel data streams with non-zero doc
counts.

**Breaks.**

| Do this | Predict, then check |
| --- | --- |
| Switch one pipeline to `mapping.mode: ecs`, leave the others on `otel` | which Kibana views break, and what the field names become |
| Stop Elasticsearch, keep sending | Collector `sending_queue` fills, then refuses; watch `otelcol_exporter_send_failed_*` |
| Drop `service.name` in a `transform` processor | what APM shows for that service, and where the data actually went |
| Send a metric with 50k distinct attribute values | mapping and index size, not series count — the different shape of the same mistake |

### Appendix: the Grafana comparison

You have now run both stacks over identical data. Fill this in with
measurements, not opinions. For one session only, fan out every pipeline to both
backends — with the demo app and one infra system only, because the RAM will not
take more:

```yaml
service:
  pipelines:
    traces:
      exporters: [otlp_grpc/tempo, elasticsearch]
```

| Dimension | Measure it by |
| --- | --- |
| Storage footprint | `GET _cat/indices?v` bytes vs `du -sh` on the Loki and Tempo volumes, same ingest |
| Memory | `docker stats` steady-state RSS for each stack |
| Query ergonomics | the same three questions in PromQL/LogQL/TraceQL vs ES\|QL; time yourself |
| Correlation UX | trace → logs and log → trace, click count in each UI |
| Ad-hoc search | "find every occurrence of this stack trace in the last 24h" — Loki without a matching label vs Elasticsearch |
| Alerting | build one identical alert in both |
| Cost model | index-everything vs index-labels-only, and what that means at 100× the volume |

Expect the honest answer to be: Elastic wins ad-hoc search decisively, Grafana
wins metrics ergonomics and cost decisively, correlation is roughly a tie. Write
down the one that surprised you.

**Gotchas.**

- `xpack.security.enabled=false` and `discovery.type=single-node` for local, and
  say clearly in the explainer that both are unacceptable in production. Phase 7
  turns security back on.
- Pin the heap: `ES_JAVA_OPTS=-Xms1g -Xmx1g`. Elasticsearch will otherwise size
  itself to the host and evict everything else.
- `vm.max_map_count` must be at least 262144. On Docker Desktop and OrbStack it
  is set inside the VM, not on macOS.
- Kibana must match the Elasticsearch major version exactly.
- Elasticsearch is slow to become healthy. Give Kibana a `depends_on` with
  `condition: service_healthy` or it will crash-loop on first boot.
- The `elasticsearch` exporter's mapping modes have moved between Collector
  releases. Verify the mode name against the exporter README for the exact
  pinned version rather than trusting an older example.
- Elastic infers dependencies in the service map from **client** spans. If the
  Mongo driver instrumentation is missing, MongoDB simply does not appear — no
  error anywhere.

---

## Phase 5 — Kubernetes and the OpenTelemetry Operator

**Objective.** Move the application and RabbitMQ onto Kubernetes, and learn the
Operator-driven auto-instrumentation that the target environment runs. Infra
stays out deliberately — MongoDB and MinIO are not deployed here. Phase 6 puts
them outside the cluster, which is where they actually live.

This mirrors the target environment: Spring Boot on k8s, instrumented by
annotation, RabbitMQ as a Helm chart in-cluster, telemetry to Elastic.

**Cluster.**

```sh
k3d cluster create obs \
  --agents 2 \
  --port "8080:80@loadbalancer" \
  --k3s-arg "--disable=metrics-server@server:0"
```

Import the locally-built demo app image with `k3d image import` — there is no
registry.

**Workloads.** The demo app (`order-api`, `order-worker`) and RabbitMQ via its
Helm chart in a genuine multi-node cluster with quorum queues. Elasticsearch and
Kibana stay on Compose, reachable from the cluster — a real cluster does not run
its own backend either, and keeping them out saves the RAM.

**Concepts.**

- **Agent vs gateway.** A DaemonSet Collector on every node collects node-local
  data (pod logs, kubelet stats, host metrics) and forwards OTLP to a Deployment
  Collector that does cluster-wide work (enrichment, tail sampling, export).
  Draw this; it is the single most important diagram of the phase, and Phase 6
  extends it past the cluster edge.
- The **OpenTelemetry Operator** (`OpenTelemetryCollector` and `Instrumentation`
  CRDs, auto-instrumentation by pod annotation) versus the plain Helm chart. The
  Operator needs cert-manager. Show the Operator, but be explicit that the Helm
  chart alone is a legitimate choice.
- `k8sattributes` processor — how pod IP to pod metadata lookup works, the RBAC
  it needs, and why every signal should carry `k8s.namespace.name`,
  `k8s.pod.name`, `k8s.deployment.name`
- `filelog` on `/var/log/pods/*/*/*.log` with the `container` parser
- `kubeletstats` receiver for pod and container resource metrics
- The Target Allocator, and how Prometheus `ServiceMonitor` CRDs get discovered
  and sharded across Collector replicas

### Re-instrument the demo app by injection, not by image

Phase 2 baked `-javaagent:` into the container's entrypoint. **Keep that image
as-is** and add a second deployment that relies on injection, so both paths run
side by side and can be compared. The injected deployment must have the flag
removed — the double-agent failure below is the lesson.

The mechanism is a **mutating admission webhook**. On pod creation the Operator
rewrites the pod spec before the scheduler ever sees it:

```
Deployment applied
      │
      ▼  API server calls the OTel mutating webhook
Operator mutates the pod spec
      ├── adds an initContainer holding opentelemetry-javaagent.jar
      ├── adds an emptyDir volume shared by initContainer and app container
      ├── sets JAVA_TOOL_OPTIONS=-javaagent:/otel-auto-instrumentation/javaagent.jar
      └── sets OTEL_* env vars from the Instrumentation resource
      │
      ▼
App container starts — the JVM reads JAVA_TOOL_OPTIONS and attaches the agent
```

The initContainer's only job is to copy the agent jar onto the shared volume.
Nothing in the application image changes.

Two objects are involved. An `Instrumentation` CR holds the defaults —
agent image, exporter endpoint, propagators, sampler:

```yaml
apiVersion: opentelemetry.io/v1alpha1
kind: Instrumentation
metadata:
  name: java-agent
  namespace: demo
spec:
  exporter:
    endpoint: http://otel-agent-collector.observability.svc.cluster.local:4318
  propagators: [tracecontext, baggage]
  sampler:
    type: parentbased_always_on
  java:
    image: ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:<pinned>
    env:
      - name: OTEL_LOGS_EXPORTER
        value: otlp
```

And an annotation on the pod template opts a workload in:

```yaml
spec:
  template:
    metadata:
      annotations:
        instrumentation.opentelemetry.io/inject-java: "java-agent"
        resource.opentelemetry.io/service.name: "order-api"
```

The annotation value is not a boolean in disguise. `"true"` means *an
`Instrumentation` named `default` in this namespace*; a bare name means that CR
in this namespace; `namespace/name` reaches across namespaces; `"false"` opts a
single pod out. The same annotation works on a `Namespace` object, which
instruments every pod in it — that is how this is used at scale, and it is
worth doing once to see it.

Point the exporter at the **DaemonSet agent** Collector, not the gateway — the
injected endpoint is the one place the two-tier topology becomes concrete for
the application.

### The three instrumentation paths, compared

You now have the material to fill this in from experience rather than from docs.
This table is the thing your team will actually ask you about.

| Path | Where the decision lives | Cost | Fails when |
| --- | --- | --- | --- |
| `-javaagent:` in the image (Phase 2) | Dockerfile / entrypoint — the app team | every service rebuilds to change agent version | you need to opt one pod out, or roll the agent fleet-wide |
| Operator injection (this phase) | `Instrumentation` CR + annotation — the platform team | needs Operator, cert-manager, webhook availability | pods are not recreated; GraalVM native images; webhook is down |
| `opentelemetry-spring-boot-starter` | build dependency — the app team | a compile-time dependency and real code | you wanted zero app change |

Run the first two simultaneously in this phase and compare the resulting spans:
they should be indistinguishable in Elastic APM apart from `service.name`. If
they are not, find out why — that difference is agent version drift, and it is
the argument for injection in one sentence.

**Gotchas.**

- The webhook only fires at pod *creation*. Applying the `Instrumentation` CR
  changes nothing until pods are recreated. `kubectl rollout restart`.
- The annotation belongs on `spec.template.metadata.annotations`, not on the
  Deployment's own metadata. Putting it in the wrong place silently does
  nothing — no error, no injection. Diagnose it with
  `kubectl get pod -o yaml` and look for the initContainer.
- If the image's entrypoint already sets `JAVA_TOOL_OPTIONS`, the injected
  value is appended to it; two `-javaagent` flags for the same agent will fail
  at startup. This is exactly what happens if you annotate the Phase 2 image
  without removing its flag — do it once on purpose and read the JVM error.
- `service.name` resolution order matters: `OTEL_SERVICE_NAME` in the container
  beats the `resource.opentelemetry.io/service.name` annotation, which beats
  the Operator's fallback of `<deployment name>`. Getting `order-worker` and
  `order-api` to stay distinct is the test.
- **Not usable with GraalVM native images.** A native executable is not a JVM;
  it ignores `JAVA_TOOL_OPTIONS` and cannot load a `-javaagent`. That path needs
  the `opentelemetry-spring-boot-starter` compiled in — the third row above, and
  the concrete reason it exists.
- If the webhook's certificate has expired or cert-manager is unhealthy, pod
  creation itself can fail cluster-wide depending on the webhook's
  `failurePolicy`. Know which policy the Operator installed.

**RBAC.** `k8sattributes` needs `get`/`list`/`watch` on `pods` and `namespaces`
cluster-wide; `kubeletstats` needs `nodes/stats` and `nodes/proxy`. Getting a
403 here and reading the Collector's own logs to find it is a worthwhile
exercise — leave it as a deliberate break.

**Verification.** Traces for `order-api` and `order-worker` appear in Elastic
APM with the same shape as Phase 2, from an image containing no OpenTelemetry
code. `kubectl get pod <api-pod> -o jsonpath='{.spec.initContainers[*].name}'`
shows `opentelemetry-auto-instrumentation-java`. Deleting the annotation and
restarting makes the traces stop. Every span, metric and log carries the correct
`k8s.pod.name` and `k8s.namespace.name`. Kibana resolves through Traefik with no
port-forward.

---

## Phase 6 — The hybrid boundary: infrastructure outside the cluster

**Objective.** The phase that mirrors the real environment most closely, and the
one with no good blog post behind it. MongoDB and MinIO live **outside** the
Kubernetes cluster. Everything the last phase taught about `k8sattributes`,
pod-log tailing and Kubernetes service discovery is unavailable for half the
estate. Design the pipeline that spans the boundary anyway, and keep telemetry
from both sides correlatable in one Kibana view.

**Local representation.** Run MongoDB (replica set) and MinIO (4-drive erasure
set) as plain Docker containers on the host, **outside the k3d network** — not
in the cluster and not on the cluster's Docker network. The cluster reaches them
via `host.k3d.internal`. That reproduces the network and identity boundary at
near-zero extra RAM. It does not reproduce OS-level concerns (a Collector as a
systemd unit, host patching, real host metrics); note that gap explicitly in the
explainer, because it is a gap your team will have to close for real.

**Concepts.**

- The two designs, and why the choice is usually made by the firewall rather
  than by engineering
- Identity without Kubernetes: `resourcedetection/system` gives `host.name`,
  `os.type`, `host.arch`. There is no `k8s.pod.name` and never will be. What do
  you join on instead?
- Keeping `service.name`, `service.namespace` and `deployment.environment`
  consistent across two worlds so that Elastic correlates them at all
- Static targets vs dynamic discovery — `file_sd_configs` as the middle ground
- Credential ownership across an administrative boundary
- Securing the OTLP hop when it leaves the cluster network: TLS, mTLS, auth
  headers, and the `oauth2client` / `bearertokenauth` extensions
- The Collector as a thing you now operate in two places

### Design A — pull: in-cluster Collector scrapes the VMs

```
┌─ k8s cluster ──────────────────┐          ┌─ VM: mongo ──────────┐
│  agent DaemonSet ─┐            │          │ mongod               │
│                   ├─→ gateway ─┼──scrape──┤ mongodb_exporter     │
│  app pods ────────┘      │     │          └──────────────────────┘
│                          │     │          ┌─ VM: minio ──────────┐
└──────────────────────────┼─────┼──scrape──┤ /minio/v2/metrics/*  │
                           ▼                └──────────────────────┘
                     Elasticsearch
```

The gateway Collector holds a `prometheus` receiver with `static_configs` (or
`file_sd_configs`) pointing at the VM endpoints, plus the `mongodb` receiver.

| | |
| --- | --- |
| Good | one place to configure; nothing to install or patch on the VMs; credentials live in cluster Secrets |
| Bad | cluster → VM ingress must be open on every metrics port; no logs, because nothing is tailing the VM's files; scrape failures look like VM outages; the target list is hand-maintained |

### Design B — push: a Collector on each VM

```
┌─ VM: mongo ──────────────┐
│ mongod                   │
│ otelcol (systemd)        │──OTLP──┐
│  ├ mongodb receiver      │        │   ┌─ k8s cluster ─────────┐
│  ├ filelog → mongod.log  │        ├──→│ gateway Collector ────┼──→ Elasticsearch
│  └ hostmetrics           │        │   └───────────────────────┘
└──────────────────────────┘        │
┌─ VM: minio ──────────────┐        │
│ otelcol → prometheus,    │──OTLP──┘
│   filelog, hostmetrics   │
└──────────────────────────┘
```

| | |
| --- | --- |
| Good | logs and host metrics become possible; one outbound port only; credentials stay local to the machine that owns them; local buffering survives a cluster outage |
| Bad | a Collector fleet to install, configure, patch and monitor on machines you may not own; config drift; the VM team now has an observability dependency |

**Build both.** Then write down which one the work environment should use and
why, in one paragraph, naming the constraint that decides it.

### Correlation across the boundary — the actual exercise

Getting bytes into Elastic from both sides is the easy half. The hard half is
that a span from a pod and a log line from a VM must be findable as facts about
one incident.

- Same `deployment.environment` on both sides, set once by an `attributes`
  processor at the gateway rather than trusted from each source
- `service.name` for infra: is Mongo a *service* (`mongodb`) or an *attribute
  of the host*? Pick one convention, apply it to all three systems, and write
  it down — this is the first entry in the attribute policy that Phase 7 turns
  into a team document
- MongoDB emits no spans. The join between an `order-api` span and a `mongod`
  slow-query log is **time plus host plus database name**, not `trace.id`.
  Build that query in Kibana and see how weak the join is; that weakness is the
  argument for keeping client-side Mongo spans rich.
- Where does the timestamp come from on each path, and are the clocks the same?

**Verification.** A single Kibana session in which you: open a slow
`order-api` transaction in APM; identify the MongoDB call inside it; jump to
`mongod` logs from the right host in the same time window; and confirm the
`host.name` there matches the one on the metrics that showed the latency. All
of it from telemetry that crossed an administrative boundary.

**Breaks.**

| Do this | Predict, then check |
| --- | --- |
| Block the scrape port (Design A) | what the Collector logs, what appears in Kibana, and whether it looks like a Mongo outage |
| Kill the VM Collector (Design B) | what is lost, for how long, and whether anything alerts |
| Skew a VM clock by 10 minutes | what correlation looks like when time is the only join key |
| Give the VM telemetry a different `deployment.environment` | how it silently splits every dashboard |
| Restart Elasticsearch with Design B running | whether the VM Collector's `sending_queue` and `file_storage` actually saved the data |

**Gotchas.**

- `k8sattributes` cannot enrich telemetry that did not come from a pod. Applied
  indiscriminately it either no-ops or, worse, attaches the *gateway pod's* own
  identity to VM data. Scope it to the right pipeline.
- `host.k3d.internal` resolves inside the cluster; `localhost` in a Collector
  config inside a pod means the pod. This is the most common first failure.
- MinIO metrics return 401 unless `MINIO_PROMETHEUS_AUTH_TYPE=public` or a
  bearer token from `mc admin prometheus generate` is in the scrape config.
  Across a boundary, "public" is not an option — do the token properly here.
- The MongoDB monitoring user needs `clusterMonitor`. Whoever owns the VM has
  to create it, which is a conversation, not a config change.
- OTLP over a network you do not control needs TLS. `tls.insecure: true` was
  fine for Compose and is not fine here — this is the phase to turn it off.
- A push Collector with no `file_storage`-backed queue loses everything during a
  cluster outage, which is precisely when you want the data.

---

## Phase 7 — Production concerns and team practices

**Objective.** Everything that separates a demo from something you would run —
and then the artefacts that let a team run it without you.

**Sampling.**

- Head sampling: `OTEL_TRACES_SAMPLER=parentbased_traceidratio` — cheap, decided
  at the first span, and therefore blind to whether the trace turned out to be
  interesting. With Operator injection this is set in the `Instrumentation` CR,
  which means the platform team changes it for everyone at once.
- Tail sampling: the `tailsampling` processor with `latency`, `status_code`,
  `probabilistic` and `rate_limiting` policies composed together — keeps every
  error and every slow trace, drops most of the boring ones.
- The consequence that catches people out: tail sampling requires **all spans of
  a trace to reach the same Collector instance**. That forces a gateway tier and
  a `loadbalancing` exporter with `routing_key: traceID` in front of it. Draw
  this; it is why Phase 5's two-tier topology exists.
- The Elastic-specific fork: sampling can be done in the Collector *or* by APM
  Server. Doing both is a common and expensive mistake. Decide where it lives
  and say so in the team doc.

**Cardinality and cost — recomputed for Elastic.** The Prometheus series
arithmetic from Phase 0 does not transfer. In an index-everything store the cost
drivers are different:

- **Field mappings.** Every new attribute *key* is a new mapped field. Unbounded
  key namespaces (`attributes.user.<id>`) cause mapping explosion, which is a
  cluster-stability problem, not just a bill. Check the mapping field count
  against `index.mapping.total_fields.limit`.
- **Document count and size.** Every span and log line is a document. Sampling
  and log-level policy are the levers.
- **ILM.** Hot/warm/cold/delete phases per data stream, and the honest answer to
  "how long do we actually need traces for".
- Tools: `filter` and `transform` (OTTL) to drop and redact · `metricstransform`
  for renaming and aggregation · `deltatocumulative` where a backend needs it.
- Deliverable: a written policy for what may and may not become an attribute.

**Reliability of the pipeline itself.** `sending_queue` backed by the
`file_storage` extension so a backend restart does not lose data ·
`retry_on_failure` tuning · `memory_limiter` sizing · what actually happens when
the queue fills. Do this on both tiers *and* on the Phase 6 VM Collectors, which
are the ones with no neighbour to fail over to.

**Observing the Collector.** It emits its own metrics. Scrape them and alert on
them:

```
otelcol_receiver_accepted_spans / otelcol_receiver_refused_spans
otelcol_exporter_sent_spans     / otelcol_exporter_send_failed_spans
otelcol_processor_dropped_metric_points
otelcol_exporter_queue_size     / otelcol_exporter_queue_capacity
```

An unmonitored telemetry pipeline that silently drops data is worse than no
pipeline, because it produces confident, wrong dashboards. With Operator-managed
Collectors, also watch the webhook: a pod that starts *without* injection
produces no error and no telemetry.

**Security, turned back on.** Everything Phase 4 disabled for local
convenience: `xpack.security.enabled`, TLS on Elasticsearch, an API key per
Collector with least-privilege index permissions rather than a superuser, TLS
and auth on every OTLP hop, and secrets out of Collector YAML via env expansion
and Kubernetes Secrets.

**Alerting and SLOs.** Define an SLO for the demo app and implement it in
Kibana — the SLO feature plus a burn-rate rule. Alert on symptoms (latency,
error rate, queue depth) rather than causes (CPU). Build one identical alert in
Grafana for comparison, then delete it.

**Also cover.** Semantic-convention drift and schema URLs · what to do when the
Collector is the bottleneck · agent version rollout strategy across an injected
fleet.

### Team-facing deliverables

This is the point of the whole project. Three documents, written for colleagues
who will not read this curriculum.

1. **`docs/onboarding-a-service.md`** — how to add a Spring Boot service to
   observability in this environment. The annotation, the `service.name`
   convention, what you get for free, what needs code (custom spans, metrics,
   MDC), how to verify it worked in Kibana within five minutes, and how to tell
   whether injection actually happened.
2. **`docs/attribute-policy.md`** — the naming and cardinality rules. Which
   semconv attributes are mandatory (`service.name`, `service.namespace`,
   `deployment.environment`), what may never be an attribute key or a metric
   label, how to name a custom metric, and who to ask. One page, with examples
   of both the right and the wrong thing.
3. **`docs/review-checklist.md`** — what a reviewer looks for in a PR that
   touches telemetry. Ten lines, checkbox form.

**Verification for the phase.** Hand the onboarding doc to someone who has not
done this, and watch them instrument a new service without asking you a
question. Anything they ask is a bug in the doc.

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
13. Mixing `mapping.mode: otel` and `ecs` in one deployment. Two field names for
    one concept; every dashboard then works for half the data, with no error.
14. Leaving Elasticsearch on its local defaults with no ILM policy. Nothing ages
    out, and the first symptom is a full disk.
15. Not calling `sdk.shutdown()` on `SIGTERM`. The final batch is lost on every
    deploy.
16. Applying an `Instrumentation` CR and expecting existing pods to change. The
    webhook fires only at pod creation. `kubectl rollout restart`.
17. Putting `instrumentation.opentelemetry.io/inject-java` on the Deployment's
    own metadata instead of `spec.template.metadata.annotations`. No error, no
    injection, no telemetry.
18. Annotating a pod whose image already sets `-javaagent` in
    `JAVA_TOOL_OPTIONS`. Two agents, and the JVM refuses to start.
19. Expecting Operator injection to work on a GraalVM native image. It cannot;
    that path needs `opentelemetry-spring-boot-starter` compiled in.
20. Running `k8sattributes` over telemetry that did not come from a pod. It
    either no-ops or stamps the gateway's own pod identity onto VM data.
21. Using `localhost` in a Collector config inside a pod to mean the node or the
    host. Use `host.k3d.internal` (or the node IP) for off-cluster targets.
22. Different `deployment.environment` values either side of the cluster
    boundary. Every dashboard silently splits in two.
23. A VM-side Collector with no `file_storage`-backed `sending_queue`. It loses
    exactly the data you wanted during a backend outage.
24. Tail sampling behind a plain round-robin load balancer. Spans of one trace
    land on different Collectors and traces come out incomplete.
25. Sampling in both the Collector and APM Server. The rates multiply, and the
    trace volume you get is not the one you configured.
26. Semantic conventions that moved — `http.method` became
    `http.request.method`. An empty dashboard panel is usually a renamed
    attribute.
