#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
env_file=/opt/runtime-os-docker-stage/config/release.env
compose=(docker compose -p runtime-os-source-production --env-file "$env_file" -f "$here/docker-compose.yml")
if grep -qx 'CUSTOMER_NATIVE_ENABLED=true' "$env_file"; then
  compose+=(-f "$here/native.compose.yml" -f "$here/../../../docker/production/compose.native-daemon.yaml")
fi
"${compose[@]}" stop -t 90
