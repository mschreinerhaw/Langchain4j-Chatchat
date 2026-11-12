# Build context is an uploaded, fingerprinted source snapshot, not local JARs.
ARG NODE_BUILD_IMAGE
ARG JAVA_BUILD_IMAGE
ARG JAVA_RUNTIME_IMAGE
ARG CODING_BASE_IMAGE

FROM ${NODE_BUILD_IMAGE} AS frontend
WORKDIR /build/chatchat-api/web-app
COPY chatchat-api/web-app/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY chatchat-api/web-app/ ./
COPY chatchat-common/src/main/resources/runtime/ /build/chatchat-common/src/main/resources/runtime/
RUN node node_modules/vite/bin/vite.js build --outDir /frontend/api
WORKDIR /build/chatchat-mcp-server/web-app-mcp
COPY chatchat-mcp-server/web-app-mcp/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY chatchat-mcp-server/web-app-mcp/ ./
RUN node node_modules/vite/bin/vite.js build --outDir /frontend/mcp

FROM ${JAVA_BUILD_IMAGE} AS java-build
COPY tools/maven/ /opt/maven/
ENV PATH=/opt/maven/bin:$PATH MAVEN_OPTS="-Xmx1536m -Dfile.encoding=UTF-8"
WORKDIR /source
COPY . .
COPY --from=frontend /frontend /frontend
RUN --mount=type=cache,id=runtime-os-source-maven,target=/root/.m2/repository \
    test -s coding-agent-node/skills/catalog.json && \
    mvn -B -ntp -pl chatchat-api,chatchat-mcp-server,chatchat-runtime-news -am \
    -Dfrontend.skip=true -Dfrontend.install.skip=true -Dcontainer.image=true \
    -Dweb.static.directory=/frontend/api -Dmcp.web.static.directory=/frontend/mcp \
    -Dassembly.skipAssembly=true -Dmaven.test.skip=true clean package && \
    test -s chatchat-chat/target/classes/coding-skills/catalog.json

FROM ${JAVA_RUNTIME_IMAGE} AS java-runtime
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai
WORKDIR /srv/app
RUN mkdir -p config data logs
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]

FROM java-runtime AS news
COPY --from=java-build /source/chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar /srv/app/app.jar
EXPOSE 8091

FROM java-runtime AS mcp
COPY --chmod=0755 tools/docker /usr/local/bin/docker
RUN docker --version
COPY --from=java-build /source/chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT.jar /srv/app/app.jar
EXPOSE 8090 9091

FROM java-runtime AS api
COPY deploy/docker/application/sql-toolchain-requirements.txt /tmp/sql-toolchain-requirements.txt
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip \
    && python3 -m pip install --no-cache-dir --require-hashes -r /tmp/sql-toolchain-requirements.txt \
    && python3 -c "import sqlglot; assert sqlglot.__version__ == '30.23.0'" \
    && rm -rf /var/lib/apt/lists/*
COPY --from=java-build /source/chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar /srv/app/app.jar
EXPOSE 8080
