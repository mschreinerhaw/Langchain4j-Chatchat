#!/usr/bin/env bash
set -euo pipefail

compose_file=${RUNTIME_OS_COMPOSE_FILE:-/opt/runtime-os/compose.yaml}
compose=(docker compose -f "$compose_file")

exec 9>"$compose_file.lock"
flock -n 9

wait_for() {
  local service=$1
  shift
  for ((attempt=0; attempt<75; attempt++)); do
    if "$@" >/dev/null 2>&1; then
      printf '%s ready.\n' "$service"
      return
    fi
    sleep 2
  done
  printf '%s did not become ready.\n' "$service" >&2
  return 1
}

api_ready() {
  local api
  api=$("${compose[@]}" ps -q api)
  [[ -n $api && $(docker inspect -f '{{.State.Health.Status}}' "$api") == healthy ]]
}

"${compose[@]}" config --quiet
docker start postgresql18 silo opensearch-node
postgres_gateway=$(docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}')
wait_for 'PostgreSQL via Docker gateway' timeout 5 docker exec postgresql18 pg_isready -h "$postgres_gateway" -t 3
wait_for Silo curl --noproxy '*' -fsS --max-time 4 http://127.0.0.1:9000/minio/health/ready

"${compose[@]}" up -d --no-recreate --no-build --pull never
wait_for API api_ready
wait_for 'PostgreSQL from API network' timeout 5 "${compose[@]}" exec -T api bash -c 'exec 3<>/dev/tcp/host.docker.internal/5432'
"${compose[@]}" exec -T gateway nginx -s reload
wait_for Gateway curl --noproxy '*' -fsS --max-time 4 http://127.0.0.1:18100/version.json
"${compose[@]}" ps -a
