#!/bin/bash
set -euo pipefail
docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
docker exec postgresql18 pg_isready -U postgres
docker exec runtime-os-app-stage-gateway-1 nginx -t
curl -fsS http://127.0.0.1:18100/version.json
