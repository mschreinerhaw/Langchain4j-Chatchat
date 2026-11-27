#!/bin/sh
set -eu
case "${1:-}" in
  check|status) exec python3 "$(dirname "$0")/runtime-os-scope.py" "$1" ;;
  install|start|stop) exec python3 /opt/product/installer.py "$1" ;;
  *) echo 'Expected check, status, install, start or stop' >&2; exit 2 ;;
esac
