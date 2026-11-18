# syntax=docker/dockerfile:1
# Build from repository root: docker build -f chatchat-mcp-server/Dockerfile .
ARG JAVA_BUILD_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
ARG NODE_IMAGE=node:24-bookworm-slim
FROM ${NODE_IMAGE} AS frontend
ARG NPM_HTTPS_PROXY=https://192.168.36.128:8443
WORKDIR /source/chatchat-mcp-server/web-app-mcp
COPY chatchat-mcp-server/web-app-mcp/package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    --mount=type=secret,id=proxy_ca,target=/run/secrets/proxy-ca.pem,required=true \
    NODE_EXTRA_CA_CERTS=/run/secrets/proxy-ca.pem npm ci --proxy="$NPM_HTTPS_PROXY" --https-proxy="$NPM_HTTPS_PROXY" --noproxy=localhost,127.0.0.1 --no-audit --no-fund
COPY chatchat-mcp-server/web-app-mcp/ ./
RUN node --max-old-space-size=4096 node_modules/vite/bin/vite.js build --outDir /frontend
FROM ${JAVA_BUILD_IMAGE} AS build
ENV MAVEN_OPTS="-Xmx1536m -Djava.net.preferIPv4Stack=true"
WORKDIR /source
COPY . .
COPY --from=frontend /frontend /frontend
RUN --mount=type=cache,id=chatchat-production-maven,target=/root/.m2,sharing=locked \
    --mount=type=secret,id=maven_settings,target=/run/secrets/maven-settings.xml,required=true \
    mvn -s /run/secrets/maven-settings.xml -U -B -ntp -pl chatchat-mcp-server -am \
      -Dfrontend.skip=true -Dfrontend.install.skip=true \
      -Dweb.static.directory=/frontend -Dmcp.web.static.directory=/frontend \
      -Dcontainer.image=true -Dassembly.skipAssembly=true -Dmaven.test.skip=true package

FROM docker:27.5.1-cli AS docker-cli
FROM ${JAVA_RUNTIME_IMAGE} AS runtime
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app \
    && mkdir -p /srv/app/config /srv/app/data /srv/app/logs \
    && chown -R app:app /srv/app
WORKDIR /srv/app
COPY --from=build --chown=app:app /source/chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT.jar /srv/app/app.jar
ENV LANG=C.UTF-8 TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8" \
    SERVER_SHUTDOWN=graceful SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s \
    SPRING_JPA_HIBERNATE_DDL_AUTO=validate
ENV SPRING_PROFILES_ACTIVE=prod CHATCHAT_DATASOURCE_CONFIG=datasource-postgresql.yml
USER 10001:10001
EXPOSE 8090
# TCP liveness only; deployment must additionally verify dependencies and business APIs.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD bash -c 'echo > /dev/tcp/127.0.0.1/${SERVER_PORT:-8090}' || exit 1
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]
