#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
case "${1:-}" in
 export) docker save deployment-ops-web:1.0.0 deployment-ops-executor:1.0.0 eclipse-temurin:17-jdk-jammy python:3.11-slim > "${2:?output TAR path}"; sha256sum "$2" > "$2.sha256" ;;
 import) archive="${2:?input TAR path}"; expected=$(cut -d " " -f 1 "$archive.sha256"); printf '%s  %s\n' "$expected" "$archive" | sha256sum -c -; docker load -i "$archive" ;;
 *) echo 'Usage: offline-images.sh export|import archive.tar'; exit 2 ;;
esac
