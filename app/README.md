# Demo app — `order-api` and `order-worker`

Spring Boot 3.5.16 on Java 21. The only source of traces in this project.
MongoDB, RabbitMQ and MinIO emit metrics and logs but never spans; every span
you see for them is produced by the client library in this app.

```
POST /orders ──▶ order-api ──publish──▶ RabbitMQ ──consume──▶ order-worker ──▶ MongoDB
                                                                          └──▶ MinIO
                                                                          └──▶ MongoDB
```

One jar, two services. Which half runs comes from `SPRING_PROFILES_ACTIVE`;
the telemetry identity comes from `OTEL_SERVICE_NAME`. Neither is in the code,
which is what lets the same build run as both.

## How OpenTelemetry gets in

It does not. That is the point.

```
java -javaagent:/otel/opentelemetry-javaagent.jar -jar /app/app.jar
```

The agent is a jar attached to the JVM, not a dependency. It is not in
`pom.xml`, it is not on the application classpath, and no class in `shop.orders`
imports anything from it. Before `main()` runs, the agent's `premain` hook
installs bytecode transformers that rewrite ~130 known libraries as they are
loaded — Tomcat, Spring AMQP, the RabbitMQ client, the Mongo driver, OkHttp —
so that each one opens and closes a span around its own work.

Configuration is entirely `OTEL_*` environment variables, set in
`work/w0/docker-compose.yaml`. Those are the
same spec-defined variables every other OpenTelemetry SDK reads, in any
language. Nothing in `application.yaml` mentions OpenTelemetry.

| File | Role |
|---|---|
| `pom.xml` | four Spring starters, one MinIO client, one OTel **API** dependency |
| `OrdersApplication.java` | `main`. Nine lines |
| `ApiController.java` | `POST /orders`, validates and publishes (`api` profile) |
| `OrderListener.java` | consumes, writes Mongo, writes MinIO, updates Mongo (`worker` profile) |
| `Telemetry.java` | one counter, one histogram, deliberately low-cardinality labels |
| `AmqpConfig.java` | exchange, queue, binding, JSON converter |
| `WorkerConfig.java` | the MinIO client bean |

### The one OTel dependency, and why it is there

```xml
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-api</artifactId>
</dependency>
```

API only, no SDK. It exists for the two things an agent structurally cannot do
for you: hand-written metrics (`Telemetry.java`), and marking a span failed when
the exception never escapes your own method (`OrderListener.handle()` catches it,
so `spring-rabbit` sees a successful delivery — `Span.current().recordException()`
is what puts the failure where `{status=error}` can find it).

Tracing itself needs nothing here. Every span in the waterfall appears with this
dependency deleted; only the error status and the custom metrics would go.

At runtime the agent replaces the API's no-op implementation with the real SDK.
Without `-javaagent` the API stays no-op: `Telemetry` still constructs, the
counter still accepts `add(1, ...)`, and the numbers go nowhere. Nothing throws.

## Build locally

```sh
mvn -B package
```

It is not run from here — `work/w0/docker-compose.yaml` builds and runs it.
The Docker build downloads the agent jar by pinned version:

```dockerfile
ARG OTEL_AGENT_VERSION=2.31.1
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
```

Pin it. `.../releases/latest/download/...` is what most tutorials show and it
means your instrumentation silently changes version between two builds of the
same commit.

## Version notes

- **Spring Boot 3.5.16** is the last open-source patch of the 3.5 line, which
  reached EOL on 30 June 2026. Boot 4.x is where new work should start; the
  agent path in this app is unchanged there.
- **MinIO client 8.5.17**, not 9.x. Version 8.6.0 and later depend on OkHttp 5,
  whose Maven artifact is a Kotlin-multiplatform shell — the classes live in
  `okhttp-jvm` and a plain Maven build fails with `cannot access okhttp3.HttpUrl`.
  8.5.17 is the last release on OkHttp 4.12.0, which is also the version the
  agent's `okhttp-3.0` instrumentation actually covers.
