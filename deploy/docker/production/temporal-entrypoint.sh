#!/usr/bin/env bash
set -euo pipefail
export POSTGRES_PWD="$(tr -d '\r\n' </run/secrets/temporal_database_password)"
test -n "$POSTGRES_PWD"
case "${1:?schema or server}" in
  schema) exec /etc/temporal/auto-setup.sh ;;
  server) exec /etc/temporal/entrypoint.sh ;;
  *) exit 64 ;;
esac
