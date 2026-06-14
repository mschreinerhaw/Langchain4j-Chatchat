#!/usr/bin/env sh
set -eu

APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$APP_HOME"

APP_NAME="chatchat-mcp-server"
APP_JAR="$APP_HOME/lib/app/$APP_NAME.jar"
PID_FILE="$APP_HOME/logs/$APP_NAME.pid"
STDOUT_LOG="$APP_HOME/logs/$APP_NAME.out"
CONFIG_DIR="$APP_HOME/config/"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi

mkdir -p "$APP_HOME/logs" "$APP_HOME/lib/drivers"

. "$APP_HOME/bin/load-env.sh"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"

if [ -f "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE")"
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    echo "$APP_NAME is already running, pid=$PID"
    exit 0
  fi
  rm -f "$PID_FILE"
fi

if [ ! -f "$APP_JAR" ]; then
  echo "Application jar not found: $APP_JAR" >&2
  exit 1
fi

nohup "$JAVA_CMD" ${JAVA_OPTS:-} -jar "$APP_JAR" \
  --spring.config.additional-location="optional:file:$CONFIG_DIR" \
  ${APP_ARGS:-} >> "$STDOUT_LOG" 2>&1 &

PID="$!"
echo "$PID" > "$PID_FILE"

sleep 1
if kill -0 "$PID" 2>/dev/null; then
  echo "$APP_NAME started, pid=$PID"
  echo "stdout log: $STDOUT_LOG"
else
  rm -f "$PID_FILE"
  echo "$APP_NAME failed to start. Check $STDOUT_LOG" >&2
  exit 1
fi
