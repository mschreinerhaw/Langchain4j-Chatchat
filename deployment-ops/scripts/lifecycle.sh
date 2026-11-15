#!/bin/sh
# Host-owned lifecycle. No dependency on a database service.
set -eu
umask 077
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
case "${1:-}" in
 install) docker compose config --quiet; docker compose up -d --no-build --pull never ;;
 stop) docker compose stop ;;
 backup)
  mkdir -p backups
  stamp=$(date +%Y%m%d%H%M%S); folder="backups/$stamp"; mkdir "$folder"
  # Close the embedded database before copying its files. Resume even if copying fails.
  docker compose stop web executor
  trap 'docker compose start executor web >/dev/null' EXIT
  docker compose cp web:/data "$folder/data"
  docker compose cp executor:/state "$folder/executor-state"
  cp .env "$folder/environment"
  printf '%s\n' 'H2 offline backup; restore only while Web and executor are stopped.' > "$folder/README.txt"
  touch "$folder/COMPLETE"
  docker compose start executor web
  trap - EXIT
  echo "Backup: $folder; store a secured copy on another disk."
  ;;
 upgrade)
  docker compose config --quiet
  sh scripts/lifecycle.sh backup
  docker image tag deployment-ops-web:1.0.0 deployment-ops-web:recovery
  docker image tag deployment-ops-executor:1.0.0 deployment-ops-executor:recovery
  if ! docker compose build --pull=false; then echo 'Build failed; running services retained.' >&2; exit 1; fi
  docker compose up -d --no-build --pull never
  ok=0
  for i in $(seq 1 30); do
   if docker compose exec -T executor python -c 'import json,urllib.request; op=urllib.request.build_opener(urllib.request.ProxyHandler({})); response=op.open("http://web:8080/api/health",timeout=3); assert json.load(response)["status"]=="UP"' >/dev/null 2>&1; then ok=1; break; fi
   sleep 2
  done
  if [ "$ok" != 1 ]; then sh scripts/lifecycle.sh recover; exit 1; fi
  ;;
 recover)
  docker image tag deployment-ops-web:recovery deployment-ops-web:1.0.0
  docker image tag deployment-ops-executor:recovery deployment-ops-executor:1.0.0
  docker compose up -d --no-build --pull never ;;
 uninstall) docker compose down ;; # Never delete data volumes.
 *) echo 'Usage: lifecycle.sh install|stop|backup|upgrade|recover|uninstall'; exit 2 ;;
esac
