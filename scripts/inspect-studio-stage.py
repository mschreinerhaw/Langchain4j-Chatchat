"""Inspect authenticated Studio readiness without printing credentials or source data."""
import json
from pathlib import Path
import urllib.request
import urllib.error
import subprocess

client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
base='http://127.0.0.1:18180/api/v1'
token=None
def call(path,body=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(base+path,headers=headers,data=None if body is None else json.dumps(body).encode())
    with client.open(req,timeout=30) as response: return json.load(response)['data']
token=call('/enterprise/auth/login',json.loads(Path('/opt/runtime-os-docker-stage/evidence/cline-monitor-20261019/stage-auth.json').read_text()))['token']
for path in ['/data-science/python/assist/coding/capabilities','/data-science/python/models','/data-science/sql/projects']:
    try:
        data=call(path)
        print(json.dumps({'path':path,'data':data if 'capabilities' in path or 'models' in path else {'count':len(data)}}))
    except urllib.error.HTTPError as error: print(json.dumps({'path':path,'status':error.code}))
for command in (['docker','exec','runtime-os-app-stage-api-1','cat','/etc/os-release'],
                ['docker','exec','runtime-os-app-stage-api-1','python3','--version'],
                ['docker','exec','runtime-os-app-stage-api-1','ls','/opt'],
                ['docker','images','--format','{{.Repository}}:{{.Tag}}']):
    result=subprocess.run(command,text=True,capture_output=True)
    print(json.dumps({'command':command,'status':result.returncode,'output':result.stdout[:4000]}))
proxy=subprocess.run(['docker','exec','runtime-os-app-stage-node-proxy-1','cat','/etc/nginx/nginx.conf'],text=True,capture_output=True)
print(json.dumps({'proxyWorkspaceRoutes':[line.strip() for line in proxy.stdout.splitlines() if 'location' in line or 'workspace' in line]}))
