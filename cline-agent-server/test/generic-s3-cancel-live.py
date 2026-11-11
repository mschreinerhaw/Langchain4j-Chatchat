"""Real-model cancellation probe. No analysis/worker program is supplied.

Waits for an actual Python child selected by Cline, cancels through the public
gateway, checks Executor stop and late local writes. Does not claim restoration
of a canceled Session, Silo validation, or business application acceptance.
"""
from pathlib import Path
import argparse, base64, hashlib, hmac, json, os, re, subprocess, time, urllib.request, urllib.error, uuid

p=argparse.ArgumentParser();p.add_argument('--deployment',type=Path,required=True);p.add_argument('--case',required=True)
a=p.parse_args();os.umask(0o077)
cfg=json.loads((a.deployment/'private/gateway.json').read_text());executor=json.loads((a.deployment/'private/executor.json').read_text())
folder=a.deployment/'acceptance'/a.case;folder.mkdir(parents=True,exist_ok=False)
tenant=next(iter(cfg['tenants']));policy=cfg['tenants'][tenant]
session='cancel-'+uuid.uuid4().hex;task='task-'+uuid.uuid4().hex;run='run-'+uuid.uuid4().hex
contract={'version':2,'tenantId':tenant,'userId':policy['users'][0],'taskId':task,'sessionId':session,'workspaceRef':'project',
    'modelProfile':policy['models'][0],'budgetPolicy':{'status':'DEFERRED_STAGE_2_REVIEW'},
    'toolPermissions':[t for t in policy['tools'] if t in ['read_files','search_codebase','editor','apply_patch','run_commands','skills']],
    'dataPermissions':[],'networkPolicy':'broker-only','expiresAt':int((time.time()+300)*1000),'runtimeProfile':'native-python-silo-v1',
    'deliveryBinding':{'tenantId':tenant,'taskId':task,'runId':run,'inputSnapshotVersion':'cancel-no-input-v1'},
    's3':{'origin':cfg['s3']['allowedOrigins'][0],'inputPrefix':'/runtime-assets/acceptance/'+task+'/'+run+'/input/',
        'outputPrefix':'/runtime-assets/acceptance/'+task+'/'+run+'/output/','inputManifestSha256':hashlib.sha256(b'[]').hexdigest()}}
body=base64.urlsafe_b64encode(json.dumps(contract,separators=(',',':')).encode()).decode().rstrip('=')
token=body+'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),body.encode(),hashlib.sha256).digest()).decode().rstrip('=')
base='http://127.0.0.1:'+str(cfg['port']);route='/v1/sessions/'+session
def request(path,value=None,key=None):
    headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}
    if key:headers['Idempotency-Key']=key
    req=urllib.request.Request(base+path,data=None if value is None else json.dumps(value).encode(),headers=headers)
    with urllib.request.urlopen(req,timeout=100) as response:return json.load(response)
def save(name,value):(folder/name).write_text(json.dumps(value,ensure_ascii=False,indent=2))
def snapshot(root):return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file() and not p.is_symlink()}
started=time.monotonic();cancelled=False
try:
    save('intent.json',{'contract':contract,'realModel':True,'sessionRestorationTest':False})
    request('/v1/sessions',{'inputs':[]},'create-'+session);deadline=time.time()+270
    while time.time()<deadline:
        state=request(route+'/status')
        if state['workerState']=='READY':break
        if state['workerState'] in ['START_FAILED','RECOVERABLE','STOPPED']:raise RuntimeError('Worker did not become ready')
        time.sleep(.5)
    else:raise RuntimeError('Worker startup deadline')
    prompt='Create a small Python cancellation fixture project with your own filenames. The program should write a started marker, wait for 120 seconds using Python time.sleep, then write a completed marker. Execute it with the native command tool and wait for it. This task tests cancellation of a REAL Python child; do not emulate the wait, catch cancellation, or spawn anything outside the task workspace. There are no business data inputs.'
    save('message.json',request(route+'/messages',{'text':prompt},'message-'+session))
    worker=state['workerId'];registry=a.deployment/'state/workers'/worker/'registry.json';container=None
    docker=['docker','--host','unix://'+executor['dockerSocket']];previous=None;observed=None
    while time.time()<deadline:
        state=request(route+'/status');save('state.json',state)
        container=json.loads(registry.read_text())['containerId']
        info=json.loads(subprocess.check_output(docker+['inspect',container]))[0]
        if info['Config']['Labels'].get('cline.workerId')!=worker:raise RuntimeError('Executor ownership differs')
        if not info['State']['Running']:raise RuntimeError('Worker stopped before real Python observation')
        table=subprocess.check_output(docker+['top',container,'-eo','pid,comm,args'],text=True)
        pids=[line.split()[0] for line in table.splitlines()[1:] if len(line.split())>=2 and re.fullmatch(r'python(?:3(?:\.11)?)?',line.split()[1])]
        if pids and pids==previous:observed={'pids':pids,'dockerTop':table};break
        previous=pids;time.sleep(1)
    if not observed:raise RuntimeError('No sustained real Python child; no cancellation pass inferred')
    save('python-before-cancel.json',observed);save('cancel-request.json',request(route+'/cancel',{}));cancelled=True
    for _ in range(40):
        state=request(route+'/status')
        if state['workerState']=='STOPPED':break
        time.sleep(.5)
    info=json.loads(subprocess.check_output(docker+['inspect',container]))[0]
    workspace=Path(cfg['workerRoot'])/worker/'workspace';before=snapshot(workspace);time.sleep(2);after=snapshot(workspace)
    checks={'actualPythonObserved':True,'executorStopped':info['State']['Running'] is False,
        'stopReceipt':state.get('stopProof',{}).get('running') is False,'revoked':state['revoked'] is True,'noLateLocalWrites':before==after}
    for action,value in [('upload',{'manifestSha256':'0'*64}),('acceptance',{'manifestSha256':'0'*64,'status':'PASSED','validationRef':'late'})]:
        try:request(route+'/'+action,value);checks['late'+action+'Denied']=False
        except urllib.error.HTTPError as failure:checks['late'+action+'Denied']=failure.code==403
    try:request('/v1/sessions',{'inputs':[]},'create-'+session);checks['oldGrantRecreationDenied']=False
    except urllib.error.HTTPError as failure:checks['oldGrantRecreationDenied']=failure.code==403
    save('cancel-verification.json',{'checks':checks,'passed':all(checks.values()),'containerId':container,'elapsedSeconds':time.monotonic()-started,
        'sessionRestored':False,'realSiloAcceptance':False,'applicationHttpSpiAcceptance':False})
    save('final-state.json',state);print(json.dumps({'case':a.case,'checks':checks}))
    if not all(checks.values()):raise RuntimeError('Real process cancellation verification failed')
except Exception as error:
    save('failure.json',{'error':str(error),'failedSampleRetained':True})
    if not cancelled:
        try:save('failure-cancel.json',request(route+'/cancel',{}))
        except Exception as stop:save('stop-unconfirmed.json',{'error':str(stop)})
    raise
