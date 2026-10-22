FROM node:24.21.0-bookworm-slim AS build

WORKDIR /srv/coding-agent
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY src ./src
COPY web ./web
COPY scripts/build.mjs ./scripts/build.mjs
COPY tsconfig.json tsconfig.web.json ./
RUN npm run build && npm prune --omit=dev --ignore-scripts --no-audit --no-fund

FROM node:24.21.0-bookworm-slim

ARG BUILD_REVISION=development
LABEL org.opencontainers.image.revision=$BUILD_REVISION

ENV NODE_ENV=production \
    RUNTIME_CODING_HOST=0.0.0.0 \
    RUNTIME_CODING_PORT=3100 \
    RUNTIME_CODING_BUILD=$BUILD_REVISION \
    RUNTIME_CODING_CALLBACK_TIMEOUT_MS=300000 \
    RUNTIME_CODING_LOG_LEVEL=info

WORKDIR /srv/coding-agent
COPY --from=build --chown=node:node /srv/coding-agent/node_modules ./node_modules
COPY --from=build --chown=node:node /srv/coding-agent/dist ./dist
COPY --chown=node:node package.json ./
# Retain the health probe path used by existing Runtime deployment tooling.
COPY --chown=node:node src/healthcheck.js ./src/healthcheck.js
RUN node dist/sdk-packages.js && mkdir /srv/monitor-data && chown node:node /srv/monitor-data
USER node
EXPOSE 3100
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 CMD ["node", "dist/healthcheck.js"]
CMD ["node", "dist/server.js"]
