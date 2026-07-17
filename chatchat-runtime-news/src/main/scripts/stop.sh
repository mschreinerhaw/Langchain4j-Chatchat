#!/usr/bin/env sh
set -eu
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APP_NAME="chatchat-runtime-news"
PID_FILE="$APP_HOME/run/$APP_NAME.pid"
[ -f "$PID_FILE" ] || { echo "$APP_NAME is not running"; exit 0; }
PID=$(cat "$PID_FILE")
if [ -z "$PID" ] || ! kill -0 "$PID" 2>/dev/null; then rm -f "$PID_FILE"; echo "$APP_NAME is not running; stale pid removed"; exit 0; fi
kill "$PID"
COUNT=0
while kill -0 "$PID" 2>/dev/null; do
  COUNT=$((COUNT + 1))
  if [ "$COUNT" -ge "${CHATCHAT_STOP_TIMEOUT_SECONDS:-30}" ]; then kill -9 "$PID" 2>/dev/null || true; break; fi
  sleep 1
done
rm -f "$PID_FILE"
echo "$APP_NAME stopped"
