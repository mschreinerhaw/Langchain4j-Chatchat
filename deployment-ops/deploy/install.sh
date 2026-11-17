#!/bin/sh
set -eu
umask 077
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test -f .env || { echo 'Create .env from .env.example and set independent credentials first.' >&2; exit 1; }
if grep -q 'replace-with-' .env; then echo 'Replace all example credentials before installation.' >&2; exit 1; fi
test "${MANAGER_INSTALL_NEW:-}" = yes || { echo 'New isolated installation only. Set MANAGER_INSTALL_NEW=yes after reviewing the target project.' >&2; exit 1; }
# Refuse adoption or replacement of a pre-existing manager installation.
test -z "$(docker ps -aq --filter label=com.docker.compose.project=deployment-ops)" || { echo 'Existing deployment-ops containers found; use reviewed upgrade procedure.' >&2; exit 1; }
for volume in deployment-ops_ops-data deployment-ops_ops-state deployment-ops_ops-packages; do
  if docker volume inspect "$volume" >/dev/null 2>&1; then echo "Existing volume found: $volume; refusing initialization." >&2; exit 1; fi
done
sha256sum -c SHA256SUMS
python3 verify-images.py --before-load
docker load -i images/manager-images.tar
python3 verify-images.py
docker compose config --quiet
docker compose up -d --no-build --pull never
