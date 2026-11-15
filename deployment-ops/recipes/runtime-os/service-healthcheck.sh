#!/usr/bin/env bash
# All Java runtime images are based on Ubuntu Temurin and include bash.
# Verify HTTP accepts requests (including authenticated endpoints) and optional gRPC TCP.
set -euo pipefail
exec 3<>/dev/tcp/127.0.0.1/"$1"
printf 'GET %s HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' "${3:-/actuator/health}" >&3
IFS= read -r status <&3
[[ "$status" =~ ^HTTP/1\.[01]\ (200|401|403)\  ]]
exec 3<&- 3>&-
if [[ -n "${2:-}" ]]; then
  exec 4<>/dev/tcp/127.0.0.1/"$2"
  exec 4<&- 4>&-
fi
