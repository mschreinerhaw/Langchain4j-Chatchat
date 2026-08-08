#!/usr/bin/env sh
set -eu

# ChatChat License Center deployment controller.
# Usage: ./chatchat-license.sh start|stop|restart|status [Spring Boot arguments]

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_HOME=${CHATCHAT_LICENSE_HOME:-$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)}
APP_NAME=chatchat-license
RUN_DIR="$APP_HOME/run"
LOG_DIR="$APP_HOME/logs"
DATA_DIR="$APP_HOME/data/license-center"
PID_FILE="$RUN_DIR/$APP_NAME.pid"
STDOUT_LOG="$LOG_DIR/$APP_NAME.out"
ENV_FILE=${CHATCHAT_LICENSE_ENV_FILE:-$APP_HOME/config/license-center.env}

log() { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

load_environment() {
  if [ -f "$ENV_FILE" ]; then
    # The environment file is managed by the deployer and must not be writable by untrusted users.
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
  fi
}

resolve_java() {
  if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
  else
    JAVA_CMD=java
  fi
  command -v "$JAVA_CMD" >/dev/null 2>&1 || fail "Java not found. Install Java 17+ or configure JAVA_HOME."

  JAVA_MAJOR=$($JAVA_CMD -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
  [ -n "$JAVA_MAJOR" ] || fail "Cannot determine Java version from: $JAVA_CMD"
  [ "$JAVA_MAJOR" -ge 17 ] || fail "Java 17+ is required; current major version is $JAVA_MAJOR."
}

resolve_jar() {
  if [ -n "${CHATCHAT_LICENSE_JAR:-}" ]; then
    APP_JAR=$CHATCHAT_LICENSE_JAR
  elif [ -f "$APP_HOME/chatchat-license.jar" ]; then
    APP_JAR="$APP_HOME/chatchat-license.jar"
  elif [ -f "$APP_HOME/lib/chatchat-license.jar" ]; then
    APP_JAR="$APP_HOME/lib/chatchat-license.jar"
  else
    APP_JAR=
    for candidate in "$APP_HOME"/chatchat-license-*-server.jar "$APP_HOME"/lib/chatchat-license-*-server.jar; do
      if [ -f "$candidate" ]; then
        APP_JAR=$candidate
        break
      fi
    done
  fi
  [ -n "${APP_JAR:-}" ] && [ -f "$APP_JAR" ] || fail "Executable jar not found. Deploy it as $APP_HOME/chatchat-license.jar or set CHATCHAT_LICENSE_JAR."
}

strong_password() {
  value=$1
  [ "${#value}" -ge 20 ] || return 1
  case "$value" in *[A-Z]*) ;; *) return 1 ;; esac
  case "$value" in *[a-z]*) ;; *) return 1 ;; esac
  case "$value" in *[0-9]*) ;; *) return 1 ;; esac
  case "$value" in *[!A-Za-z0-9]*) ;; *) return 1 ;; esac
  return 0
}

ensure_database_password() {
  if [ -n "${CHATCHAT_LICENSE_DB_PASSWORD:-}" ]; then
    strong_password "$CHATCHAT_LICENSE_DB_PASSWORD" \
      || fail "CHATCHAT_LICENSE_DB_PASSWORD must be 20+ characters with upper/lowercase letters, digits and symbols."
    return 0
  fi
  [ -f "$ENV_FILE" ] || fail "Create $ENV_FILE before generating the H2 database password."
  command -v openssl >/dev/null 2>&1 || fail "Configure a complex CHATCHAT_LICENSE_DB_PASSWORD, or install openssl for automatic generation."
  GENERATED_DB_PASSWORD=
  ATTEMPTS=0
  while ! strong_password "$GENERATED_DB_PASSWORD" && [ "$ATTEMPTS" -lt 10 ]; do
    GENERATED_DB_PASSWORD=$(openssl rand -base64 36 | tr -d '\r\n')
    ATTEMPTS=$((ATTEMPTS + 1))
  done
  strong_password "$GENERATED_DB_PASSWORD" || fail "Unable to generate a compliant H2 database password."
  printf '\n# Automatically generated for this deployment. Do not share or rotate without migrating the H2 user.\nCHATCHAT_LICENSE_DB_PASSWORD=%s\n' \
    "$GENERATED_DB_PASSWORD" >> "$ENV_FILE"
  chmod 600 "$ENV_FILE" 2>/dev/null || true
  export CHATCHAT_LICENSE_DB_PASSWORD=$GENERATED_DB_PASSWORD
  log "Generated a unique H2 database password in $ENV_FILE"
}

