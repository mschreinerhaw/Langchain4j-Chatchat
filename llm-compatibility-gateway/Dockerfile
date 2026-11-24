FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build && npm prune --omit=dev --no-audit --no-fund

FROM node:24-bookworm-slim AS runtime
ENV NODE_ENV=production
WORKDIR /app
RUN install -d -o node -g node -m 0700 /app/data /app/logs
COPY --from=build --chown=node:node /app/package.json ./package.json
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
USER node
EXPOSE 18080 18081
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "-e", "Promise.all([fetch('http://127.0.0.1:18080/ready'),fetch('http://127.0.0.1:18081/')]).then(r=>{if(r.some(v=>!v.ok))process.exit(1)}).catch(()=>process.exit(1))"]
CMD ["node", "dist/server/main.js"]
