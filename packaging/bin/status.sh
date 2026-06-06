#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_DIR="$APP_HOME/run"
PID_FILE="$RUN_DIR/chatchat.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "ChatChat is not running."
  exit 1
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" >/dev/null 2>&1; then
  echo "ChatChat is running. PID=$PID"
  exit 0
fi

echo "ChatChat is not running (stale pid file: $PID_FILE)."
exit 1
