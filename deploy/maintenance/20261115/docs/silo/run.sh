#!/bin/bash
set -euo pipefail
docker run -d --name silo --restart unless-stopped \
  --network runtime-os-app-stage_control \
  -p 127.0.0.1:9000:9000 -p 127.0.0.1:9001:9001 \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/silo.env \
  --memory 1g --cpus 2 --security-opt no-new-privileges:true \
  --mount type=bind,src=/opt/runtime-os-docker-stage/silo/data,dst=/data \
  --mount type=bind,src=/opt/runtime-os-docker-stage/silo/secrets,dst=/run/secrets,readonly \
  --mount type=bind,src=/opt/runtime-os-docker-stage/config/silo/app-policy.json,dst=/bootstrap/app-policy.json,readonly \
  pgsty/silo:latest server /data --console-address :9001
