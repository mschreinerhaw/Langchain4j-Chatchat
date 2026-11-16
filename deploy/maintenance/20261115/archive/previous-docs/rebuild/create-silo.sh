#!/bin/bash
set -euo pipefail
CONTAINER_NAME=${CONTAINER_NAME:-silo}
if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  echo "Container already exists: $CONTAINER_NAME; inspect it before rebuilding." >&2
  exit 1
fi
test -s /opt/runtime-os/standard-maintenance-20261115/private/rebuild/silo.env
test -e /opt/runtime-os-docker-stage/config/silo/app-policy.json
test -e /opt/runtime-os-docker-stage/silo/data
test -e /opt/runtime-os-docker-stage/silo/secrets
docker \
+  run \
+  -d \
+  --name \
+  "$CONTAINER_NAME" \
+  --restart \
+  unless-stopped \
+  --network \
+  runtime-os-app-stage_control \
+  --env-file \
+  /opt/runtime-os/standard-maintenance-20261115/private/rebuild/silo.env \
+  --workdir \
+  / \
+  --memory \
+  1073741824 \
+  --cpus \
+  2.0 \
+  --shm-size \
+  67108864 \
+  --security-opt \
+  no-new-privileges:true \
+  --publish \
+  127.0.0.1:9000:9000/tcp \
+  --publish \
+  127.0.0.1:9001:9001/tcp \
+  --mount \
+  type=bind,src=/opt/runtime-os-docker-stage/config/silo/app-policy.json,dst=/bootstrap/app-policy.json,readonly \
+  --mount \
+  type=bind,src=/opt/runtime-os-docker-stage/silo/data,dst=/data \
+  --mount \
+  type=bind,src=/opt/runtime-os-docker-stage/silo/secrets,dst=/run/secrets,readonly \
+  pgsty/silo@sha256:08ce1505b5eacefbad1ca07159b242b9ce045a11c2817e3bc257b25f77bbc02d \
+  server \
+  /data \
+  --console-address \
+  :9001
