# syntax=docker/dockerfile:1
# Repository-root build context. Node ABI and libc match builder and runtime.
ARG NODE_IMAGE=node:24-bookworm-slim
ARG BUN_IMAGE=oven/bun:1.4.2
FROM ${BUN_IMAGE} AS bun
FROM ${NODE_IMAGE} AS upstream
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates python3 make g++ unzip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=bun /usr/local/bin/bun /usr/local/bin/bun
RUN ln -s /usr/local/bin/bun /usr/local/bin/bunx
WORKDIR /build
# Immutable source is shared with upstream-pin.json; do not build a moving branch.
RUN git init cline && cd cline \
    && git remote add origin https://github.com/cline/cline.git \
    && git fetch --depth=1 origin a459f1d04dea030833b558be645011ab739c463b \
    && git checkout --detach FETCH_HEAD
COPY cline-agent-server/scripts/ /service/scripts/
COPY cline-agent-server/upstream-pin.json /service/upstream-pin.json
ENV CLINE_SOURCE_ROOT=/build/cline
ARG CLINE_RUNTIME_OPERATION_CONTEXT=1
ENV BUN_CONFIG_MAX_HTTP_REQUESTS=16
RUN --mount=type=cache,id=cline-production-bun,target=/root/.bun/install/cache \
    bash /service/scripts/build.sh \
    && python3 /service/scripts/assemble-runtime.py /build/cline /runtime

FROM ${NODE_IMAGE} AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates git python3 bash libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app \
    && mkdir -p /srv/app/state /workspace \
    && chown -R app:app /srv/app /workspace
COPY --from=upstream /runtime/ /opt/cline/runtime/
COPY cline-agent-server/src/ /srv/app/src/
COPY cline-agent-server/scripts/verify-runtime.mjs /srv/app/scripts/verify-runtime.mjs
COPY cline-agent-server/docker/verify-native-modules.mjs /srv/app/scripts/verify-native-modules.mjs
COPY coding-agent-node/package.json /srv/coding-agent-node/package.json
COPY coding-agent-node/src/ /srv/coding-agent-node/src/
COPY coding-agent-node/skills/ /srv/coding-agent-node/skills/
RUN node /srv/app/scripts/verify-runtime.mjs /opt/cline/runtime \
    && node /srv/app/scripts/verify-native-modules.mjs /opt/cline/runtime
ENV NODE_ENV=production HOME=/home/app LANG=C.UTF-8 TZ=Asia/Shanghai \
    CLINE_SERVER_CONFIG=/run/secrets/cline-server.json
WORKDIR /srv/app
USER 10001:10001
EXPOSE 18326
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["node", "-e", "const c=JSON.parse(require('fs').readFileSync(process.env.CLINE_SERVER_CONFIG));fetch('http://127.0.0.1:'+(c.port??18326)+'/health/ready',{headers:{Authorization:'Bearer '+c.apiToken}}).then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"]
CMD ["node", "/srv/app/src/server.mjs"]
