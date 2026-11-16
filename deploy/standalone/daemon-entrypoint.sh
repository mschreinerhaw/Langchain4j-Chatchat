#!/bin/sh
set -eu
umask 077
root=${RUNTIME_DAEMON_ROOT:?}
mkdir -p "$root/run" "$root/data"
chmod 700 "$root" "$root/run" "$root/data"
# This daemon has its own network namespace and persistent root. Only the
# trusted Executor can reach its private Unix socket; no TCP API is enabled.
containerd --config "$root/containerd.toml" &
containerd_pid=$!
dockerd --config-file "$root/daemon.json" &
daemon_pid=$!
trap 'kill -TERM "$daemon_pid" "$containerd_pid" 2>/dev/null || true; wait || true' INT TERM EXIT
while kill -0 "$daemon_pid" 2>/dev/null && kill -0 "$containerd_pid" 2>/dev/null; do
  if [ -S "$root/run/docker.sock" ]; then chmod 600 "$root/run/docker.sock"; fi
  chmod 700 "$root" "$root/run" "$root/data"
  sleep 2
done
exit 1
