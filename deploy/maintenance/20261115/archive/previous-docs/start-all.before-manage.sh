#!/bin/bash
set -euo pipefail
ROOT=/opt/runtime-os/standard-maintenance-20261115
STAGE=/opt/runtime-os-docker-stage
NATIVE=/opt/runtime-os/native-temporal/rc-20261112/compose.private.json
docker start opensearch-node silo redis >/dev/null
docker compose -f "$ROOT/infrastructure.compose.json" up -d --wait --wait-timeout 90
# Start existing business dependencies without recreating any infrastructure.
docker compose --profile '*' --env-file "$STAGE/config/deployment.env" -f "$STAGE/compose.yaml" up -d --no-deps news mcp
docker compose -p frontend-resource-hub -f /opt/frontend-resource-hub/1.0.0/compose.yaml up -d --no-build --wait --wait-timeout 120
docker compose -p runtime-native-temporal-candidate -f "$NATIVE" up -d --no-deps temporal-control-api
docker compose -p runtime-native-temporal-candidate -f "$NATIVE" up -d --no-deps --force-recreate executor1 executor2 temporal recovery1 recovery2 node1 node2 studio-gateway worker1 worker2
docker compose -p runtime-os-stage-maintenance --env-file "$STAGE/config/deployment.env" -f "$STAGE/compose.gateway-ui.json" up -d --no-deps --force-recreate gateway
docker exec runtime-os-app-stage-gateway-1 nginx -t
