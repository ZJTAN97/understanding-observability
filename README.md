# Understanding Observability

Learning OpenTelemetry properly, then using it to observe a MongoDB replica set,
an HA RabbitMQ cluster and MinIO — first on Docker Compose, then on k3d with
half the estate deliberately left outside the cluster, all landing in Elastic.

Phases 5–7 mirror a specific real environment: Spring Boot on Kubernetes
auto-instrumented by the OpenTelemetry Operator, RabbitMQ as a Helm chart
in-cluster, MongoDB and MinIO on machines of their own, Elasticsearch and Kibana
as the backend.

## Stack decisions

| | |
|---|---|
| Pipeline | OpenTelemetry Collector (`contrib` distribution) |
| Teaching backend | Grafana + Prometheus / Loki / Tempo — phases 1–3, then removed |
| Destination backend | Elasticsearch + Kibana — phases 4–7, same pipeline |
| Demo app | Spring Boot 3.5 / Java 21, instrumented by the OTel Java agent (the only source of traces) |
| Java agent delivery | baked `-javaagent` in phases 2–4 → Operator injection from phase 5 |
| Local runtime | Docker Compose → k3d from phase 5 |
| Off-cluster infra | plain Docker outside the k3d network, from phase 6 |

**Full build spec: [docs/CURRICULUM.md](docs/CURRICULUM.md)** — every phase in
detail, with deliverables, verification steps, and the accumulated gotcha list.
That file is self-contained; hand it to another agent and they can build any
phase without this README.

## Phases

| | Goal | Explainer | Code |
|---|---|---|---|
| **0** | Signals, OTLP, semconv, cardinality, Collector anatomy | [docs/phase-0-signals-and-collector.html](docs/phase-0-signals-and-collector.html) | [`phase-0/`](phase-0) |
| **1** | Swap `debug` for a real backend: Grafana LGTM | [docs/phase-1-grafana-lgtm.html](docs/phase-1-grafana-lgtm.html) | [`phase-1/`](phase-1) |
| **2** | Instrument the Spring Boot app; propagate trace context through RabbitMQ | [docs/phase-2-instrument-the-app.html](docs/phase-2-instrument-the-app.html) | [`phase-2/`](phase-2), [`app/`](app) |
| **3** | Real topologies, then observe them: scrape MongoDB / RabbitMQ / MinIO, tail their logs, drop 63% of the series at the pipe | [docs/phase-3-infrastructure-signals.html](docs/phase-3-infrastructure-signals.html) | [`phase-3/`](phase-3) |
| 4 | Repoint the pipeline at Elastic and stay there: mapping modes, data streams, ILM, APM, ES\|QL — with the Grafana comparison as an appendix | | |
| 5 | Kubernetes on k3d: the OTel Operator, `inject-java` auto-instrumentation, agent vs gateway, RabbitMQ by Helm chart | | |
| 6 | The hybrid boundary: MongoDB and MinIO outside the cluster — pull vs push Collectors, identity without `k8s.*`, correlating across the edge | | |
| 7 | Sampling, cost in an index-everything store, pipeline reliability, security back on, SLOs — and the three docs a team can follow | | |

## Running a phase

```sh
cd phase-0
docker compose up -d
./send-telemetry.sh
docker compose logs -f
docker compose down
```

Open the phase's HTML explainer in a browser first — the code is meant to be read
alongside it, not run blind.

## Notes

- Everything is pinned. Collector is `otel/opentelemetry-collector-contrib:0.149.0`;
  Tempo `3.0.3`, Loki `3.7.7`, Prometheus `v3.14.0`, Grafana `13.2.1`, MongoDB
  `8.2.1`, RabbitMQ `4.2.0-management`, MinIO `RELEASE.2025-09-07T16-13-09Z`,
  Temurin `21.0.12_8`, OTel Java agent `2.31.1`, Percona `mongodb_exporter:0.53.0`.
- 16 GB of RAM does not fit Grafana's stack and Elastic's stack at once. Tear one
  down before bringing the other up. The one exception is the deliberate fan-out
  exercise in the phase 4 appendix, and then with one infra system only.
- From phase 5 the cluster does not host its own backend — Elasticsearch and
  Kibana stay on Compose and the cluster reaches them, which is both cheaper and
  closer to how this actually runs.
