#!/bin/sh
# External operator component: operates only the dedicated deployment-ops Compose project.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
case "${1:-}" in
 install) docker compose config --quiet; docker compose up -d --no-build --pull never ;;
 stop) docker compose stop ;;
 backup)
  mkdir -p backups; chmod 700 backups; stamp=$(date +%Y%m%d%H%M%S)
  folder="backups/$stamp"; mkdir "$folder"; chmod 700 "$folder"
  docker compose exec -T db pg_dump -U deployment_ops deployment_ops > "$folder/database.sql"
  cp .env "$folder/environment"; chmod 600 "$folder/"*
  docker compose cp executor:/state "$folder/executor-state"
  echo "Backup: $folder; keep a secured copy on another disk."
  ;;
 upgrade)
  docker compose config --quiet
  sh scripts/lifecycle.sh backup
  docker image tag deployment-ops-web:1.0.0 deployment-ops-web:recovery
  docker image tag deployment-ops-executor:1.0.0 deployment-ops-executor:recovery
  docker compose build --pull=false
  docker compose up -d --no-build --pull never
  ok=0
  for i in $(seq 1 30); do
   if docker compose exec -T web java -version >/dev/null 2>&1 && curl -fsS "http://127.0.0.1:${OPS_PORT:-19220}/api/health" >/dev/null; then ok=1; break; fi
   sleep 2
  done
  if [ "$ok" != 1 ]; then sh scripts/lifecycle.sh recover; exit 1; fi
  ;;
 recover)
  docker image tag deployment-ops-web:recovery deployment-ops-web:1.0.0
  docker image tag deployment-ops-executor:recovery deployment-ops-executor:1.0.0
  docker compose up -d --no-build --pull never ;;
 uninstall) docker compose down ;; # Volumes intentionally retained.
 *) echo 'Usage: lifecycle.sh install|stop|backup|upgrade|recover|uninstall'; exit 2 ;;
esac
