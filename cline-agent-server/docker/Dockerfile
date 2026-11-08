# Build context: a dedicated directory containing only runtime/ and service/.
# Use the same Node ABI and compatible libc as the standalone native-module build.
ARG RUNTIME_BASE
FROM ${RUNTIME_BASE}
USER root
COPY runtime/ /poc/runtime/
COPY service/ /poc/service/
WORKDIR /poc/service
ENV CLINE_SERVER_CONFIG=/run/cline-poc.json
USER 1000:1000
EXPOSE 18326
CMD ["node", "/poc/service/src/server.mjs"]
