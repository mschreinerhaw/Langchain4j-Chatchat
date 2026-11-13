FROM mcr.microsoft.com/playwright:v1.56.1-noble@sha256:f1e7e01021efd65dd1a2c56064be399f3e4de00fd021ac561325f2bfbb2b837a
WORKDIR /app
COPY package*.json ./
RUN npm ci --ignore-scripts
COPY build.mjs catalog.mjs server.mjs worker.mjs ./
COPY acceptance.mjs offline-check.mjs verify-delivered.mjs ./
COPY licenses ./licenses
RUN node build.mjs && mkdir jobs && chown pwuser:pwuser jobs
USER pwuser
CMD ["node","server.mjs"]
