#!/usr/bin/env bash
# Offline patch for an existing 1.2.0-20261022 Linux/amd64 installation.
set -euo pipefail
release=/opt/chatchat-releases/1.2.0-20261022-python-default
cd "$release"
if test -e evidence/deployment.json; then
  echo 'This patch has already been deployed; inspect evidence before any rollback/redeployment.' >&2
  exit 1
fi
sha256sum -c PATCH-SHA256SUMS
test -f /opt/chatchat-releases/1.2.0-20261022/scripts/release-product.py
mkdir -p delta-base jars evidence
source_container=
cleanup() {
  if test -n "$source_container"; then docker rm "$source_container" >/dev/null; fi
}
trap cleanup EXIT
for service in api mcp; do
  source_container=$(docker create "runtime-os-$service:1.2.0-20261022")
  docker cp "$source_container:/srv/app/app.jar" "delta-base/$service.jar"
  docker rm "$source_container" >/dev/null
  source_container=
  python3 scripts/jar-delta.py apply "delta-base/$service.jar" "$service.patch.zip" "jars/$service.jar"
done
revision=fa94182760197464d9c2ea3c844a95e2c2a0bc95
python3 deploy.py build "$revision"
python3 deploy.py deploy "$revision"
