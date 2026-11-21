#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
root=/srv/runtime-os-coding-node-20261110/strict-daemon-3
state=/opt/runtime-os/p18-d736e9f
install -d -m 0700 "$root"
install -d -m 0700 "$root/run" "$root/data" "$root/containerd-data" "$root/containerd-state"
install -d -m 0700 "$state"
tmp=$(mktemp -d)
trap 'rm -f "$tmp"/*; rmdir "$tmp"' EXIT
cat >"$tmp/daemon.json" <<EOF
{"hosts":["unix://$root/run/docker.sock"],"bridge":"none","iptables":false,"ip6tables":false,"data-root":"$root/data","exec-root":"$root/exec","containerd":"$root/run/containerd.sock","pidfile":"$root/run/dockerd.pid"}
EOF
cat >"$tmp/containerd.toml" <<EOF
version = 2
root = "$root/containerd-data"
state = "$root/containerd-state"
disabled_plugins = ["io.containerd.grpc.v1.cri"]
[grpc]
address = "$root/run/containerd.sock"
EOF
place() {
  local source=$1 target=$2 mode=$3
  if [[ -e $target ]]; then
    cmp -s "$source" "$target" || { printf 'Existing configuration differs: %s\n' "$target" >&2; return 1; }
  else
    install -m "$mode" "$source" "$target"
  fi
}
place "$tmp/daemon.json" "$root/daemon.json" 0600
place "$tmp/containerd.toml" "$root/containerd.toml" 0600
export CUSTOMER_FRONTEND_SECCOMP="$here/../../../../frontend-resource-hub/seccomp_profile.json"
env_file=/opt/runtime-os-docker-stage/config/release.env
docker compose -p runtime-os-source-production --env-file "$env_file" \
  -f "$here/docker-compose.yml" -f "$here/native.compose.yml" \
  -f "$here/../../../docker/production/compose.native-daemon.yaml" \
  up -d --wait --wait-timeout 90 --no-build --pull never native-daemon
