#!/usr/bin/env bash
set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
build_context=$(mktemp -d)
trap 'rm -f -- "$build_context/Dockerfile" "$build_context/supervise.py" "$build_context/docker/"*; rmdir -- "$build_context/docker" "$build_context"' EXIT
mkdir "$build_context/docker"
cp "$here/Dockerfile" "$here/supervise.py" "$build_context/"

# Build with the same engine and runtime versions as the source host.
for binary in docker dockerd containerd containerd-shim-runc-v2 ctr runc; do
  install -m 0755 "$(command -v "$binary")" "$build_context/docker/$binary"
done
install -m 0755 "${DOCKER_INIT_BINARY:-/usr/libexec/docker/docker-init}" "$build_context/docker/docker-init"

image=${RUNTIME_OS_NATIVE_DAEMON_IMAGE:-runtime-os-native-daemon:26.1.3-el8}
sha256sum "$build_context/Dockerfile" "$build_context/supervise.py" "$build_context/docker/"*
docker build --network none --progress plain --tag "$image" "$build_context"
docker image inspect "$image" --format '{{.Id}}'
