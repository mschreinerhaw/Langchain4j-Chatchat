#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

LIB_DIR="$APP_HOME/lib"
CONFIG_DIR="$APP_HOME/config"
CONFIG_FILE="$APP_HOME/config/application.yml"
LOG_DIR="$APP_HOME/logs"
RUN_DIR="$APP_HOME/run"
PID_FILE="$RUN_DIR/chatchat.pid"
CONSOLE_LOG="$LOG_DIR/console.out"

JAVA_CMD="${JAVA_HOME:-}/bin/java"
if [[ ! -x "$JAVA_CMD" ]]; then
  JAVA_CMD="java"
fi

if [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE")"
  if kill -0 "$PID" >/dev/null 2>&1; then
    echo "ChatChat is already running. PID=$PID"
    exit 0
  fi
  rm -f "$PID_FILE"
fi

mkdir -p "$LOG_DIR" "$RUN_DIR"

JAR_FILE="$(ls "$LIB_DIR"/chatchat-api-*.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "$JAR_FILE" ]]; then
  JAR_FILE="$(ls "$LIB_DIR"/*.jar 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "$JAR_FILE" ]]; then
  echo "No jar found in $LIB_DIR"
  exit 1
fi

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Missing config file: $CONFIG_FILE"
  exit 1
fi

JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"
APP_ARGS="${APP_ARGS:-}"

echo "Starting ChatChat..."
echo "JAVA_CMD=$JAVA_CMD"
echo "JAR_FILE=$JAR_FILE"
echo "CONFIG_DIR=$CONFIG_DIR"

# shellcheck disable=SC2086
nohup "$JAVA_CMD" $JAVA_OPTS -jar "$JAR_FILE" --spring.config.additional-location="optional:file:$CONFIG_DIR/" $APP_ARGS >>"$CONSOLE_LOG" 2>&1 &
PID=$!
echo "$PID" >"$PID_FILE"

sleep 1
if kill -0 "$PID" >/dev/null 2>&1; then
  echo "ChatChat started. PID=$PID"
  echo "Logs: $CONSOLE_LOG"
else
  echo "ChatChat failed to start. Check logs: $CONSOLE_LOG"
  exit 1
fi
