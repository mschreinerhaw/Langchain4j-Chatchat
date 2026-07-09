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

DB_MODE="${CHAT_DATABASE_MODE:-}"
SEARCH_ENGINE="${CHATCHAT_SEARCH_ENGINE:-}"
REMAINING_ARGS=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --database=*)
      DB_MODE="${1#--database=}"
      ;;
    --database)
      shift
      if [ "$#" -eq 0 ]; then
        echo "--database requires h2 or mysql" >&2
        exit 1
      fi
      DB_MODE="$1"
      ;;
    --mysql)
      DB_MODE="mysql"
      ;;
    --h2)
      DB_MODE="h2"
      ;;
    --search-engine=*)
      SEARCH_ENGINE="${1#--search-engine=}"
      ;;
    --search-engine)
      shift
      if [ "$#" -eq 0 ]; then
        echo "--search-engine requires lucene or opensearch" >&2
        exit 1
      fi
      SEARCH_ENGINE="$1"
      ;;
    --lucene)
      SEARCH_ENGINE="lucene"
      ;;
    --opensearch)
      SEARCH_ENGINE="opensearch"
      ;;
    *)
      REMAINING_ARGS="${REMAINING_ARGS}${REMAINING_ARGS:+ }$1"
      ;;
  esac
  shift
done

if [ -n "$DB_MODE" ]; then
  DB_MODE_NORMALIZED="$(printf '%s' "$DB_MODE" | tr '[:upper:]' '[:lower:]')"
  case "$DB_MODE_NORMALIZED" in
    mysql) export SPRING_PROFILES_ACTIVE="prod,mysql" ;;
    h2) export SPRING_PROFILES_ACTIVE="prod" ;;
    *)
      echo "Unsupported database mode: $DB_MODE. Use h2 or mysql." >&2
      exit 1
      ;;
  esac
else
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
fi

if [ -n "$SEARCH_ENGINE" ]; then
  SEARCH_ENGINE_NORMALIZED="$(printf '%s' "$SEARCH_ENGINE" | tr '[:upper:]' '[:lower:]')"
  case "$SEARCH_ENGINE_NORMALIZED" in
    lucene) export CHATCHAT_SEARCH_ENGINE="lucene" ;;
    opensearch|open-search) export CHATCHAT_SEARCH_ENGINE="opensearch" ;;
    *)
      echo "Unsupported search engine: $SEARCH_ENGINE. Use lucene or opensearch." >&2
      exit 1
      ;;
  esac
fi

JAVA_OPTIONS="${JAVA_OPTS:-}"
if [ "${CHATCHAT_SEARCH_ENGINE:-}" = "opensearch" ]; then
  case " $JAVA_OPTIONS " in
    *" -Djdk.internal.httpclient.disableHostnameVerification=true "*) ;;
    *) JAVA_OPTIONS="${JAVA_OPTIONS}${JAVA_OPTIONS:+ }-Djdk.internal.httpclient.disableHostnameVerification=true" ;;
  esac
fi

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
