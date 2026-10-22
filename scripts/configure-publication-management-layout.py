"""Set the publication workspace layout through its versioned, audited control-plane API.

Requires a private JSON session file containing {"admin": {"token": "..."}}.
The output directory stores before/after configuration, never authentication data.
"""
import argparse
import copy
import json
from pathlib import Path
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base', required=True, help='API origin, e.g. http://127.0.0.1:18180')
parser.add_argument('--session', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
token = json.loads(args.session.read_text())['admin']['token']
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def call(path, data=None):
    request = urllib.request.Request(args.base.rstrip('/')+'/api/v1'+path,
        data=None if data is None else json.dumps(data).encode(),
        headers={'Content-Type':'application/json','Authorization':'Bearer '+token},
        method='GET' if data is None else 'PUT')
    with client.open(request, timeout=30) as response:
        value = json.load(response)
    if value['code'] != 200:
        raise RuntimeError('Workspace API rejected update')
    return value['data']

before = next(row for row in call('/enterprise/workspaces')
              if row['manifest']['workspaceId'] == 'mcp-publication')
args.output.mkdir(parents=True, exist_ok=False)
(args.output/'before.json').write_text(json.dumps(before, ensure_ascii=False, indent=2))
if before['manifest']['layoutType'] != 'management':
    manifest = copy.deepcopy(before['manifest'])
    manifest['layoutType'] = 'management'
    manifest['configVersion'] += 1
    call('/enterprise/workspaces/mcp-publication', {
        'manifest':manifest, 'status':before['status'],
        'accessPermissionCode':before['accessPermissionCode'], 'expectedVersion':before['version']})
after = next(row for row in call('/enterprise/workspaces')
             if row['manifest']['workspaceId'] == 'mcp-publication')
assert after['manifest']['layoutType'] == 'management'
assert call('/workspaces/mcp-publication/manifest')['manifest']['layoutType'] == 'management'
(args.output/'after.json').write_text(json.dumps(after, ensure_ascii=False, indent=2))
print(json.dumps({'workspace':'mcp-publication','layoutBefore':before['manifest']['layoutType'],
                 'layoutAfter':after['manifest']['layoutType'],'configVersion':after['manifest']['configVersion']}))
