# One image, two services. service.name comes from the environment, not the code
# - that is the whole reason api and worker can share this build.
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
# pom first, so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21.0.12_8-jre-alpine
WORKDIR /app

# The agent is a jar, not a dependency. It is not on the application classpath,
# it does not appear in pom.xml, and the application cannot see it.
ARG OTEL_AGENT_VERSION=2.31.1
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar

COPY --from=build /build/target/app.jar /app/app.jar
# No ENTRYPOINT: docker-compose.yaml sets the full java command per service so
# the -javaagent flag is visible there.
