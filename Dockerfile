FROM gradle:8.14.3-jdk21 AS builder

ARG SERVICE
WORKDIR /workspace

COPY settings.gradle build.gradle ./
COPY gradle ./gradle
COPY shared-libs ./shared-libs
COPY ai-service ./ai-service
COPY orchestrator-service ./orchestrator-service
COPY tg-connector-service ./tg-connector-service
COPY vk-connector-service ./vk-connector-service

RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked \
    test -n "$SERVICE" \
    && gradle ":${SERVICE}:test" ":${SERVICE}:bootJar" --no-daemon \
    && mkdir -p /out \
    && find "${SERVICE}/build/libs" \
        -maxdepth 1 \
        -type f \
        -name '*.jar' \
        ! -name '*-plain.jar' \
        -exec cp {} /out/app.jar \; \
        -quit \
    && test -s /out/app.jar

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 curator \
    && useradd --system --uid 10001 --gid curator --home-dir /app curator

WORKDIR /app
COPY --from=builder --chown=curator:curator /out/app.jar /app/app.jar

USER curator
EXPOSE 8081 8082 8083 8084

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
