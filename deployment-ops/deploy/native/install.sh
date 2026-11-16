#!/bin/sh
# Native bootstrap installation. Does not install or alter managed workloads.
set -eu
umask 077
[ "$(id -u)" = 0 ] || { echo 'Run installer as root' >&2; exit 1; }
mountpoint -q /mnt/disk01 || { echo 'Required mount /mnt/disk01 is unavailable' >&2; exit 1; }
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
[ "$SOURCE" = /opt/deployment-ops ] || { echo 'Install release at /opt/deployment-ops; existing directories must be preserved.' >&2; exit 1; }
[ -r "$SOURCE/lib/deployment-ops.jar" ] || { echo 'Build lib/deployment-ops.jar first' >&2; exit 1; }
JAVA_BIN=${JAVA_BIN:-java}
JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
[ "${JAVA_MAJOR:-0}" -ge 17 ] || { echo 'Java 17+ is required; set JAVA_BIN' >&2; exit 1; }
# Preserve credentials and data on repeated installation.
getent group deployment-ops >/dev/null || groupadd --system deployment-ops
id deployment-ops >/dev/null 2>&1 || useradd --system --gid deployment-ops --home-dir /mnt/disk01/deployment-ops --shell /usr/sbin/nologin deployment-ops
install -d -m 0750 -o deployment-ops -g deployment-ops /mnt/disk01/deployment-ops /mnt/disk01/deployment-ops/data /mnt/disk01/deployment-ops/logs /mnt/disk01/deployment-ops/tmp
install -d -m 0750 -g deployment-ops /etc/deployment-ops
if [ ! -f /etc/deployment-ops/manager.env ]; then
  cp "$SOURCE/deploy/native/manager.env.example" /etc/deployment-ops/manager.env
  chown root:deployment-ops /etc/deployment-ops/manager.env
  chmod 0640 /etc/deployment-ops/manager.env
fi
install -m 0644 "$SOURCE/deploy/native/deployment-ops.service" /etc/systemd/system/deployment-ops.service
systemctl daemon-reload
echo 'Installed. Configure manager.env with Java 17+, then systemctl enable --now deployment-ops.'
