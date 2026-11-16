#!/bin/bash
set -euo pipefail
docker run -d --name redis --restart unless-stopped \
  -p 6379:6379 \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/redis.env \
  --mount type=volume,src=387a5d69f2b1a0f25fc2587933aad767e395ad034031df9bd0e53e4add49fc5a,dst=/data \
  --mount type=bind,src=/opt/runtime-os/standard-maintenance-20261115/private/image-env/redis.conf,dst=/usr/local/etc/redis/redis.conf,readonly \
  redis:7.4-alpine redis-server /usr/local/etc/redis/redis.conf
