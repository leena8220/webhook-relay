# syntax=docker/dockerfile:1.7
FROM gradle:9.8.0-jdk21-alpine AS build

ARG APP_MODULE
WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY relay-core/build.gradle.kts relay-core/build.gradle.kts
COPY relay-api/build.gradle.kts relay-api/build.gradle.kts
COPY relay-worker/build.gradle.kts relay-worker/build.gradle.kts
RUN ./gradlew --no-daemon dependencies >/dev/null

COPY relay-core/src relay-core/src
COPY relay-api/src relay-api/src
COPY relay-worker/src relay-worker/src
RUN ./gradlew --no-daemon ":${APP_MODULE}:bootJar" \
    && cp "${APP_MODULE}/build/libs/"*.jar /workspace/application.jar

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S relay && adduser -S -D -H -u 10001 -G relay relay
WORKDIR /app
COPY --from=build --chown=relay:relay /workspace/application.jar /app/application.jar

USER 10001:10001
EXPOSE 8080 8081

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/application.jar"]
