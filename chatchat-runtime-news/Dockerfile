# syntax=docker/dockerfile:1
# Build from repository root: docker build -f chatchat-runtime-news/Dockerfile .
ARG JAVA_BUILD_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
ARG NODE_IMAGE=node:24-bookworm-slim
FROM ${JAVA_BUILD_IMAGE} AS build
ENV MAVEN_OPTS="-Xmx1536m -Djava.net.preferIPv4Stack=true"
WORKDIR /source
COPY . .
RUN --mount=type=cache,id=chatchat-production-maven,target=/root/.m2,sharing=locked \
    --mount=type=secret,id=maven_settings,target=/run/secrets/maven-settings.xml,required=true \
    mvn -s /run/secrets/maven-settings.xml -U -B -ntp -pl chatchat-runtime-news -am \
      -Dfrontend.skip=true -Dfrontend.install.skip=true \
      -Dweb.static.directory=/frontend -Dmcp.web.static.directory=/frontend \
      -Dcontainer.image=true -Dassembly.skipAssembly=true -Dmaven.test.skip=true package

FROM ${JAVA_RUNTIME_IMAGE} AS runtime
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app \
    && mkdir -p /srv/app/config /srv/app/data /srv/app/logs \
    && chown -R app:app /srv/app
WORKDIR /srv/app
COPY --from=build --chown=app:app /source/chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar /srv/app/app.jar
ENV LANG=C.UTF-8 TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8" \
    SERVER_SHUTDOWN=graceful SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s \
    SPRING_JPA_HIBERNATE_DDL_AUTO=validate
ENV SPRING_PROFILES_ACTIVE=postgresql \
    CHATCHAT_RUNTIME_NEWS_TEMPORAL_SERVER_MODE=external
USER 10001:10001
EXPOSE 8091
# TCP liveness only; deployment must additionally verify dependencies and business APIs.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD bash -c 'echo > /dev/tcp/127.0.0.1/${SERVER_PORT:-8091}' || exit 1
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]
