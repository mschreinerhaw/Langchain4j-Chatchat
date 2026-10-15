FROM node:24.21.0-bookworm-slim

ENV NODE_ENV=production \
    RUNTIME_CODING_HOST=0.0.0.0 \
    RUNTIME_CODING_PORT=3100

WORKDIR /srv/coding-agent
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund \
    && npm cache clean --force
COPY --chown=node:node src ./src
USER node
EXPOSE 3100
CMD ["node", "src/server.js"]
