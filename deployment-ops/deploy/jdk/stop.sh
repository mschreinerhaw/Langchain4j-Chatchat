#!/bin/sh
set -eu
exec /bin/systemctl stop "${OPS_SERVICE:-deployment-ops}"
