#!/bin/sh
set -eu
umask 077
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
folder="${1:?Specify a new absolute backup directory}"
case "$folder" in /*) ;; *) echo 'Absolute backup directory required' >&2; exit 1 ;; esac
test ! -e "$folder" || { echo 'Backup destination already exists' >&2; exit 1; }
mkdir -p "$folder"
docker compose stop web executor
trap 'docker compose start executor web >/dev/null' EXIT
docker compose cp web:/data "$folder/data"
docker compose cp executor:/state "$folder/state"
cp .env compose.yaml images.lock.json "$folder/"
cp -R trust "$folder/trust"
touch "$folder/COMPLETE"
docker compose start executor web
trap - EXIT
echo "Cold management backup complete: $folder; business workloads were not stopped."
