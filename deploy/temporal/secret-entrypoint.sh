#!/usr/bin/env bash
set -euo pipefail
# auto-setup reads POSTGRES_PWD, not a made-up POSTGRES_PWD_FILE variable.
export POSTGRES_PWD="$(cat /run/secrets/temporal_database_password)"
test -n "$POSTGRES_PWD"
exec /etc/temporal/entrypoint.sh autosetup
