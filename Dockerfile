# syntax=docker/dockerfile:1
# baobab-subscriptions: the Baobab Billing API (ADR-SUB-0001).
# Base images are pinned by tag and digest; never latest.

FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package \
 && mkdir -p /app \
 && cp target/baobab-subscriptions-*.jar /app/baobab-subscriptions.jar \
 && cp -r target/lib /app/lib

FROM eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f
ARG VERSION=0.0.0-dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="baobab-subscriptions" \
      org.opencontainers.image.description="Baobab headless subscription billing engine (temporary provider; Kill Bill not yet integrated)" \
      org.opencontainers.image.source="https://github.com/baobab-platform/baobab-subscriptions" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="Baobab Platform"
RUN groupadd --system --gid 10001 baobab \
 && useradd --system --uid 10001 --gid baobab --home-dir /app --shell /usr/sbin/nologin baobab
WORKDIR /app
COPY --from=build --chown=root:root /app /app
USER 10001:10001
EXPOSE 8080
ENV HTTP_PORT=8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD ["java", "-cp", "/app/baobab-subscriptions.jar", "com.baobabplatform.subscriptions.HealthCheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/baobab-subscriptions.jar"]
