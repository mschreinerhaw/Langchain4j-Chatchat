#!/bin/bash
set -euo pipefail
CONTAINER_NAME=${CONTAINER_NAME:-postgresql18}
if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  echo "Container already exists: $CONTAINER_NAME; inspect it before rebuilding." >&2
  exit 1
fi
test -s /opt/runtime-os/standard-maintenance-20261115/private/rebuild/postgresql18.env
docker volume inspect 998a12dcec518fdb8a7201c81cbd3d507cae16d6e817851638a9d2cbadd3adf5 >/dev/null
test -e /data/postgresql/18/docker
docker \
+  run \
+  -d \
+  --name \
+  "$CONTAINER_NAME" \
+  --restart \
+  unless-stopped \
+  --network \
+  bridge \
+  --env-file \
+  /opt/runtime-os/standard-maintenance-20261115/private/rebuild/postgresql18.env \
+  --shm-size \
+  67108864 \
+  --publish \
+  5432:5432/tcp \
+  --mount \
+  type=volume,src=998a12dcec518fdb8a7201c81cbd3d507cae16d6e817851638a9d2cbadd3adf5,dst=/var/lib/postgresql \
+  --mount \
+  type=bind,src=/data/postgresql/18/docker,dst=/var/lib/postgresql/18/docker \
+  --health-cmd \
+  'pg_isready -U "${POSTGRES_USER}"' \
+  --health-interval \
+  5000000000ns \
+  --health-timeout \
+  5000000000ns \
+  --health-retries \
+  30 \
+  postgres@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42 \
+  postgres
