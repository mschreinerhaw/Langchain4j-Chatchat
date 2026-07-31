#!/usr/bin/env sh

load_jvm_options() {
  ENV_FILE="$1"
  CR=$(printf '\r')
  if [ ! -f "$ENV_FILE" ]; then
    return 0
  fi

  while IFS= read -r LINE || [ -n "$LINE" ]; do
    LINE=${LINE%"$CR"}
    case "$LINE" in
      ''|\#*) continue ;;
      JAVA_OPTS=*) export JAVA_OPTS="${LINE#JAVA_OPTS=}" ;;
    esac
  done < "$ENV_FILE"
}

load_jvm_options "$APP_HOME/config/env.properties"
