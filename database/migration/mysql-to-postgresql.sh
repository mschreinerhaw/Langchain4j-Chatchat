#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec "${PYTHON_BIN:-python3}" "${script_dir}/transfer_mysql_postgresql.py" \
  --direction mysql-to-postgresql "$@"
