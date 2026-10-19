FROM node:24.21.0-bookworm-slim

ARG BUILD_REVISION=development
LABEL org.opencontainers.image.revision=$BUILD_REVISION

ENV NODE_ENV=production \
    RUNTIME_CODING_HOST=0.0.0.0 \
    RUNTIME_CODING_PORT=3100 \
    RUNTIME_CODING_BUILD=$BUILD_REVISION \
    RUNTIME_CODING_CALLBACK_TIMEOUT_MS=90000 \
    RUNTIME_CODING_LOG_LEVEL=info

WORKDIR /srv/coding-agent
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund \
    && npm cache clean --force
COPY --chown=node:node src ./src
USER node
EXPOSE 3100
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 CMD ["node", "src/healthcheck.js"]
CMD ["node", "src/server.js"]
