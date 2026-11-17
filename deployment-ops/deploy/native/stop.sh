#!/bin/sh
set -eu
SERVICE=${OPS_SERVICE:-deployment-ops}
case "$SERVICE" in deployment-ops|deployment-ops-*) ;; *) echo 'Invalid platform service name' >&2; exit 1 ;; esac
case "$SERVICE" in *[!a-z0-9-]*) echo 'Invalid platform service name' >&2; exit 1 ;; esac
exec systemctl stop "$SERVICE.service" "$SERVICE-executor.service"
