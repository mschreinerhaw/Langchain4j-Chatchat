#!/bin/sh
set -eu
exec systemctl status deployment-ops.service --no-pager
