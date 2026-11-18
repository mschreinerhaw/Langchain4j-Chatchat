FROM mcr.microsoft.com/playwright:v1.56.1-noble@sha256:f1e7e01021efd65dd1a2c56064be399f3e4de00fd021ac561325f2bfbb2b837a
WORKDIR /app
COPY package*.json ./
RUN --mount=type=secret,id=proxy_ca,target=/run/secrets/proxy-ca.pem,required=true \
    test -n "$HTTPS_PROXY" && NODE_EXTRA_CA_CERTS=/run/secrets/proxy-ca.pem npm ci --ignore-scripts --proxy="$HTTPS_PROXY" --https-proxy="$HTTPS_PROXY" --noproxy=localhost,127.0.0.1
COPY build.mjs catalog.mjs server.mjs worker.mjs ./
COPY acceptance.mjs offline-check.mjs verify-delivered.mjs ./
COPY licenses ./licenses
COPY report-assets ./report-assets
RUN node build.mjs && mkdir jobs && chown pwuser:pwuser jobs
USER pwuser
CMD ["node","server.mjs"]
