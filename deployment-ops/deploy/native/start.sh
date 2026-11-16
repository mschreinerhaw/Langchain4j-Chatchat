#!/bin/sh
# Native control-plane launcher. Never invokes Docker or a business application.
set -eu
umask 077
if [ -z "${INVOCATION_ID:-}" ] && [ "${OPS_FOREGROUND:-0}" != 1 ]; then
  SERVICE=${OPS_SERVICE:-deployment-ops}
  case "$SERVICE" in deployment-ops|deployment-ops-*) ;; *) echo 'Invalid platform service name' >&2; exit 1 ;; esac
  case "$SERVICE" in *[!a-z0-9-]*) echo 'Invalid platform service name' >&2; exit 1 ;; esac
  exec systemctl start "$SERVICE-executor.service" "$SERVICE.service"
fi
OPS_HOME=${OPS_HOME:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}
OPS_DATA_MOUNT=${OPS_DATA_MOUNT:-/mnt/disk01}
[ "$OPS_DATA_MOUNT" = /mnt/disk01 ] || { echo 'Required data mount must be /mnt/disk01' >&2; exit 1; }
OPS_DATA_DIR=${OPS_DATA_DIR:-$OPS_DATA_MOUNT/deployment-ops/data}
OPS_PORT=${OPS_PORT:-19240}
OPS_BIND=${OPS_BIND:-127.0.0.1}
OPS_JAR=${OPS_JAR:-$OPS_HOME/lib/deployment-ops.jar}
JAVA_BIN=${JAVA_BIN:-java}
if ! mountpoint -q "$OPS_DATA_MOUNT"; then
  echo "Required data mount is unavailable: $OPS_DATA_MOUNT" >&2
  exit 1
fi
if [ ! -r "$OPS_JAR" ]; then
  echo "Missing native application JAR: $OPS_JAR" >&2
  exit 1
fi
mkdir -p "$OPS_DATA_DIR"
OPS_DATA_DIR=$(CDPATH= cd -- "$OPS_DATA_DIR" && pwd -P)
OPS_DATA_MOUNT=$(CDPATH= cd -- "$OPS_DATA_MOUNT" && pwd -P)
case "$OPS_DATA_DIR/" in
  "$OPS_DATA_MOUNT/"*) ;;
  *) echo "Data directory must remain inside the required mount" >&2; exit 1 ;;
esac
export OPS_DATA_DIR OPS_BIND OPS_PORT
cd "$OPS_HOME"
exec "$JAVA_BIN" -Djava.io.tmpdir=${OPS_TMP_DIR:-/mnt/disk01/deployment-ops/tmp} -Xms128m -Xmx512m -jar "$OPS_JAR"
