#!/bin/bash
set -euo pipefail
docker run -d --name opensearch-node --restart unless-stopped \
  -p 9200:9200 -p 9600:9600 \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/opensearch-node.env \
  --mount type=bind,src=/data/opensearch/data,dst=/usr/share/opensearch/data \
  --mount type=bind,src=/data/opensearch/logs,dst=/usr/share/opensearch/logs \
  --mount type=bind,src=/data/opensearch/plugins,dst=/usr/share/opensearch/plugins \
  --mount type=bind,src=/data/opensearch/config,dst=/usr/share/opensearch/config \
  opensearchproject/opensearch:2.19.1
