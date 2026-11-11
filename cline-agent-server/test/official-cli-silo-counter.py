"""One real official CLI control on the exact verified Native input snapshot.

No retry/restart or fabricated Native receipt. A zero CLI exit is not business
acceptance. Independent Java review is a separate required operation.
"""
from pathlib import Path
import argparse, datetime, hashlib, http.server, json, os, secrets, shutil
import subprocess, threading, time
import live_model_audit as audit
from resource_registry import register_container

p=argparse.ArgumentParser()
p.add_argument('--root',type=Path,required=True)
p.add_argument('--fixture',type=Path,required=True)
p.add_argument('--reference-run',required=True)
p.add_argument('--case',required=True)
p.add_argument('--image',required=True)
p.add_argument('--proxy-port',type=int,default=18540)
p.add_argument('--output-token-policy',choices=['8192','provider-default'],default='8192')
a=p.parse_args()
if not a.image.startswith('sha256:') or not a.case.replace('-','').isalnum():raise ValueError('Pinned image and unique case required')
os.umask(0o077)
folder=a.root/'runs'/a.case
folder.mkdir(mode=0o700) # Existing cases must never be redispatched.
workspace=folder/'workspace';state=folder/'state'
for d in [workspace,state]:d.mkdir();os.chown(d,10001,10001);d.chmod(0o700)
plan=json.loads((a.fixture/'worker-plan.private.json').read_text())
reference=a.root/'runs'/a.reference_run/'workspace'
shutil.copytree(reference/'input',workspace/'input')
for item in plan['inputs']:
    raw=(workspace/item['path']).read_bytes()
    if len(raw)!=item['size'] or hashlib.sha256(raw).hexdigest()!=item['sha256']:raise RuntimeError('Original input integrity mismatch')
for file in (workspace/'input').rglob('*'):
    os.chown(file,10001,10001);file.chmod(0o555 if file.is_dir() else 0o444)
(workspace/'input').chmod(0o555)
config=json.loads((a.root/'private'/(a.fixture.name+'.json')).read_text())
prompt=(a.root/'customer-objective.txt').read_text().replace('{{customerNumber}}',config['customerNumber'])
objective=folder/'objective.private.txt';objective.write_text(prompt);objective.chmod(0o444)
profile=json.loads((a.root/'private/settings.json').read_text());profile['proxyToken']=secrets.token_hex(32)
audit.configure(a.root,profile)
local='/workspace/'+plan['binding']['taskId']
connection=folder/'connection.private.json'
connection.write_text(json.dumps({'apiKey':profile['proxyToken'],'model':profile['model'],
    'baseUrl':f'http://cline-model-audit:{a.proxy_port}/{a.case}/v1','workspace':local,'outputTokenPolicy':a.output_token_policy}))
os.chown(connection,10001,10001);connection.chmod(0o400)
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2))
def docker(*args,timeout=60):
    r=subprocess.run(['docker',*args],text=True,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stderr[-1200:])
    return r.stdout.strip()
def versions():
    for path in workspace.rglob('*'):
        if not path.is_file() or any(x in path.relative_to(workspace).parts for x in ['input','.git','__pycache__','.pytest_cache']):continue
        if path.stat().st_size>4*1024*1024:continue
        try:
            raw=path.read_bytes();target=folder/'versions'/hashlib.sha256(raw).hexdigest()/path.relative_to(workspace)
            if not target.exists():target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
        except OSError:pass
