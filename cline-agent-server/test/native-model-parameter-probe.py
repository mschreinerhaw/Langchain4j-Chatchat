"""Real request-parameter probe only; never counts as analysis acceptance.

One fresh session, no task data or analysis program. Forwarded model bodies are
unchanged. Discover the Native defaults before freezing a CLI comparison cohort.
"""
from pathlib import Path
import argparse, hashlib, http.server, json, os, secrets, subprocess, threading, time, urllib.request, uuid
import live_model_audit as audit
from resource_registry import register_container

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--image',required=True)
p.add_argument('--case',default='native-provider-default-parameter-probe-1')
a=p.parse_args()
if not a.image.startswith('sha256:') or not a.case.replace('-','').isalnum():raise ValueError('Pinned image and unique probe case required')
os.umask(0o077);folder=a.root/'runs'/a.case;folder.mkdir(mode=0o700)
for name in ['workspace','state']:
    path=folder/name;path.mkdir();os.chown(path,10001,10001);path.chmod(0o700)
profile=json.loads((a.root/'private/settings.json').read_text());profile.update(apiToken=secrets.token_hex(32),proxyToken=secrets.token_hex(32))
audit.configure(a.root,profile);audit.active[a.case]=time.monotonic()
config={'sourceRoot':'/not-mounted/upstream','distDir':'/poc/runtime','stateDir':'/state','apiToken':profile['apiToken'],
    'apiKey':profile['proxyToken'],'model':profile['model'],'baseUrl':f'http://cline-model-audit:18550/{a.case}/v1',
    'workspaces':{'probe':'/workspace/probe'},'modelOptions':{'thinking':True,'reasoningEffort':'low'},
    'toolPermissions':[],'host':'0.0.0.0','port':18551,'requireSubmitAndExit':False}
private=folder/'native-config.private.json';private.write_text(json.dumps(config));os.chown(private,10001,10001);private.chmod(0o400)
def docker(*args,timeout=60):
    result=subprocess.run(['docker',*args],text=True,capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stderr[-900:])
    return result.stdout.strip()
def api(path,data=None):
    req=urllib.request.Request('http://127.0.0.1:18551'+path,data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization':'Bearer '+profile['apiToken'],'Content-Type':'application/json','Accept':'application/json','Idempotency-Key':str(uuid.uuid4())})
    with urllib.request.urlopen(req,timeout=20) as response:return json.load(response)
proxy=http.server.ThreadingHTTPServer(('0.0.0.0',18550),audit.AuditProxy)
threading.Thread(target=proxy.serve_forever,daemon=True).start();container=None;session=None;terminal='PROBE_FAILED'
try:
    name='coding-node-'+a.case
    container=docker('create','--name',name,'--label','runtime-os.coding.acceptance='+a.case,'--init',
        '--network','cline-parquet-isolation','--add-host','cline-model-audit:host-gateway','--read-only','--cap-drop','ALL',
        '--security-opt','no-new-privileges','--memory','3g','--cpus','2','--pids-limit','256',
        '--tmpfs','/tmp:rw,nosuid,nodev,size=128m','-p','127.0.0.1:18551:18551','-e','CLINE_SERVER_CONFIG=/run/config.json',
        '-v',str(folder/'workspace')+':/workspace/probe','-v',str(folder/'state')+':/state','-v',str(private)+':/run/config.json:ro',a.image)
    inspection=json.loads(docker('inspect',container))[0];register_container(a.root,inspection,name,'Real Native provider-default wire parameter probe; no business acceptance',a.case)
    docker('start',container);deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        try:
            if api('/health/ready').get('ready'):break
        except Exception:time.sleep(.25)
    else:raise RuntimeError('Native readiness failed')
    session=api('/v1/sessions',{'workspaceRef':'probe'})['sessionId']
    while time.monotonic()<deadline:
        if api('/v1/sessions/'+session)['status']=='idle':break
        time.sleep(.25)
    else:raise RuntimeError('Worker readiness failed')
    api('/v1/sessions/'+session+'/messages',{'text':'这是一条配置连通性探针。仅回复 READY，不要读取、创建文件或调用工具。'})
    while time.monotonic()<deadline:
        status=api('/v1/sessions/'+session)['status']
        if status in ['turn_completed','failed','awaiting_input']:terminal=status;break
        time.sleep(.25)
    (folder/'events.json').write_text(json.dumps(api('/v1/sessions/'+session+'/events')))
except Exception as error:(folder/'failure.json').write_text(json.dumps({'error':repr(error)}))
finally:
    if container:
        docker('stop','-t','5',container)
        (folder/'container-final.json').write_text(json.dumps({'id':container,'state':json.loads(docker('inspect',container))[0]['State']}))
    proxy.shutdown();audit.active.pop(a.case,None)
    requests=[json.loads(path.read_text()) for path in sorted((folder/'model').glob('*-request.json'))]
    parameters=[{k:v for k,v in request.items() if k not in ['messages','tools']} for request in requests]
    summary={'case':a.case,'terminal':terminal,'modelRequests':len(requests),'wireParameters':parameters,
        'workspaceFiles':[str(path.relative_to(folder/'workspace')) for path in (folder/'workspace').rglob('*') if path.is_file()],
        'countsAsBusinessAcceptance':False,'imageId':a.image}
    (folder/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary))
