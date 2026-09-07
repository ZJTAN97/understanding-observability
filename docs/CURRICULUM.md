# Observability with OpenTelemetry and Elastic — work-specific build plan

Replicating a specific production estate locally, in order to understand it well
enough to operate it and to teach it to a team.

This document is self-contained. Hand it to another engineer or another agent
and they can build any phase without further context.

---

## Contents

- [The premise](#the-premise)
- [The target environment](#the-target-environment)
- [What is already decided](#what-is-already-decided)
- [The version constraint that shapes everything](#the-version-constraint-that-shapes-everything)
- [Open questions](#open-questions)
- [Stack decisions and why](#stack-decisions-and-why)
- [Hardware budget](#hardware-budget)
- [Conventions](#conventions)
- [Definition of done](#definition-of-done)
- [W0 — Compose foundation](#w0--compose-foundation)
- [W1 — The route into Elastic](#w1--the-route-into-elastic)
- [W2 — Kubernetes and Operator injection](#w2--kubernetes-and-operator-injection)
- [W3 — ECK: the backend moves into the cluster](#w3--eck-the-backend-moves-into-the-cluster)
- [W4 — RabbitMQ in-cluster](#w4--rabbitmq-in-cluster)
- [W5 — The off-cluster estate: MongoDB and MinIO](#w5--the-off-cluster-estate-mongodb-and-minio)
- [W6 — The React frontend](#w6--the-react-frontend)
- [W7 — Hardening and team practices](#w7--hardening-and-team-practices)
- [Reference: the gotcha list](#reference-the-gotcha-list)
- [What was deliberately dropped](#what-was-deliberately-dropped)

---

## The premise

The goal is not to learn OpenTelemetry in the abstract. It is to be able to
answer, for one particular production estate, three questions: where does a
given signal come from, what happens to it on the way to Elastic, and what
breaks it. Everything here is chosen because it appears in that estate.

The learner is new to OpenTelemetry and to Elastic observability. The strategy
is therefore to reproduce the estate locally, one boundary at a time, and to
break each piece deliberately once so that its failure signature is familiar
before it appears in production.

Two principles that the phase ordering follows:

**One new axis at a time.** A Collector config problem and a Kubernetes problem
look identical from Kibana — both are "no data". So the Collector is learned on
Docker Compose, where there is nothing else to blame, before Kubernetes is
introduced. Likewise the backend stays on Compose while Operator injection is
being learned, and only moves into the cluster once injection is known to work.

**Predict, then break.** Every phase ends with a table of things to do on
purpose. The value is in writing down the expected symptom first and then
checking. A failure mode you have seen once is diagnosable; one you have only
read about is not.

---

## The target environment

| Component | How it runs in production |
|---|---|
| Spring Boot applications | Kubernetes pods, auto-instrumented by the OpenTelemetry Operator's mutating admission webhook — no OTel code or agent in the image |
| OpenTelemetry Collector | in-cluster, exporting metrics, logs and traces to the Elastic stack |
| RabbitMQ | in-cluster, deployed by Helm chart |
| MongoDB | outside the cluster, on its own VM — the applications' only backing store |
| MinIO | outside the cluster, on its own VM |
| Elastic stack | Elasticsearch, Kibana, APM and Beats — **8.14.2** — deployed by ECK |
| React frontend | browser client, currently not instrumented |

Two facts about this estate drive most of the design work:

1. **Half the estate is not in Kubernetes.** MongoDB and MinIO have no
   `k8s.pod.name`, no pod logs to tail, and no Kubernetes service discovery.
   Everything Kubernetes-native learned for the applications is unavailable for
   them, and telemetry from both sides still has to be correlatable in one
   Kibana view. This is the hardest part of the estate and the part with the
   least written about it.

2. **The observability backend lives in the cluster it observes.** ECK deploying
   Elasticsearch, Kibana and APM Server inside Kubernetes means the monitoring
   system shares fate with the workloads. A cluster in trouble is exactly when
   the backend is also in trouble, and exactly when the telemetry matters most.
   Mitigating that — resource guarantees, and Collector queues that buffer to
   disk so a backend outage delays data rather than destroying it — is
   load-bearing work here, not a refinement.

---

## What is already decided

Established by asking, rather than assumed:

| Question | Answer | Consequence |
|---|---|---|
| Is the ECK-deployed Elasticsearch the observability backend, or an application datastore? | **The observability backend.** The applications do not use Elasticsearch at all; MongoDB is the only backing store. | No application-side Elasticsearch client spans exist or need to. The backend is in-cluster, so shared fate applies. |
| Can software be installed on the MongoDB and MinIO VMs? | **Yes — those VMs are under our control.** | Design B (a Collector running on each machine) is available, which is the only design that yields logs and host metrics from those hosts. Build it as the primary; build Design A once for comparison. Locally the Collector runs as a container rather than a systemd unit — see W5 for what that costs. |
| Is the React frontend instrumented? | **No — greenfield.** | W6 builds browser instrumentation from nothing, including the endpoint exposure and CORS work, which is the actual difficulty. |
| Elastic version | **8.14.2** | See below. This is the single most consequential fact in this document. |

---

## The version constraint that shapes everything

There are three routes OpenTelemetry data can take into Elastic:

| Route | Mechanism | Who owns the mapping |
|---|---|---|
| **A. Collector → `elasticsearch` exporter** | the contrib Collector writes directly to `:9200` over the bulk API | you do, via `mapping.mode` |
| **B. Collector → OTLP → APM Server** | export OTLP to APM Server's OTLP intake; Elastic maps server-side into `traces-apm*` data streams | Elastic does |
| **C. EDOT** | Elastic's own Collector and SDK distributions, preconfigured | Elastic does, with curated defaults |

**On 8.14.2, route A does not light up the APM app.** Kibana's
OpenTelemetry-native support — reading the OTel-native data streams that the
`elasticsearch` exporter produces under `mapping.mode: otel` — arrived
meaningfully later in the 8.x line (around 8.16 onward, maturing into 9.x). On
8.14.2 the APM app still expects the `traces-apm*` data streams that APM Server
writes. Point the Collector straight at `:9200` on this version and the data
lands, is searchable, and the APM app shows nothing: no services, no service map,
no transaction latency. Nothing errors. It is a genuinely nasty failure to
diagnose cold.

**Therefore the working assumption is route B**, and it fits everything else we
know. ECK manages APM Server as a first-class custom resource alongside
Elasticsearch and Kibana, and the production stack is described as including
"APM and Beats" — which maps one-to-one onto ECK's `Elasticsearch`, `Kibana`,
`ApmServer` and `Beat` resources.

This is inference from a version number, not verification against the cluster.
The feature thresholds moved across minor versions. **W1 exists to prove it** by
configuring both routes side by side and observing which one populates the APM
app. That experiment is worth more than the answer, because it teaches what the
APM app is actually reading.

The same constraint reaches the frontend: EDOT Browser targets recent 8.x and
9.x, so it is probably unavailable on 8.14.2, and W6 chooses between the classic
`@elastic/apm-rum` agent and the vendor-neutral OTel browser SDK instead. The
same experiment decides it.

---

## Open questions

To be resolved before the phase they affect:

1. **Is the production Elasticsearch itself ECK-deployed, or does ECK manage only
   part of the stack?** Elasticsearch on VMs with ECK running just Kibana and
   APM Server is a common arrangement. Blocks the final shape of W3: whether the
   whole backend moves into k3d or only part of it.
2. **Which route is production actually using?** Determined in W1 by reading the
   in-cluster Collector's ConfigMap `exporters` block, checking for an
   `ApmServer` resource or an apm-server pod, and reading the data stream names
   in Kibana — `traces-apm*` means APM Server, `traces-generic.otel-*` means the
   `elasticsearch` exporter.
3. **Exact Elastic minor-version feature support.** Verify OTLP intake behaviour
   and EDOT Browser compatibility against the 8.14 documentation rather than
   against newer examples, which will not match.
4. **ECK operator version compatible with 8.14.2.** Check the ECK compatibility
   matrix and pin it in W3. Do not assume the latest operator supports an older
   stack version.

---

## Stack decisions and why

| Choice | Decision | Rationale |
|---|---|---|
| Collector distribution | `otel/opentelemetry-collector-contrib` | Every receiver needed here (`prometheus`, `filelog`, `mongodb`, `rabbitmq`, `k8sattributes`, `kubeletstats`, `hostmetrics`) is contrib-only. The `core` image fails with `unknown type`. |
| Backend | Elasticsearch + Kibana + APM Server, pinned to **8.14.2** | Learning against the version actually run. Newer defaults and newer docs will mislead. |
| Route into Elastic | assumed **B** (OTLP → APM Server), proven in W1 | Forced by 8.14.2's APM app expectations. |
| Backend deployment | Compose in W0–W2, **ECK** from W3 | Keeps the backend boring while injection is being learned, then moves it in-cluster to match production and to confront shared fate. |
| Demo app | Spring Boot 3.5.16 / Java 21, existing `app/` | Already covers the whole path: Tomcat, Spring AMQP, the AMQP client, the Mongo driver, OkHttp, Logback. Unchanged from here on. |
| Java instrumentation delivery | baked `-javaagent` in W0–W1, **Operator injection** from W2 | Both survive side by side in W2 so the trade-off is written from experience. Injection is what production uses. |
| Local Kubernetes | **k3d** | Small footprint, ships Traefik and a LoadBalancer so hostnames work without port-forwarding. Diverges from upstream Kubernetes only in storage internals. |
| Off-cluster infra | **plain Docker containers outside the k3d network** | Reproduces the network boundary, the identity gap, log tailing and the whole of Design B at near-zero cost. A real VM would add the systemd Collector unit and genuine per-host metrics; that gap is recorded in W5 rather than papered over. A second VM alongside Docker Desktop's own WSL2 backend was judged not worth the setup effort or the 2 GB. |
| Container runtime | Docker Desktop on WSL2 (already installed) | Note that kernel settings such as `vm.max_map_count` are set inside the WSL VM, not on Windows. |

---

## Hardware budget

Host: **Windows 11, 32 GB RAM, 14 logical cores, Docker Desktop on WSL2.**
Roughly double the headroom of a typical 16 GB laptop, which is why ECK plus the
full estate is feasible at all.

| Component | Approx RSS |
|---|---|
| Elasticsearch (single node, pinned heap) | 1.5 GB |
| Kibana | 1.0 GB |
| APM Server | 0.3 GB |
| k3s control plane + 2 agents | 0.8 GB |
| Demo app (api + worker) | 0.4 GB |
| Collector fleet (DaemonSet ×3, gateway, 2 off-cluster) | 0.6 GB |
| RabbitMQ (3 nodes, quorum queues) | 1.5 GB |
| MongoDB replica set (3 nodes) | 1.5 GB |
| MinIO (4 drives) | 2.0 GB |

Rules that follow:

- Set `mem_limit` on every Compose service and `resources.limits` on every pod.
  An unbounded Elasticsearch sizes itself to the host and starves everything else.
- Pin the Elasticsearch heap explicitly: `ES_JAVA_OPTS=-Xms1g -Xmx1g`.
- `vm.max_map_count` must be at least 262144, set inside the WSL2 VM.
- `k3d cluster create --agents 2` is sufficient. Three plus all workloads will
  not fit.
- Do not run the W0–W2 Compose backend and the W3 ECK backend simultaneously.
- W5's off-cluster containers replace anything equivalent running earlier.

---

## Conventions

### Repository layout

```
app/                      the demo app, unchanged across all phases
docs/CURRICULUM.md        this file
docs/w<N>-<slug>.md       one explainer per phase, written as the phase is built
work/w<N>/                everything runnable for that phase
  docker-compose.yaml
  otel-collector-config.yaml
  k8s/                    manifests, from W2 on
  loadgen.sh
```

Each phase's directory is self-contained and runnable on its own. Copy forward
and modify rather than sharing files between phases; a phase that cannot be run
in isolation cannot be debugged in isolation.

### Version pinning

Pin everything, explicitly, including the Collector. Two reasons that matter
here specifically: the `elasticsearch` exporter's mapping-mode names have moved
between Collector releases, and `releases/latest/download/` for the Java agent
means instrumentation silently changes version between two builds of one commit.

Record every pinned version in the phase's explainer.

### Collector config style

Comment every component with what it does and why it is there. Above all: a
component that is defined but not listed under `service.pipelines` is silently
inert. That is the first thing to check whenever data does not arrive, and it
costs everyone an afternoon exactly once.

### Explainers

One Markdown explainer per phase in `docs/`, written while building rather than
after. Each covers: the diagram, what each component does, the decisions taken
and rejected, what broke and what the symptom looked like, and what production
does differently from the local reproduction. The explainers are the actual
output of this project; the running stacks are scaffolding.

---

## Definition of done

A phase is done when all five hold:

1. The stack comes up from a cold `docker compose up` or `kubectl apply` with no
   manual intervention.
2. The verification steps in the phase pass, checked rather than assumed.
3. Every break in the phase's break table has been performed, its symptom
   observed, and the prediction recorded against the outcome.
4. The explainer is written, including the gotchas hit.
5. The gap between the local reproduction and production is stated explicitly.

---

## W0 — Compose foundation

**Objective.** All three signals from the demo app arriving in Elastic, on
Docker Compose, with nothing else in play. Half a day.

This phase exists because a Collector problem and a Kubernetes problem are
indistinguishable from Kibana. Learning to read a pipeline and diagnose "no
data" in the simplest possible topology is what makes every later phase
bisectable.

**Components.** The demo app (`order-api`, `order-worker`) with the agent baked
in, RabbitMQ, MongoDB and MinIO as single containers, one Collector, and
Elasticsearch + Kibana + APM Server pinned to 8.14.2. Security disabled locally,
which W7 turns back on.

**Concepts.**

- The three signals, what each is for, and why they are separate pipelines
- OTLP: gRPC on 4317, HTTP on 4318, and which one the agent defaults to
- Collector anatomy: receivers, processors, exporters, and the fact that
  `service.pipelines` is what actually wires them together
- Resource attributes versus signal attributes, and `service.name` as the
  primary key of everything downstream
- The `debug` exporter as the first diagnostic, before blaming the backend

**Build.** Start with the `debug` exporter only and confirm signals arrive at the
Collector at all. Only then add the Elastic exporter. Resisting the urge to wire
the whole thing up at once is the lesson.

**Verification.** A `POST /orders` produces a trace spanning `order-api` and
`order-worker`; the app's logs carry a populated `trace.id`; the custom counter
from `Telemetry.java` is visible. All three findable in Kibana.

**Breaks.**

| Do this | Predict, then check |
|---|---|
| Remove an exporter from `service.pipelines` but leave it defined | where the data goes and what the Collector says |
| Start the JVM without `-javaagent` | how many spans, and whether anything errors |
| Stop Elasticsearch, keep sending | when the Collector's queue fills and what it does then |
| Set the same `OTEL_SERVICE_NAME` for api and worker | what happens to the trace and to the service list |

**Gotchas.** Elasticsearch is slow to become healthy — give Kibana a
`depends_on` with `condition: service_healthy` or it crash-loops on first boot.
Kibana must match the Elasticsearch major version exactly. The app README
currently references a `phase-2/docker-compose.yaml` that no longer exists; fix
those references to `work/w0/` as part of this phase.

---

## W1 — The route into Elastic

**Objective.** Prove which route into Elastic works on 8.14.2, and learn where
telemetry physically lands once it gets there. One day.

**The central experiment.** Configure both routes simultaneously — one pipeline
via the `elasticsearch` exporter to `:9200`, one via OTLP to APM Server — and
observe which populates the APM app. Expect only route B to. Then find where the
route A data actually went, because it did land somewhere, and being able to find
it is the skill.

**Concepts.**

- The three routes, and which one a given cluster is using
- `mapping.mode: otel` versus `ecs`, and why the choice is close to one-way
- Data streams, index templates, component templates and ILM: where telemetry
  physically lives and what ages it out
- The APM app — services, transactions, dependencies, the service map — and which
  part of the OTLP payload each view is reading
- Log-to-trace correlation in Elastic as a field join on `trace.id`, not a
  datasource setting
- ES|QL, and how one query language over one store differs from three

**The mapping decision.** If route A were viable, this would be the most
consequential line in the exporter config. It still matters, because Beats in the
production stack means ECS-shaped data and probably ECS-based dashboards already
exist. Mixing `otel` and `ecs` modes in one deployment gives two field names for
one concept, and every dashboard then works for exactly half the data, with no
error anywhere. Decide deliberately and write down why.

**Storage and lifecycle.** Find the data streams with `GET _data_stream/*`, find
what shaped them with `GET _index_template/*` and the component templates it
composes, then count the mapped fields with `GET <index>/_mapping`. Field count
is the cost driver in an index-everything store, not row count — an unbounded
attribute key namespace is a mapping explosion. Attach an ILM policy with a short
hot phase and a delete phase and verify it applied; local defaults keep
everything forever, and the first symptom is a full disk.

**Verification.** A trace opened in APM, its logs reachable in one click, the
same event findable by free-text search, and `GET _cat/indices?v` showing the
data streams with non-zero doc counts. Three questions answered in ES|QL.

**Breaks.**

| Do this | Predict, then check |
|---|---|
| Switch one pipeline to `mapping.mode: ecs`, leave others on `otel` | which views break and what the field names become |
| Drop `service.name` in a `transform` processor | what APM shows, and where the data went instead |
| Send a metric with 50k distinct attribute values | mapping size and index size, not series count |
| Remove the ILM policy and backfill a day of data | disk, and how long before anything notices |

---

## W2 — Kubernetes and Operator injection

**Objective.** Move the applications onto Kubernetes and replace the baked-in
agent with Operator injection — the mechanism production actually uses. Two days.
The backend stays on Compose so that a broken injection cannot be mistaken for a
broken backend.

**Cluster.**

```sh
k3d cluster create obs --agents 2 --port "8080:80@loadbalancer"
```

The locally built app image needs `k3d image import`; there is no registry.

**Concepts.**

- **Agent versus gateway.** A DaemonSet Collector on every node collects
  node-local data (pod logs, kubelet stats, host metrics) and forwards OTLP to a
  Deployment Collector doing cluster-wide work (enrichment, sampling, export).
  Draw this. W5 extends it past the cluster edge and W7 explains why the gateway
  tier is mandatory rather than tidy.
- The OpenTelemetry Operator, its `OpenTelemetryCollector` and `Instrumentation`
  custom resources, and the fact that it needs cert-manager
- `k8sattributes` — how pod-IP-to-metadata lookup works, the RBAC it needs, and
  why every signal should carry `k8s.namespace.name`, `k8s.pod.name` and
  `k8s.deployment.name`
- `filelog` on `/var/log/pods/*/*/*.log` with the `container` parser
- `kubeletstats` for pod and container resource metrics

**The injection mechanism.** A mutating admission webhook rewrites the pod spec
before the scheduler sees it:

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

The initContainer's only job is copying the jar onto the shared volume. Nothing
in the application image changes.

An `Instrumentation` custom resource holds the defaults — agent image, exporter
endpoint, propagators, sampler — and an annotation on the pod template opts a
workload in:

```yaml
spec:
  template:
    metadata:
      annotations:
        instrumentation.opentelemetry.io/inject-java: "java-agent"
        resource.opentelemetry.io/service.name: "order-api"
```

That annotation value is not a boolean in disguise. `"true"` means *an
`Instrumentation` named `default` in this namespace*; a bare name means that
resource in this namespace; `namespace/name` reaches across namespaces; `"false"`
opts a single pod out. The same annotation on a `Namespace` object instruments
every pod in it, which is how this is used at scale — worth doing once to see.

Point the injected exporter endpoint at the **DaemonSet agent**, not the gateway.
That is the one place the two-tier topology becomes concrete for the application.

**Run both paths side by side.** Keep the W0 image with its baked `-javaagent`
and add a second deployment relying on injection, with the flag removed. The
spans should be indistinguishable in APM apart from `service.name`. If they are
not, the difference is agent version drift — which is the argument for injection
in one sentence.

| Path | Where the decision lives | Cost | Fails when |
|---|---|---|---|
| `-javaagent:` in the image | Dockerfile — the app team | every service rebuilds to change agent version | you need to opt one pod out, or roll the fleet |
| Operator injection | `Instrumentation` resource + annotation — the platform team | needs Operator, cert-manager, a healthy webhook | pods are not recreated; GraalVM native images; webhook down |
| `opentelemetry-spring-boot-starter` | build dependency — the app team | a compile-time dependency and real code | you wanted zero app change |

**RBAC.** `k8sattributes` needs `get`/`list`/`watch` on `pods` and `namespaces`
cluster-wide; `kubeletstats` needs `nodes/stats` and `nodes/proxy`. Hit the 403
on purpose and find it in the Collector's own logs.

**Verification.** `kubectl get pod <api-pod> -o jsonpath='{.spec.initContainers[*].name}'`
shows the auto-instrumentation initContainer. Traces for both services appear in
APM from an image containing no OpenTelemetry code. Deleting the annotation and
restarting makes them stop. Every span, metric and log carries the right
`k8s.pod.name` and `k8s.namespace.name`.

**Gotchas.**

- The webhook fires only at pod *creation*. Applying the `Instrumentation`
  resource changes nothing until pods are recreated: `kubectl rollout restart`.
- The annotation belongs on `spec.template.metadata.annotations`, not on the
  Deployment's own metadata. Wrong place means no error, no injection, no
  telemetry. Diagnose with `kubectl get pod -o yaml` and look for the
  initContainer.
- If the image's entrypoint already sets `JAVA_TOOL_OPTIONS`, the injected value
  is appended and two `-javaagent` flags for the same agent fail at startup.
  This is exactly what happens if you annotate the W0 image without removing its
  flag. Do it once on purpose and read the JVM error.
- `service.name` resolution order: `OTEL_SERVICE_NAME` in the container beats the
  `resource.opentelemetry.io/service.name` annotation, which beats the Operator's
  fallback of the deployment name. Keeping api and worker distinct is the test.
- Not usable with GraalVM native images — a native executable is not a JVM,
  ignores `JAVA_TOOL_OPTIONS` and cannot load a `-javaagent`. That path needs the
  Spring Boot starter compiled in, which is the concrete reason it exists.
- If cert-manager is unhealthy or the webhook certificate expires, pod creation
  can fail cluster-wide depending on the webhook's `failurePolicy`. Know which
  policy the Operator installed.

---

## W3 — ECK: the backend moves into the cluster

**Objective.** Deploy the Elastic stack with ECK inside the cluster, as
production does, and confront what that means. One day.

Doing a second operator immediately after the OpenTelemetry Operator is the
point: operators and custom resources stop being instructions to follow and
become a pattern to recognise.

**Concepts.**

- ECK's resources: `Elasticsearch`, `Kibana`, `ApmServer`, `Beat` — and how they
  map onto the production stack description
- Operator-managed TLS and generated credentials, and reading them from a
  Kubernetes Secret
- Making the Collector trust ECK's CA, which is the first non-optional security
  work in this project
- **Shared fate**: the backend degrading with the cluster it observes, and the
  three mitigations — resource guarantees and dedicated nodes, a Collector
  `sending_queue` backed by the `file_storage` extension, and alerting that does
  not depend on the thing being alerted about
- Self-monitoring: the Collector's own metrics, and Elasticsearch's cluster health

**Build.** Install the ECK operator at a version verified against the
compatibility matrix for 8.14.2. Create `Elasticsearch`, `Kibana` and `ApmServer`
resources. Repoint the gateway Collector at the in-cluster APM Server over TLS
using the operator-generated CA and credentials. Then decommission the Compose
backend.

**Verification.** Everything visible in W2 is still visible, now through an
in-cluster Kibana reached via Traefik with no port-forward, over TLS, with the
Collector authenticating from a Secret rather than running insecure.

**Breaks.**

| Do this | Predict, then check |
|---|---|
| Delete the Elasticsearch pod | what the Collector does, what is lost, and what you can still see while blind |
| Do the same with `file_storage` configured | whether the queue actually saved the data |
| Let the ECK CA secret rotate without updating the Collector | the error, and how long before you notice |
| Starve the Elasticsearch pod's memory limit | whether the symptom looks like a backend problem or an application problem |

**Gotchas.** `vm.max_map_count` is set inside the WSL2 VM, not on Windows. ECK
enables TLS and generates passwords by default, so nothing works until the
Collector is configured for both — that friction is the production posture
arrived at by default. Do not assume the newest ECK operator supports 8.14.2.

---

## W4 — RabbitMQ in-cluster

**Objective.** RabbitMQ by Helm chart in a genuine multi-node cluster, observed,
with trace context surviving the queue. One day.

**Concepts.**

- The `rabbitmq` receiver and the management plugin's Prometheus endpoint, and
  what each gives you that the other does not
- Which RabbitMQ metrics actually predict trouble — queue depth, consumer
  utilisation, unacked counts, memory alarms — as opposed to which are merely
  available
- Trace context propagation across an asynchronous boundary: the publisher
  injects `traceparent` into message headers, the consumer extracts it, and that
  is what makes one trace out of two processes
- Quorum queues, and why the queue type changes the metrics you get

**Verification.** A single trace spans `order-api` through RabbitMQ into
`order-worker`, and the APM service map shows the hop connecting them. If it does
not, propagation is broken — which is the regression this phase is designed to
catch early.

**Breaks.**

| Do this | Predict, then check |
|---|---|
| Hand the message to a thread pool the agent does not recognise (`APP_DETACH_CONTEXT=true`) | orphan root spans in the worker, and what the service map does |
| Stop the worker and keep publishing | which metric moves first, and how long before it is obvious |
| Trip a memory alarm | what publishers see, and whether the telemetry explains it |

**Gotchas.** Losing context in a consumer by dispatching to an unrecognised
thread is the single most common asynchronous-tracing failure, and the app has a
flag to reproduce it deliberately. Reusing one `service.name` across api and
worker degrades both correlation and the service graph, silently.

---

## W5 — The off-cluster estate: MongoDB and MinIO

**Objective.** The phase that mirrors production most closely and has the least
written about it. MongoDB and MinIO live outside Kubernetes; design a pipeline
that spans the boundary and keeps both sides correlatable. Two days.

**Local representation.** MongoDB (replica set) and MinIO (4-drive erasure set)
as plain Docker containers on the host, **outside the k3d network**, reached from
the cluster via `host.k3d.internal`. Each gets its own Collector container
alongside it.

That reproduces most of what this phase is about: the network boundary, the
identity gap (no `k8s.pod.name`, and never will be), log collection with
`filelog` over a mounted volume, the credential-ownership boundary, and the whole
of Design B — because a Collector container beside MongoDB runs the same
receivers, processors, queue settings and exporters as a Collector on a VM beside
MongoDB. Only the delivery mechanism differs.

**What the container reproduction does not teach, stated so it can be closed for
real later:**

- **Per-host metrics.** `hostmetrics` reads `/proc` and `/sys`, which inside a
  container belong to the single shared WSL2 VM. Pointing it at a mounted
  `root_path` measures that one VM, so "the MongoDB host" and "the MinIO host"
  would report identical numbers and "this VM's disk is filling up" cannot be
  represented at all. Substitute the `docker_stats` receiver, which gives genuine
  per-container CPU, memory, disk and network — the right concept through a
  different lens, with different field names to re-learn when doing it for real.
- **Systemd operations.** Restart-on-failure, journald, config at
  `/etc/otelcol/config.yaml`, upgrades via the package manager. This is
  operations knowledge rather than OpenTelemetry knowledge, and since the
  Collector config is nearly identical either way, the VM runbook can be written
  from the container config. One thing to check at work, because it is the single
  non-cosmetic difference: whether `mongod` on those VMs logs to a **file** or to
  **journald**, since that decides whether you need `filelog` or the `journald`
  receiver.
- **Clock skew.** Containers share the host clock and cannot easily be moved
  without `CAP_SYS_TIME`. See the break table for the substitute.

**Concepts.**

- The two designs, and why the choice is usually made by the firewall rather than
  by engineering
- Identity without Kubernetes: `resourcedetection/system` yields `host.name`,
  `os.type`, `host.arch`. There is no `k8s.pod.name` and never will be. What do
  you join on instead?
- Keeping `service.name`, `service.namespace` and `deployment.environment`
  consistent across two worlds, so Elastic correlates them at all
- Static targets versus dynamic discovery, with `file_sd_configs` as the middle
  ground
- Credential ownership across an administrative boundary
- Securing the OTLP hop once it leaves the cluster network: TLS, mTLS, auth headers
- The Collector as something you now operate in two places

**Design A — pull.** The gateway Collector holds a `prometheus` receiver with
static or file-based targets pointing at the VM endpoints, plus the `mongodb`
receiver. One place to configure and nothing to install on the VMs; but
cluster-to-VM ingress must be open on every metrics port, there are no logs
because nothing is tailing the VMs' files, scrape failures look like VM outages,
and the target list is hand-maintained.

**Design B — push.** A Collector runs on each VM with the `mongodb` or
`prometheus` receiver, `filelog` over the local log files, and `hostmetrics`,
exporting OTLP outbound to the gateway. Logs and host metrics become possible,
only one outbound port is needed, credentials stay on the machine that owns them,
and local buffering survives a cluster outage. The cost is a Collector fleet to
install, configure, patch and monitor.

**Build B as the primary**, since these VMs are under our control and logs are
not optional. Build A once for comparison, then write down which one production
should use and the constraint that decides it.

**Correlation across the boundary — the actual exercise.** Getting bytes into
Elastic from both sides is the easy half. The hard half is that a span from a pod
and a log line from a VM must be findable as facts about one incident.

- Set `deployment.environment` once at the gateway with an `attributes`
  processor, rather than trusting each source to get it right
- Decide whether MongoDB is a *service* (`service.name: mongodb`) or an
  *attribute of a host*. Pick one convention, apply it to both systems, write it
  down — this is the first entry in the attribute policy W7 produces.
- MongoDB emits no spans. The join between an `order-api` span and a `mongod`
  slow-query log is time plus host plus database name, not `trace.id`. Build that
  query in Kibana and feel how weak the join is. That weakness is the argument
  for keeping client-side Mongo spans rich, and since MongoDB is the only backing
  store, this is the most important correlation problem in the estate.
- Establish where the timestamp comes from on each path, and whether the clocks
  agree.

**Verification.** One Kibana session in which you open a slow `order-api`
transaction in APM, identify the MongoDB call inside it, jump to `mongod` logs
from the right host in the same time window, and confirm the `host.name` there
matches the one on the metrics that showed the latency — all from telemetry that
crossed an administrative boundary.

**Breaks.**

| Do this | Predict, then check |
|---|---|
| Block the scrape port under Design A | what the Collector logs, and whether it looks like a Mongo outage |
| Kill the VM Collector under Design B | what is lost, for how long, and whether anything alerts |
| Skew timestamps by 10 minutes with a `transform` processor (the container substitute for moving a VM clock) | what correlation looks like when time is the only join key |
| Give VM telemetry a different `deployment.environment` | how it silently splits every dashboard |
| Restart Elasticsearch with Design B running | whether `file_storage` actually saved the data |

**Gotchas.** `k8sattributes` cannot enrich telemetry that did not come from a
pod; applied indiscriminately it either no-ops or stamps the gateway pod's own
identity onto VM data, so scope it to the right pipeline. `host.k3d.internal`
resolves inside the cluster, while `localhost` in a Collector config inside a pod
means the pod — the most common first failure. MinIO metrics return 401 unless
`MINIO_PROMETHEUS_AUTH_TYPE=public` or a bearer token from
`mc admin prometheus generate` is in the scrape config; across a boundary
"public" is not acceptable, so do the token properly. The MongoDB monitoring user
needs `clusterMonitor`, which whoever owns the VM has to create — a conversation,
not a config change. `tls.insecure: true` was fine on Compose and is not fine
here. A push Collector without a `file_storage`-backed queue loses exactly the
data you wanted during an outage. And `hostmetrics` in a container silently
reports the shared WSL2 VM rather than a per-service host — the numbers look
plausible, which is what makes it dangerous.

---

## W6 — The React frontend

**Objective.** Instrument the browser and get one trace from a user's click all
the way through to MongoDB. One day.

**The SDK decision.** Three options, and it is a generational split:

| | `@elastic/apm-rum` | OTel JS browser SDK | EDOT Browser |
|---|---|---|---|
| Protocol | Elastic APM intake v2 | OTLP/HTTP | OTLP/HTTP |
| Sends to | APM Server only | any OTLP endpoint | any OTLP endpoint |
| Vendor lock | yes | none | none — the OTel SDK plus Elastic defaults |
| Out of the box | page-load waterfall, route changes, session and user context, breakdown metrics, Core Web Vitals | document load, fetch/XHR, user interaction, `traceparent` propagation — web vitals wired up yourself | the OTel set plus Core Web Vitals and session handling pre-wired |
| Kibana | populates the User Experience app | traces in APM; the UX app may not populate | the intended Elastic path |
| On 8.14.2 | supported | supported via the Collector | probably too new — verify |

EDOT Browser is the natural choice on a current stack, and probably unavailable
here. Decide between the other two with the same experiment as W1, and record the
reasoning: the classic agent lights up more of Kibana on 8.14, the OTel SDK is
what survives the next upgrade.

**The part that is actually hard.** Not the SDK. A browser is an untrusted,
unauthenticated client on someone else's network, which means an OTLP endpoint
exposed publicly through the ingress, CORS configured with `traceparent` in
`Access-Control-Allow-Headers`, and a considered answer to the fact that anyone
can now POST spans into your telemetry. Rate limiting, payload limits, and what
you are willing to believe from a browser are the content of this phase.

**Concepts.**

- Browser instrumentation versus server instrumentation: no persistent process,
  no clean shutdown, unreliable clocks, and users who close the tab mid-trace
- Context propagation from `fetch` into Spring Boot, and how a same-origin
  assumption in the fetch layer breaks it
- Core Web Vitals as user-centric metrics, and why server latency percentiles do
  not predict them
- Sessions and users as telemetry, and the privacy questions that follow

**Verification.** One trace beginning at a button click in the browser and ending
at a MongoDB write on a VM outside the cluster, visible as a single waterfall in
APM.

**Breaks.**

| Do this | Predict, then check |
|---|---|
| Omit `traceparent` from the CORS allow-list | what the browser console says, and what the trace looks like |
| POST a fabricated span from `curl` | that it lands, and what that implies |
| Close the tab mid-request | what arrives and what does not |
| Serve the frontend from a different origin | which instrumentation silently stops propagating |

---

## W7 — Hardening and team practices

**Objective.** Everything separating a demo from something you would run, and
then the artefacts that let a team run it without you. Two days.

**Sampling.**

- Head sampling with `OTEL_TRACES_SAMPLER=parentbased_traceidratio` is cheap,
  decided at the first span, and therefore blind to whether the trace turned out
  to be interesting. Under Operator injection it lives in the `Instrumentation`
  resource, which means the platform team changes it for everyone at once.
- Tail sampling composes `latency`, `status_code`, `probabilistic` and
  `rate_limiting` policies to keep every error and every slow trace while
  dropping the boring majority.
- The consequence that catches people out: tail sampling requires all spans of a
  trace to reach the same Collector instance. That forces a gateway tier and a
  `loadbalancing` exporter with `routing_key: traceID` in front of it — which is
  why W2's two-tier topology exists.
- Sampling can happen in the Collector or in APM Server. Doing both multiplies
  the rates and is a common, expensive mistake. Decide where it lives and say so.

**Cardinality and cost, in an index-everything store.** Prometheus series
arithmetic does not transfer. Here the cost drivers are the number of mapped
fields (every new attribute *key* is a new field, and unbounded key namespaces
cause mapping explosion, which is a stability problem and not merely a bill),
document count and size, and ILM phases. Check the mapping field count against
`index.mapping.total_fields.limit`. Use `filter` and `transform` (OTTL) to drop
and redact, `metricstransform` to rename and aggregate. Deliverable: a written
policy for what may and may not become an attribute.

**Reliability of the pipeline itself.** `sending_queue` backed by the
`file_storage` extension so a backend restart delays rather than destroys,
`retry_on_failure` tuning, `memory_limiter` sizing, and what actually happens
when the queue fills. Do this on both tiers and on the W5 VM Collectors, which
have no neighbour to fail over to. This matters more here than in most estates
because the backend shares fate with the cluster.

**Observing the Collector.** It emits its own metrics; scrape them and alert on
them:

```
otelcol_receiver_accepted_spans / otelcol_receiver_refused_spans
otelcol_exporter_sent_spans     / otelcol_exporter_send_failed_spans
otelcol_processor_dropped_metric_points
otelcol_exporter_queue_size     / otelcol_exporter_queue_capacity
```

An unmonitored telemetry pipeline that silently drops data is worse than no
pipeline, because it produces confident, wrong dashboards. With Operator-managed
injection, also watch for pods that start *without* injection — that produces no
error and no telemetry.

**Security, turned back on.** Everything W0 disabled: `xpack.security.enabled`,
TLS on Elasticsearch, an API key per Collector with least-privilege index
permissions rather than a superuser, TLS and auth on every OTLP hop, and secrets
out of Collector YAML via environment expansion and Kubernetes Secrets. W3
already forced some of this; finish it.

**Alerting and SLOs.** Define an SLO for the demo app and implement it in Kibana
with a burn-rate rule. Alert on symptoms — latency, error rate, queue depth —
rather than causes such as CPU.

### Team-facing deliverables

The actual point of the project. Three documents, written for colleagues who will
not read this curriculum.

1. **`docs/onboarding-a-service.md`** — how to add a Spring Boot service to
   observability in this environment: the annotation, the `service.name`
   convention, what comes free, what needs code (custom spans, custom metrics,
   MDC), how to verify it worked in Kibana within five minutes, and how to tell
   whether injection actually happened.
2. **`docs/attribute-policy.md`** — naming and cardinality rules. Which semantic
   conventions are mandatory (`service.name`, `service.namespace`,
   `deployment.environment`), what may never be an attribute key or metric label,
   how to name a custom metric, and who to ask. One page, with examples of both
   the right and the wrong thing.
3. **`docs/review-checklist.md`** — what a reviewer looks for in a pull request
   that touches telemetry. Ten lines, checkbox form.

**Verification for the phase.** Hand the onboarding document to someone who has
not done this and watch them instrument a new service without asking a question.
Anything they ask is a bug in the document.

---

## Reference: the gotcha list

Consolidated, roughly in the order they will be hit.

1. Using `otel/opentelemetry-collector` instead of `-contrib`. Most receivers are
   missing. Symptom: `unknown type: "filelog"`.
2. Defining a component and never listing it in `service.pipelines`. Silently
   inert. Always the first thing to check when data does not arrive.
3. Starting the JVM without `-javaagent`, or pinning the agent to
   `releases/latest/download/`. Symptom: zero spans and no error, or
   instrumentation that changes version between two builds of one commit.
4. Expecting route A (the `elasticsearch` exporter) to populate the APM app on
   8.14.2. The data lands and is searchable; APM stays empty; nothing errors.
5. Elasticsearch with no heap limit. It sizes itself to the host and starves
   everything else.
6. Kibana not matching the Elasticsearch major version exactly.
7. Not giving Kibana `depends_on` with `condition: service_healthy`.
   Elasticsearch is slow to become healthy and Kibana crash-loops on first boot.
8. `vm.max_map_count` below 262144 — and setting it on Windows rather than inside
   the WSL2 VM, where it actually applies.
9. Mixing `mapping.mode: otel` and `ecs` in one deployment. Two field names for
   one concept; every dashboard works for half the data, with no error.
10. Leaving Elasticsearch on local defaults with no ILM policy. Nothing ages out
    and the first symptom is a full disk.
11. Unbounded metric labels and attribute keys — user IDs, request IDs, full URLs,
    raw queries. In Prometheus that is a series explosion; in Elasticsearch it is
    a mapping explosion. Same mistake, different shape.
12. Reusing one `service.name` across two services. Correlation and the service
    graph both degrade silently.
13. Applying an `Instrumentation` resource and expecting existing pods to change.
    The webhook fires only at pod creation. `kubectl rollout restart`.
14. Putting `instrumentation.opentelemetry.io/inject-java` on the Deployment's own
    metadata instead of `spec.template.metadata.annotations`. No error, no
    injection, no telemetry.
15. Annotating a pod whose image already sets `-javaagent` in `JAVA_TOOL_OPTIONS`.
    Two agents, and the JVM refuses to start.
16. Expecting Operator injection to work on a GraalVM native image. It cannot;
    that path needs `opentelemetry-spring-boot-starter` compiled in.
17. Assuming the newest ECK operator supports 8.14.2. Check the compatibility
    matrix.
18. Forgetting that ECK enables TLS and generates credentials by default, then
    wondering why the Collector cannot connect.
19. Running `k8sattributes` over telemetry that did not come from a pod. It either
    no-ops or stamps the gateway's own pod identity onto VM data.
20. Using `localhost` in a Collector config inside a pod to mean the node or the
    host. Use `host.k3d.internal` or the node IP for off-cluster targets.
21. Different `deployment.environment` values either side of the cluster boundary.
    Every dashboard silently splits in two.
22. A VM-side Collector with no `file_storage`-backed `sending_queue`. It loses
    exactly the data you wanted during a backend outage.
23. Losing context in a RabbitMQ consumer by handing the message to a thread the
    agent does not recognise. Symptom: orphan root spans in the worker.
24. MinIO metrics returning 401. Set `MINIO_PROMETHEUS_AUTH_TYPE=public` for local
    only, or generate a bearer token with `mc admin prometheus generate`.
25. A MongoDB monitoring user without `clusterMonitor`.
26. Omitting `traceparent` from the CORS allow-list on the browser OTLP endpoint.
    The trace silently starts at the server instead of at the click.
27. Tail sampling behind a plain round-robin load balancer. Spans of one trace
    land on different Collectors and traces come out incomplete.
28. Sampling in both the Collector and APM Server. The rates multiply, and the
    trace volume you get is not the one you configured.
29. Semantic conventions that moved — `http.method` became `http.request.method`.
    An empty dashboard panel is usually a renamed attribute.
30. Not calling `sdk.shutdown()` on `SIGTERM`. The final batch is lost on every
    deploy.

---

## What was deliberately dropped

An earlier version of this repository built up from first principles: signals and
Collector anatomy, then a Grafana LGTM stack as a teaching backend, then
application instrumentation, then infrastructure receivers — all on Compose,
before reaching Elastic. Those phases and their explainers were removed when this
branch started.

The reasoning: the Grafana stack was there to make the three signals feel
genuinely distinct, by forcing three query languages against three stores. That
is a real pedagogical benefit and it costs several days that this project does
not have. Since the destination is fixed and known, the same understanding gets
built by working directly against Elastic and being explicit about what a single
search engine lets you skip.

What that trade costs, stated honestly so it can be corrected later: metrics
ergonomics and cost intuition are much clearer in a Prometheus-shaped store than
in an index-everything one, and cardinality is a more visceral lesson when you can
watch series count explode. W1 and W7 substitute the Elastic-shaped version of
the same arithmetic. If the team ever has to defend the choice of Elastic over
Grafana, that comparison will have to be built for real.

The demo application in `app/` survives unchanged. It is the only source of spans
in the whole estate, and it was already built for exactly this purpose.
