"""Preserve first-candidate metadata and require an idle worker before replacement."""
import json
from pathlib import Path
import shutil
import urllib.request
import sys
import re
label=sys.argv[1] if len(sys.argv)>1 else 'first-candidate'
if not re.fullmatch(r'[a-z0-9-]{1,64}',label):raise ValueError('Invalid candidate label')
root=Path('/opt/runtime-os-docker-stage')
evidence=root/'evidence/runtime-os-studios-20261021'
request=urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',headers={
    'Authorization':'Bearer '+(root/'config/coding-node-monitor.token').read_text().strip()})
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
with client.open(request,timeout=10) as response: node=json.load(response)
if node['sessions']['active']!=0:raise RuntimeError('Accepted SDK sessions must stop before replacement')
for name in ('module-manifest.json','module-package.json','deployment.json'):
    target=evidence/(label+'-'+name)
    if not target.exists():shutil.copy2(evidence/name,target)
print(json.dumps({'activeNodeSessions':0,'firstCandidateMetadataRetained':True}))
