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

require_bind() {
  local container=$1 source=$2 target=$3
  if [[ ! -d $source ]] ||
     ! docker inspect -f '{{range .Mounts}}{{if eq .Type "bind"}}{{println .Source .Destination}}{{end}}{{end}}' "$container" |
       grep -Fx "$source $target" >/dev/null; then
    printf '%s requires the existing mount %s -> %s.\n' "$container" "$source" "$target" >&2
    return 1
  fi
}

"${compose[@]}" config --quiet
require_bind postgresql18 /data/postgresql/18/docker /var/lib/postgresql/18/docker
require_bind silo /opt/runtime-os-docker-stage/silo/data /data
require_bind opensearch-node /data/opensearch/data /usr/share/opensearch/data
require_bind opensearch-node /data/opensearch/logs /usr/share/opensearch/logs
require_bind opensearch-node /data/opensearch/plugins /usr/share/opensearch/plugins
test -s /data/postgresql/18/docker/PG_VERSION
docker volume inspect runtime-os-source-production_temporal-postgresql-data runtime-os-source-production_redis-data >/dev/null
docker start postgresql18 silo opensearch-node
postgres_gateway=$(docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}')
wait_for 'PostgreSQL via Docker gateway' timeout 5 docker exec postgresql18 pg_isready -h "$postgres_gateway" -t 3
wait_for Silo curl --noproxy '*' -fsS --max-time 4 http://127.0.0.1:9000/minio/health/ready

"${compose[@]}" up -d --no-recreate --no-build --pull never
wait_for 'Coding executor' curl --noproxy '*' -fsS --max-time 4 \
  --unix-socket /opt/runtime-os/p18-d736e9f/executor.sock http://localhost/health/live
wait_for 'Coding Broker' curl --noproxy '*' -fsS --max-time 4 "http://$postgres_gateway:18360/health/live"
wait_for API api_ready
wait_for 'PostgreSQL from API network' timeout 5 "${compose[@]}" exec -T api bash -c 'exec 3<>/dev/tcp/host.docker.internal/5432'
"${compose[@]}" exec -T gateway nginx -s reload
wait_for Gateway curl --noproxy '*' -fsS --max-time 4 http://127.0.0.1:18100/version.json
"${compose[@]}" ps -a
