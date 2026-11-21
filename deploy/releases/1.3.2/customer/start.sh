#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
export CUSTOMER_FRONTEND_SECCOMP="$here/../../../../frontend-resource-hub/seccomp_profile.json"
env_file=/opt/runtime-os-docker-stage/config/release.env
compose=(docker compose -p runtime-os-source-production --env-file "$env_file" -f "$here/docker-compose.yml")
if grep -qx 'CUSTOMER_NATIVE_ENABLED=true' "$env_file"; then
  compose+=(-f "$here/native.compose.yml" -f "$here/../../../docker/production/compose.native-daemon.yaml")
fi
"${compose[@]}" config --quiet
"${compose[@]}" up -d --wait --wait-timeout 360 --no-build --pull never
if grep -qx 'CUSTOMER_NATIVE_ENABLED=true' "$env_file"; then
  curl --noproxy '*' --max-time 5 -fsS --unix-socket /opt/runtime-os/p18-d736e9f/executor.sock http://localhost/health/live
  gateway=$(docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}')
  curl --noproxy '*' --max-time 5 -fsS "http://$gateway:18360/health/live"
fi
