#!/bin/sh
set -eu
exec /bin/systemctl status "${OPS_SERVICE:-deployment-ops}" --no-pager
