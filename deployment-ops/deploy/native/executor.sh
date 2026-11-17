#!/bin/sh
set -eu
umask 077
mountpoint -q /mnt/disk01 || { echo 'Required mount /mnt/disk01 is unavailable' >&2; exit 1; }
export OPS_NATIVE=1 OPS_EXECUTOR_BIND=${OPS_EXECUTOR_BIND:-127.0.0.1} OPS_EXECUTOR_PORT=${OPS_EXECUTOR_PORT:-19242}
export OPS_STATE=${OPS_STATE:-/mnt/disk01/deployment-ops/state}
export OPS_PACKAGES=${OPS_PACKAGES:-/mnt/disk01/deployment-ops/packages}
export OPS_TRUST=${OPS_TRUST:-/mnt/disk01/deployment-ops/trust}
export OPS_APP_DATA=${OPS_APP_DATA:-/mnt/disk01/deployment-ops/apps}
export OPS_HOST_PROC=/proc OPS_HOST_ROOT=/mnt/disk01
export OPS_STRICT_OWNERSHIP=1
export TMPDIR=${OPS_TMP_DIR:-/mnt/disk01/deployment-ops/tmp}
exec "${PYTHON_BIN:-python3}" "${OPS_HOME:-/opt/deployment-ops}/executor/agent.py"
