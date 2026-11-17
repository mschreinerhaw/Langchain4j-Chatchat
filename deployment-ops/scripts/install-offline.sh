#!/bin/sh
# New environment bootstrap. Docker/Compose are prerequisites; no PG/Java/Python host service required.
set -eu
umask 077
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
command -v docker >/dev/null || { echo 'Install Docker Engine from your offline media first.' >&2; exit 1; }
docker compose version >/dev/null
docker info >/dev/null
if [ "$#" -gt 0 ]; then sh scripts/offline-images.sh import "$1"; fi
platform=$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')
for image in deployment-ops-web:1.0.0 deployment-ops-executor:1.0.0; do
 image_platform=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image") || { echo "Missing offline image: $image" >&2; exit 1; }
 [ "$image_platform" = "$platform" ] || { echo "Image $image is $image_platform; target is $platform. Supply matching offline images." >&2; exit 1; }
done
if [ ! -f .env ]; then
 env_file=$(mktemp .env.bootstrap.XXXXXX)
 trap 'rm -f -- "$env_file"' EXIT
 docker run --rm --pull=never --network none --entrypoint python deployment-ops-executor:1.0.0 -c 'import secrets; print("DB_PASSWORD="+secrets.token_hex(24)); print("EXECUTOR_TOKEN="+secrets.token_hex(32)); print("ADMIN_PASSWORD="+secrets.token_urlsafe(18)); print("OPS_BIND=127.0.0.1"); print("OPS_PORT=19220")' > "$env_file"
 mv "$env_file" .env
 trap - EXIT
 echo 'Generated .env with unique credentials. Review OPS_BIND and read ADMIN_PASSWORD locally before LAN access.'
fi
docker compose config --quiet
docker compose up -d --no-build --pull never
listener=$(docker compose port web 8080)
printf 'Started. Web listener: %s. Credentials: protected .env file. No database server is required.\n' "$listener"
