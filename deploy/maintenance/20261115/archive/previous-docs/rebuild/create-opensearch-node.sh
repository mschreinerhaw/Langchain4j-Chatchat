#!/bin/bash
set -euo pipefail
CONTAINER_NAME=${CONTAINER_NAME:-opensearch-node}
if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
  echo "Container already exists: $CONTAINER_NAME; inspect it before rebuilding." >&2
  exit 1
fi
test -s /opt/runtime-os/standard-maintenance-20261115/private/rebuild/opensearch-node.env
test -e /data/opensearch/data
test -e /data/opensearch/logs
test -e /data/opensearch/plugins
test -s /opt/runtime-os/standard-maintenance-20261115/private/rebuild/opensearch-config/opensearch.yml
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
+  /opt/runtime-os/standard-maintenance-20261115/private/rebuild/opensearch-node.env \
+  --user \
+  1000 \
+  --workdir \
+  /usr/share/opensearch \
+  --shm-size \
+  67108864 \
+  --publish \
+  9200:9200/tcp \
+  --publish \
+  9600:9600/tcp \
+  --mount \
+  type=bind,src=/data/opensearch/data,dst=/usr/share/opensearch/data \
+  --mount \
+  type=bind,src=/data/opensearch/logs,dst=/usr/share/opensearch/logs \
+  --mount \
+  type=bind,src=/data/opensearch/plugins,dst=/usr/share/opensearch/plugins \
+  --mount \
+  type=bind,src=/opt/runtime-os/standard-maintenance-20261115/private/rebuild/opensearch-config,dst=/usr/share/opensearch/config \
+  opensearchproject/opensearch@sha256:72fe2fc84be8295906b8efca020b46c58ed45c8da60cd9b8b49e1991e38e89a4 \
+  opensearch