read_pid() {
  PID=
  if [ -f "$PID_FILE" ]; then
    PID=$(sed -n '1p' "$PID_FILE" | tr -d '[:space:]')
    case "$PID" in
      ''|*[!0-9]*) PID= ;;
    esac
  fi
}

is_running() {
  read_pid
  [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null
}

start_service() {
  load_environment
  resolve_java
  resolve_jar

  [ -n "${CHATCHAT_LICENSE_CENTER_PASSWORD:-}" ] || fail "Set CHATCHAT_LICENSE_CENTER_PASSWORD in $ENV_FILE before starting."
  ensure_database_password
  mkdir -p "$RUN_DIR" "$LOG_DIR" "$DATA_DIR"

  if is_running; then
    log "$APP_NAME is already running, pid=$PID"
    return 0
  fi
  [ ! -f "$PID_FILE" ] || rm -f "$PID_FILE"

  DEFAULT_JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$LOG_DIR -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
  JAVA_OPTIONS=${JAVA_OPTS:-$DEFAULT_JAVA_OPTS}

  cd "$APP_HOME"
  # JAVA_OPTIONS intentionally uses shell word splitting so multiple JVM flags are accepted.
  # shellcheck disable=SC2086
  nohup "$JAVA_CMD" $JAVA_OPTIONS -jar "$APP_JAR" "$@" >> "$STDOUT_LOG" 2>&1 &
  PID=$!
  printf '%s\n' "$PID" > "$PID_FILE"

  WAITED=0
  while [ "$WAITED" -lt 10 ]; do
    if ! kill -0 "$PID" 2>/dev/null; then
      rm -f "$PID_FILE"
      fail "$APP_NAME failed to start. Check $STDOUT_LOG"
    fi
    sleep 1
    WAITED=$((WAITED + 1))
  done

  log "$APP_NAME started, pid=$PID"
  log "URL: http://127.0.0.1:${CHATCHAT_LICENSE_CENTER_PORT:-8092}/"
  log "Log: $STDOUT_LOG"
}

stop_service() {
  if ! is_running; then
    [ ! -f "$PID_FILE" ] || rm -f "$PID_FILE"
    log "$APP_NAME is not running"
    return 0
  fi

  log "Stopping $APP_NAME, pid=$PID"
  kill "$PID"
  WAITED=0
  while kill -0 "$PID" 2>/dev/null && [ "$WAITED" -lt 30 ]; do
    sleep 1
    WAITED=$((WAITED + 1))
  done
  if kill -0 "$PID" 2>/dev/null; then
    fail "$APP_NAME did not stop within 30 seconds; inspect pid=$PID before taking further action."
  fi
  rm -f "$PID_FILE"
  log "$APP_NAME stopped"
}

status_service() {
  if is_running; then
    log "$APP_NAME is running, pid=$PID"
    return 0
  fi
  log "$APP_NAME is not running"
  return 3
}

COMMAND=${1:-start}
[ "$#" -eq 0 ] || shift
case "$COMMAND" in
  start) start_service "$@" ;;
  stop) stop_service ;;
  restart) stop_service; start_service "$@" ;;
  status) status_service ;;
  *) fail "Usage: $0 {start|stop|restart|status} [Spring Boot arguments]" ;;
esac
