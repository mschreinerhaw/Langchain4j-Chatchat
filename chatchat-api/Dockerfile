# syntax=docker/dockerfile:1
# Build from repository root: docker build -f chatchat-api/Dockerfile .
ARG JAVA_BUILD_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
ARG NODE_IMAGE=node:24-bookworm-slim
FROM ${NODE_IMAGE} AS frontend
ARG NPM_HTTPS_PROXY=https://192.168.36.128:8443
WORKDIR /source/chatchat-api/web-app
COPY chatchat-api/web-app/package*.json ./
RUN --mount=type=cache,target=/root/.npm \
    --mount=type=secret,id=proxy_ca,target=/run/secrets/proxy-ca.pem,required=true \
    NODE_EXTRA_CA_CERTS=/run/secrets/proxy-ca.pem npm ci --proxy="$NPM_HTTPS_PROXY" --https-proxy="$NPM_HTTPS_PROXY" --noproxy=localhost,127.0.0.1 --no-audit --no-fund
COPY chatchat-api/web-app/ ./
COPY chatchat-common/src/main/resources/runtime/ /source/chatchat-common/src/main/resources/runtime/
RUN node --max-old-space-size=4096 node_modules/vite/bin/vite.js build --outDir /frontend
FROM ${JAVA_BUILD_IMAGE} AS build
ENV MAVEN_OPTS="-Xmx1536m -Djava.net.preferIPv4Stack=true"
WORKDIR /source
COPY . .
COPY --from=frontend /frontend /frontend
RUN --mount=type=cache,id=chatchat-production-maven,target=/root/.m2,sharing=locked \
    --mount=type=secret,id=maven_settings,target=/run/secrets/maven-settings.xml,required=true \
    mvn -s /run/secrets/maven-settings.xml -U -B -ntp -pl chatchat-api -am \
      -Dfrontend.skip=true -Dfrontend.install.skip=true \
      -Dweb.static.directory=/frontend -Dmcp.web.static.directory=/frontend \
      -Dcontainer.image=true -Dassembly.skipAssembly=true -Dmaven.test.skip=true package

FROM ${JAVA_RUNTIME_IMAGE} AS runtime
COPY deploy/docker/application/sql-toolchain-requirements.txt /tmp/requirements.txt
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip \
    && python3 -m pip install --no-cache-dir --require-hashes -r /tmp/requirements.txt \
    && python3 -c "import sqlglot; assert sqlglot.__version__ == '30.23.0'" \
    && rm -rf /var/lib/apt/lists/* /tmp/requirements.txt
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app \
    && mkdir -p /srv/app/config /srv/app/data /srv/app/logs \
    && chown -R app:app /srv/app
WORKDIR /srv/app
COPY --from=build --chown=app:app /source/chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar /srv/app/app.jar
ENV LANG=C.UTF-8 TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8" \
    SERVER_SHUTDOWN=graceful SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s \
    SPRING_JPA_HIBERNATE_DDL_AUTO=validate
ENV SPRING_PROFILES_ACTIVE=prod CHATCHAT_DATASOURCE_CONFIG=datasource-postgresql.yml
USER 10001:10001
EXPOSE 8080
# TCP liveness only; deployment must additionally verify dependencies and business APIs.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD bash -c 'echo > /dev/tcp/127.0.0.1/${SERVER_PORT:-8080}' || exit 1
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]
