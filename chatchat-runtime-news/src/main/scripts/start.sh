#!/usr/bin/env sh
set -eu

APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APP_NAME="chatchat-runtime-news"
APP_JAR="$APP_HOME/lib/$APP_NAME.jar"
PID_FILE="$APP_HOME/run/$APP_NAME.pid"
APP_LOG="$APP_HOME/logs/$APP_NAME.log"
STARTUP_LOG="$APP_HOME/logs/$APP_NAME-startup.log"
LOG_CONFIG="$APP_HOME/config/logback-spring.xml"
CONFIG_DIR="$APP_HOME/config/"
cd "$APP_HOME"
mkdir -p "$APP_HOME/data" "$APP_HOME/logs/archive" "$APP_HOME/run"
. "$APP_HOME/bin/load-env.sh"

JAVA_CMD="java"
[ -n "${JAVA_HOME:-}" ] && JAVA_CMD="$JAVA_HOME/bin/java"
if ! "$JAVA_CMD" -version >/dev/null 2>&1; then echo "Java is unavailable: $JAVA_CMD" >&2; exit 1; fi
if [ ! -f "$APP_JAR" ]; then echo "Application jar not found: $APP_JAR" >&2; exit 1; fi
if [ ! -f "$LOG_CONFIG" ]; then echo "Logback configuration not found: $LOG_CONFIG" >&2; exit 1; fi

if [ -f "$PID_FILE" ]; then
  PID=$(cat "$PID_FILE")
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then echo "$APP_NAME is already running, pid=$PID"; exit 0; fi
  rm -f "$PID_FILE"
fi

nohup "$JAVA_CMD" ${JAVA_OPTS:-} -DLOG_DIR="$APP_HOME/logs" -jar "$APP_JAR" \
  --spring.config.additional-location="optional:file:$CONFIG_DIR" \
  --logging.config="file:$LOG_CONFIG" \
  ${APP_ARGS:-} "$@" > "$STARTUP_LOG" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"
sleep 2
if kill -0 "$PID" 2>/dev/null; then
  echo "$APP_NAME started, pid=$PID"
  echo "application log: $APP_LOG"
  echo "startup diagnostics: $STARTUP_LOG"
else
  rm -f "$PID_FILE"
  echo "$APP_NAME failed to start. Check $STARTUP_LOG" >&2
  exit 1
fi
