#!/bin/sh
set -eu
home=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$home/lib/deployment-ops.jar" --install-service "${OPS_SERVICE:-deployment-ops}"
