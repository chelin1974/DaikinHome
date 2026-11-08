# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build stage
FROM maven:3.10-eclipse-temurin-27 AS build
WORKDIR /src

COPY pom.xml ./
COPY core core
COPY cli cli
COPY web web

# A BuildKit cache mount rather than a separate `dependency:go-offline` layer:
# go-offline cannot resolve this build's own inter-module dependencies and fails,
# whereas the cache mount survives between builds and needs no workaround.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# -------------------------------------------------------------- runtime stage
FROM eclipse-temurin:27-jre

LABEL org.opencontainers.image.title="Daikin One HomeKit bridge" \
      org.opencontainers.image.description="Daikin One thermostats in Apple Home, with a REST API" \
      org.opencontainers.image.source="https://github.com/chelin1974/DaikinHome" \
      org.opencontainers.image.licenses="MIT"

RUN useradd --system --uid 10001 --create-home --home-dir /app --shell /usr/sbin/nologin daikin \
 && mkdir -p /data \
 && chown daikin:daikin /data

WORKDIR /app
COPY --from=build --chown=daikin:daikin /src/web/target/daikin-home-web.jar ./daikin-home-web.jar

USER 10001

# HomeKit pairing state. It MUST outlive the container: lose it and every paired
# controller sees a stranger where its bridge used to be, and the accessories go
# "No Response" until the bridge is removed and re-added in the Home app.
VOLUME ["/data"]
ENV DAIKIN_STORAGE_PATH=/data/daikin-home.state.json

# 8080 is the REST API and actuator. 51826 is the HomeKit accessory port.
#
# Neither is the whole story: HomeKit discovery is mDNS multicast on
# 224.0.0.251:5353, which a container on a bridge/overlay network cannot send or
# receive. Run this with the host's network (`--network host`, or hostNetwork in
# Kubernetes) or the Home app will never find the bridge. See deploy/k8s/README.md.
EXPOSE 8080/tcp 51826/tcp

# No shell, so the JVM is PID 1 and gets SIGTERM directly. Override `command` in
# Kubernetes, or set JAVA_TOOL_OPTIONS, to add flags.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/daikin-home-web.jar"]
