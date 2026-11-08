#!/usr/bin/env bash
# Build a complete tracked real-project fixture; never stage a hand-picked source subset.
set -euo pipefail
REPOSITORY="$(cd "${1:?Runtime repository required}" && pwd)"
REVISION="${2:?Fixed Runtime commit required}"
DESTINATION="${3:?New empty destination required}"
if test -e "$DESTINATION"; then
  echo "Destination must not exist: $DESTINATION" >&2
  exit 1
fi
mkdir -p "$DESTINATION"
git -C "$REPOSITORY" archive "$REVISION" coding-agent-node | tar -xf - -C "$DESTINATION"
cd "$DESTINATION/coding-agent-node"
test -s fixtures/monitor-server.mjs
test -s skills/catalog.json
printf 'node_modules/\ndist/\n' > .gitignore
git init -q
git -c user.name='Cline POC' -c user.email='poc@localhost' add -A
git -c user.name='Cline POC' -c user.email='poc@localhost' commit -qm "Complete real-project fixture from $REVISION"
git rev-parse HEAD
