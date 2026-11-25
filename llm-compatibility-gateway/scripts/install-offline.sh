#!/bin/sh
set -eu
cd "$(dirname "$0")"
sha256sum -c llm-compatibility-gateway.tar.sha256
if [ ! -f .env ]; then
  echo 'Create .env from .env.example before installation' >&2
  exit 1
fi
docker image load --input llm-compatibility-gateway.tar
docker compose up --no-build --pull never -d --wait
