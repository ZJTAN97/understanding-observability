# Understanding Observability

Replicating one specific production estate locally, in order to learn
OpenTelemetry and Elastic observability well enough to operate it — and to teach
it to a team.

The estate: Spring Boot applications on Kubernetes, auto-instrumented by the
OpenTelemetry Operator's mutating admission webhook. RabbitMQ in-cluster by Helm
chart. MongoDB and MinIO on VMs of their own, outside the cluster. A React
frontend. Elasticsearch, Kibana and APM Server at **8.14.2**, deployed by ECK
inside the same cluster.

## Stack decisions

| | |
|---|---|
| Pipeline | OpenTelemetry Collector, `contrib` distribution |
| Backend | Elasticsearch + Kibana + APM Server, pinned to 8.14.2 |
| Route into Elastic | assumed OTLP → APM Server; proven in W1 |
| Demo app | Spring Boot 3.5.16 / Java 21, instrumented by the OTel Java agent — the only source of spans in the estate |
| Agent delivery | baked `-javaagent` in W0–W1 → Operator injection from W2 |
| Local runtime | Docker Compose → k3d from W2 |
| Off-cluster infra | Docker outside the k3d network, then one real Hyper-V VM |

## Phases

| | Goal | Effort |
|---|---|---|
| **W0** | Compose foundation: all three signals from the app into Elastic | ½ day |
| **W1** | The route into Elastic; data streams, mapping mode, ILM, ES\|QL | 1 day |
| **W2** | k3d and Operator injection; agent/gateway topology, `k8sattributes` | 2 days |
| **W3** | ECK: the backend moves into the cluster, with TLS and shared fate | 1 day |
| **W4** | RabbitMQ in-cluster; trace context across the queue | 1 day |
| **W5** | Off-cluster MongoDB and MinIO; correlation across the boundary | 2 days |
| **W6** | React frontend; browser instrumentation end to end | 1 day |
| **W7** | Hardening, sampling, cost, and the team-facing documents | 2 days |

**Full build plan: [docs/CURRICULUM.md](docs/CURRICULUM.md)** — every phase in
detail, with deliverables, verification steps, deliberate breaks and the
accumulated gotcha list. That file is self-contained; hand it to another engineer
and they can build any phase from it.

## Layout

```
app/                the demo app, unchanged across all phases
docs/CURRICULUM.md  the build plan
docs/w<N>-*.md      one explainer per phase, written as the phase is built
work/w<N>/          everything runnable for that phase
```

The explainers are the actual output of this project. The running stacks are
scaffolding.
