#!/usr/bin/env bash
set -euo pipefail
: "${CLINE_SOURCE_ROOT:?Set fixed upstream checkout directory}"
SERVICE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$CLINE_SOURCE_ROOT"
test "$(node -p "require('./apps/vscode/package.json').version")" = 4.1.26
test "$(bun --version)" = 1.4.2
bun install --frozen-lockfile
bun run build:sdk
node "$SERVICE_ROOT/scripts/patch-upstream.mjs" "$CLINE_SOURCE_ROOT"
cd apps/vscode
# Exact compile-standalone body; run the Linux-only post step explicitly.
# The default post lifecycle builds every platform and requires npx/prebuild downloads.
bun run check-types
bun run lint
bun esbuild.mjs --standalone
node scripts/package-standalone.mjs -s
test -s dist-standalone/cline-core.js
