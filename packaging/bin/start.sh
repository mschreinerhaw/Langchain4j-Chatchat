#!/usr/bin/env sh
set -eu

APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APP_NAME="chatchat"
APP_JAR="$APP_HOME/lib/app/$APP_NAME.jar"
PID_FILE="$APP_HOME/run/$APP_NAME.pid"
STDOUT_LOG="$APP_HOME/logs/$APP_NAME.out"
CONFIG_DIR="$APP_HOME/config/"
LIB_DIR="$APP_HOME/lib"
EXT_LIB_DIR="$LIB_DIR/ext"
DRIVERS_DIR="$LIB_DIR/drivers"
LAUNCHER_CLASS="org.springframework.boot.loader.launch.PropertiesLauncher"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi

mkdir -p "$APP_HOME/logs" "$APP_HOME/run" "$APP_HOME/data" "$EXT_LIB_DIR" "$DRIVERS_DIR"

. "$APP_HOME/bin/load-env.sh"

REMAINING_ARGS=""
while [ "$#" -gt 0 ]; do
  REMAINING_ARGS="${REMAINING_ARGS}${REMAINING_ARGS:+ }$1"
  shift
done

JAVA_OPTIONS="${JAVA_OPTS:-}"
case "${CHATCHAT_OPENSEARCH_INSECURE_SSL:-false}" in
  true|TRUE|True|1|yes|YES|Yes)
    case " $JAVA_OPTIONS " in
      *" -Djdk.internal.httpclient.disableHostnameVerification=true "*) ;;
      *) JAVA_OPTIONS="${JAVA_OPTIONS}${JAVA_OPTIONS:+ }-Djdk.internal.httpclient.disableHostnameVerification=true" ;;
    esac
    ;;
esac

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

LOADER_PATH="$EXT_LIB_DIR,$DRIVERS_DIR"
for JAR in "$LIB_DIR"/*.jar; do
  if [ -f "$JAR" ]; then
    LOADER_PATH="$JAR,$LOADER_PATH"
  fi
done

nohup "$JAVA_CMD" ${JAVA_OPTIONS:-} "-Dloader.path=$LOADER_PATH" -cp "$APP_JAR" "$LAUNCHER_CLASS" \
  --debug=false \
  --spring.config.additional-location="optional:file:$CONFIG_DIR" \
  ${APP_ARGS:-} ${REMAINING_ARGS:-} >> "$STDOUT_LOG" 2>&1 &

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
