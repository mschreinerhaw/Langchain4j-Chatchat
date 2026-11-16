#!/bin/sh
set -eu
exec systemctl restart deployment-ops.service
