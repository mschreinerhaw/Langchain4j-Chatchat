#!/bin/sh
set -eu
umask 077
exec "${PYTHON_BIN:-python3}" "$(dirname -- "$0")/install.py" "$@"
