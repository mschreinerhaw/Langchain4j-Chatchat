#!/usr/bin/env sh
set -eu
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APP_NAME="chatchat-runtime-news"
PID_FILE="$APP_HOME/run/$APP_NAME.pid"
if [ ! -f "$PID_FILE" ]; then echo "$APP_NAME is stopped"; exit 3; fi
PID=$(cat "$PID_FILE")
if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then echo "$APP_NAME is running, pid=$PID"; exit 0; fi
echo "$APP_NAME is stopped; stale pid file: $PID_FILE"
exit 1
