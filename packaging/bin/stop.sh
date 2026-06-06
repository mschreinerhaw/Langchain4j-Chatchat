#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_DIR="$APP_HOME/run"
PID_FILE="$RUN_DIR/chatchat.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "No pid file found. ChatChat may not be running."
  exit 0
fi

PID="$(cat "$PID_FILE")"
if ! kill -0 "$PID" >/dev/null 2>&1; then
  echo "Process $PID is not running. Cleaning stale pid file."
  rm -f "$PID_FILE"
  exit 0
fi

echo "Stopping ChatChat PID=$PID"
kill "$PID"

for _ in {1..30}; do
  if ! kill -0 "$PID" >/dev/null 2>&1; then
    rm -f "$PID_FILE"
    echo "ChatChat stopped."
    exit 0
  fi
  sleep 1
done

echo "Graceful stop timed out, force killing PID=$PID"
kill -9 "$PID"
rm -f "$PID_FILE"
echo "ChatChat stopped."
