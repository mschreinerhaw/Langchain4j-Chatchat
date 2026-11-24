#!/bin/sh
set -eu
mkdir -p /tmp/gateway-cline/workspace
printf '%s\n' 'Gateway compatibility fixture.' > /tmp/gateway-cline/workspace/README.md
cat >/tmp/gateway-cline/config.json <<'JSON'
{
  "sourceRoot": "/opt/cline/runtime",
  "distDir": "/opt/cline/runtime",
  "stateDir": "/tmp/gateway-cline/state",
  "apiToken": "fixture-cline-operator-token",
  "model": "gateway-cline-fixture",
  "baseUrl": "http://llm-gateway:18080/v1",
  "apiKey": "fixture-gateway-credential",
  "workspaces": {"fixture": "/tmp/gateway-cline/workspace"},
  "host": "0.0.0.0",
  "port": 18329
}
JSON
export CLINE_SERVER_CONFIG=/tmp/gateway-cline/config.json
exec node /srv/app/src/server.mjs
