#!/bin/sh
set -eu
exec /bin/systemctl restart "${OPS_SERVICE:-deployment-ops}"
