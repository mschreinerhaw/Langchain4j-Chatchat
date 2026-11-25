FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm test && npm run build && npm prune --omit=dev --no-audit --no-fund

FROM node:24-bookworm-slim AS runtime
ARG GATEWAY_SOURCE_SHA=unknown
LABEL org.opencontainers.image.version="1.2.0-rc.1" org.opencontainers.image.revision="${GATEWAY_SOURCE_SHA}"
ENV NODE_ENV=production
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && install -d -o node -g node -m 0700 /app/data /app/logs /app/certs
COPY --from=build --chown=node:node /app/package.json ./package.json
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
USER node
EXPOSE 8443 18081
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "dist/server/health.js"]
CMD ["node", "dist/server/main.js"]
