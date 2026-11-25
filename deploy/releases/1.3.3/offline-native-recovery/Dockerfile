ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY governance-server.mjs runtime-model.mjs worker-executor.mjs worker-executor-client.mjs /opt/runtime-os/coding-node/service/src/
RUN cd /opt/runtime-os/coding-node/service/src \
    && node --check governance-server.mjs \
    && node --check runtime-model.mjs \
    && node --check worker-executor.mjs \
    && node --check worker-executor-client.mjs
