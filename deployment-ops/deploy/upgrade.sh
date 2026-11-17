#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test "${MANAGER_UPGRADE_REVIEWED:-}" = yes || { echo 'Review image identities, take a cold backup, and set MANAGER_UPGRADE_REVIEWED=yes.' >&2; exit 1; }
test -f .env
sha256sum -c SHA256SUMS
# Verify loading cannot change existing tag bindings to a different image.
python3 - <<'PY'
import json,subprocess
for item in json.load(open('images.lock.json')):
 p=subprocess.run(['docker','image','inspect',item['tag']],capture_output=True,text=True)
 if p.returncode==0 and json.loads(p.stdout)[0]['Id']!=item['id']:raise SystemExit('Release tag collides with an existing image; use versioned release tags')
PY
docker load -i images/manager-images.tar
python3 verify-images.py
docker compose config --quiet
docker compose up -d --no-build --pull never web executor
