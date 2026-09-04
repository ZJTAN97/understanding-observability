# Understanding Observability

Learning OpenTelemetry properly, then using it to observe a MongoDB replica set,
an HA RabbitMQ cluster and MinIO — first on Docker Compose, finally on k3d.

## Stack decisions

| | |
|---|---|
| Pipeline | OpenTelemetry Collector (`contrib` distribution) |
| Backend A | Grafana + Prometheus / Loki / Tempo — phases 1–3 |
| Backend B | Elasticsearch + Kibana — phase 4, same pipeline |
| Demo app | Spring Boot 3.5 / Java 21, instrumented by the OTel Java agent (the only source of traces) |
| Local runtime | Docker Compose → k3d in phase 5 |

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
| 3 | Scrape MongoDB / RabbitMQ / MinIO; tail their logs; correlate | | |
| 4 | Repoint the same pipeline at Elasticsearch + Kibana; compare | | |
| 5 | Port to Kubernetes on k3d: OTEL Operator, agent vs gateway | | |
| 6 | Sampling, cardinality control, alerting, SLOs | | |

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
  Temurin `21.0.12_8`, OTel Java agent `2.31.1`.
- 16 GB of RAM does not fit Grafana's stack and Elastic's stack at once. Tear one
  down before bringing the other up.
