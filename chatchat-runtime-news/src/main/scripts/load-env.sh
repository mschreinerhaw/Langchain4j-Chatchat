#!/usr/bin/env sh

load_env_file() {
  ENV_FILE="$1"
  CR=$(printf '\r')
  [ -f "$ENV_FILE" ] || return 0
  while IFS= read -r LINE || [ -n "$LINE" ]; do
    LINE=${LINE%"$CR"}
    case "$LINE" in ''|\#*) continue ;; export\ *) LINE=${LINE#export } ;; esac
    case "$LINE" in *=*) ;; *) continue ;; esac
    KEY=${LINE%%=*}
    VALUE=${LINE#*=}
    KEY=$(printf '%s' "$KEY" | tr -d '[:space:]')
    case "$KEY" in ''|[0-9]*|*[!A-Za-z0-9_]*) echo "Skip invalid env key: $KEY" >&2; continue ;; esac
    case "$VALUE" in \"*\") VALUE=${VALUE#\"}; VALUE=${VALUE%\"} ;; \'*\') VALUE=${VALUE#\'}; VALUE=${VALUE%\'} ;; esac
    export "$KEY=$VALUE"
  done < "$ENV_FILE"
}

load_env_file "$APP_HOME/config/env.properties"
load_env_file "$APP_HOME/config/env.local"
