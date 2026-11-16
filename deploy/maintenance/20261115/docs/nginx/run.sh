#!/bin/bash
set -euo pipefail
docker run -d --name runtime-os-app-stage-gateway-1 --restart unless-stopped \
  --network runtime-os-app-stage_control \
  -p 18100:8080 -p 18101:8090 \
  --user 101:101 --read-only --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/runtime-os-app-stage-gateway-1.env \
  --mount type=bind,src=/opt/runtime-os-docker-stage/config/public-gateway.conf,dst=/etc/nginx/nginx.conf,readonly \
  --mount type=bind,src=/opt/runtime-os/releases/latest-main-20261115/public-ui,dst=/srv/task-lifecycle-ui,readonly \
  --mount type=bind,src=/opt/runtime-os/releases/latest-main-20261115/source/chatchat-api/src/main/resources/coding-monitor,dst=/srv/coding-monitor,readonly \
  nginx:1.28-alpine
