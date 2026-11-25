#!/bin/sh
set -eu
mkdir -p /tmp/gateway-cline/workspace
printf '%s\n' 'Gateway compatibility fixture.' > /tmp/gateway-cline/workspace/README.md
url=http://llm-gateway:18080/v1
key=fixture-gateway-credential
if [ "${CLINE_FIXTURE_TLS:-}" = 1 ]; then
  url=https://llm-gateway:${CLINE_FIXTURE_PORT:-8445}/v1
  key=fixture-cline-model-credential
fi
cat >/tmp/gateway-cline/config.json <<JSON
{
  "sourceRoot": "/opt/cline/runtime",
  "distDir": "/opt/cline/runtime",
  "stateDir": "/tmp/gateway-cline/state",
  "apiToken": "fixture-cline-operator-token",
  "model": "gateway-cline-fixture",
  "baseUrl": "$url",
  "apiKey": "$key",
  "workspaces": {"fixture": "/tmp/gateway-cline/workspace"},
  "host": "0.0.0.0",
  "port": 18329
}
JSON
export CLINE_SERVER_CONFIG=/tmp/gateway-cline/config.json
exec node --use-system-ca /srv/app/src/server.mjs