write(folder/'intent.json',{'case':a.case,'entrypoint':'unmodified official CLI','clineRelease':'4.1.26','cliComponentVersion':'3.0.74',
    'imageId':a.image,'model':profile['model'],'requestedMaxOutputTokens':8192 if a.output_token_policy=='8192' else None,'outputTokenPolicy':a.output_token_policy,'reasoningEffort':'low','consecutiveMistakeGuard':6,'cliToolMode':'official-yolo; team/spawn disabled by official CLI',
    'modelRequestLimit':'unlimited','toolCallLimit':'unlimited','timeoutSeconds':1200,
    'inputSnapshotVersion':plan['binding']['inputSnapshotVersion'],'inputCopySource':a.reference_run,
    'inputHashesVerified':True,'promptSha256':hashlib.sha256(prompt.encode()).hexdigest(),
    'independentAcceptance':'PENDING','startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()})
proxy=http.server.ThreadingHTTPServer(('0.0.0.0',a.proxy_port),audit.AuditProxy)
threading.Thread(target=proxy.serve_forever,daemon=True).start()
container=None;terminal='HARNESS_FAILURE';started=time.monotonic();log_process=None
try:
    name='coding-node-official-'+a.case
    mounts=['-v',str(workspace)+':'+local,'-v',str(workspace/'input')+':'+local+'/input:ro','-v',str(state)+':/state',
        '-v',str(connection)+':/run/connection.json:ro','-v',str(objective)+':/run/objective.txt:ro']
    for file in ['official-cli-entrypoint.py','configure-official-cli.py']:
        mounts+=['-v',str(a.root/file)+':/run/'+file+':ro']
    container=docker('create','--name',name,'--label','runtime-os.coding.acceptance='+a.case,'--init',
        '--network','cline-parquet-isolation','--add-host','cline-model-audit:host-gateway',
        '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','3g','--cpus','2',
        '--pids-limit','256','--tmpfs','/tmp:rw,nosuid,nodev,size=256m','-e','HOME=/state',
        '-e','NO_PROXY=cline-model-audit,127.0.0.1,localhost','-e','no_proxy=cline-model-audit,127.0.0.1,localhost',
        '--entrypoint','python',*mounts,a.image,'/run/official-cli-entrypoint.py')
    inspect=json.loads(docker('inspect',container))[0]
    register_container(a.root,inspect,name,'Official CLI single Agent control; exact verified customer snapshot',a.case)
    audit.active[a.case]=time.monotonic();docker('start',container)
    with (folder/'cli.stdout.jsonl').open('w') as out,(folder/'cli.stderr.log').open('w') as err:
        log_process=subprocess.Popen(['docker','logs','--follow',container],stdout=out,stderr=err)
        while time.monotonic()-started<1260:
            versions();inspect=json.loads(docker('inspect',container))[0]
            if not inspect['State']['Running']:
                terminal='CLI_EXIT_'+str(inspect['State']['ExitCode']);break
            time.sleep(1)
        else:terminal='EXTERNAL_RESOURCE_DEADLINE'
        docker('stop','-t','5',container)
        log_process.wait(timeout=20)
except Exception as error:
    write(folder/'failure.json',{'error':repr(error)})
finally:
    if container:
        docker('stop','-t','5',container)
        inspect=json.loads(docker('inspect',container))[0]
        write(folder/'container-final.json',{'id':container,'state':inspect['State']})
    versions();proxy.shutdown();audit.active.pop(a.case,None)
    receipts=[json.loads(p.read_text()) for p in (folder/'model').glob('*-receipt.json') if p.name[0].isdigit()]
    complete=bool(receipts) and all(isinstance((r.get('usage') or {}).get(k),int) for r in receipts for k in ['prompt_tokens','completion_tokens','total_tokens'])
    total={k:sum((r.get('usage') or {}).get(k,0) or 0 for r in receipts) for k in ['prompt_tokens','completion_tokens','total_tokens']}
    requests=[json.loads(p.read_text()) for p in sorted((folder/'model').glob('*-request.json'))]
    parameters=[{k:v for k,v in r.items() if k not in ['messages','tools']} for r in requests]
    aligned=bool(requests) and all(r.get('model')==profile['model'] and r.get('max_tokens')==(8192 if a.output_token_policy=='8192' else None) and r.get('reasoning_effort')=='low' for r in requests)
    summary={'case':a.case,'terminal':terminal,'modelRequests':len(receipts),'usage':total if complete else None,
        'observedUsageSubtotal':total,'meteringComplete':complete,'elapsedSeconds':round(time.monotonic()-started,3),
        'businessAcceptance':'PENDING_INDEPENDENT_JAVA_REVIEW','sourceVersions':'versions/','cost':None,
        'strictRequestParameterMatch':aligned,'observedRequestParameters':parameters}
    write(folder/'summary.json',summary);print(json.dumps(summary))
