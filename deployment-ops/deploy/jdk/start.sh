#!/bin/sh
set -eu
exec /bin/systemctl start "${OPS_SERVICE:-deployment-ops}"
