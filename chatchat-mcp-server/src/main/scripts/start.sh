#!/usr/bin/env sh
set -eu

APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$APP_HOME"

mkdir -p logs lib

exec java ${JAVA_OPTS:-} -jar chatchat-mcp-server.jar --spring.config.additional-location=optional:file:./config/
