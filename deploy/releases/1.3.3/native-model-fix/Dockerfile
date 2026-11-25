ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY governance-server.mjs runtime-model.mjs /opt/runtime-os/coding-node/service/src/
RUN node --check /opt/runtime-os/coding-node/service/src/governance-server.mjs \
    && node --check /opt/runtime-os/coding-node/service/src/runtime-model.mjs
