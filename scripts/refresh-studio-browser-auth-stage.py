"""Create a short-lived browser acceptance session without exporting the stage password."""
import json
import os
from pathlib import Path
import urllib.request
os.umask(0o077)
root=Path('/opt/runtime-os-docker-stage')
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
body=(root/'evidence/cline-monitor-20261019/stage-auth.json').read_bytes()
request=urllib.request.Request('http://127.0.0.1:18180/api/v1/enterprise/auth/login',data=body,headers={'Content-Type':'application/json'})
with client.open(request,timeout=30) as response:auth=json.load(response)['data']
(root/'evidence/runtime-os-studios-20261021/browser-auth-private.json').write_text(json.dumps({'token':auth['token'],'user':auth['user']}))
print(json.dumps({'status':'AUTHENTICATED','passwordExported':False}))
