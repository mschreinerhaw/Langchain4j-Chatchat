#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec python3 restore.py "${1:?backup directory}" "${2:?exact confirmation RESTORE-MANAGEMENT}"
