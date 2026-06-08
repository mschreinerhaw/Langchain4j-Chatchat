#!/usr/bin/env sh
set -eu

APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APP_NAME="chatchat"
PID_FILE="$APP_HOME/run/$APP_NAME.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "$APP_NAME is not running: pid file not found"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if [ -z "$PID" ] || ! kill -0 "$PID" 2>/dev/null; then
  rm -f "$PID_FILE"
  echo "$APP_NAME is not running: stale pid file removed"
  exit 0
fi

kill "$PID"

COUNT=0
while kill -0 "$PID" 2>/dev/null; do
  COUNT=$((COUNT + 1))
  if [ "$COUNT" -ge 30 ]; then
    echo "$APP_NAME did not stop gracefully, forcing kill"
    kill -9 "$PID" 2>/dev/null || true
    break
  fi
  sleep 1
done

rm -f "$PID_FILE"
echo "$APP_NAME stopped"
