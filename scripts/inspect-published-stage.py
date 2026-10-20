"""Read authorized assistant identities without exporting credentials or prompts."""
import json
import pathlib
import urllib.request

root = pathlib.Path('/opt/runtime-os-docker-stage')
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
base = 'http://127.0.0.1:18180/api/v1'
credentials = json.loads((root/'evidence/cline-monitor-20261019/stage-auth.json').read_text())
request = urllib.request.Request(base+'/enterprise/auth/login',
    data=json.dumps(credentials).encode(), headers={'Content-Type': 'application/json'})
with client.open(request, timeout=30) as response:
    token = json.load(response)['data']['token']
request = urllib.request.Request(base+'/agents/workshop', headers={'Authorization': 'Bearer '+token})
with client.open(request, timeout=30) as response:
    data = json.load(response)['data']
def visit(value):
    if isinstance(value, dict):
        if 'id' in value and ('label' in value or 'defaultMode' in value):
            print(json.dumps({k:value.get(k) for k in ('id','name','label','defaultMode','status','marketStatus','modelName')}, ensure_ascii=False))
        else:
            for child in value.values(): visit(child)
    elif isinstance(value, list):
        for child in value: visit(child)
visit(data)
