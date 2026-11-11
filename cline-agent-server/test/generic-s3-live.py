"""Real-model gateway exercise; inputs are explicitly synthetic test data.

Does not simulate S3, supply generated analysis code, or claim business Runtime
integration. If Silo is unavailable, the sealed result remains pending upload.
Run against the opt-in gateway using its private server authority configuration.
"""
from pathlib import Path
import argparse, base64, hashlib, hmac, json, os, time, urllib.request, urllib.error, uuid
from urllib.parse import urlsplit
from native_execution_observation import observe_python_repair

p=argparse.ArgumentParser()
p.add_argument('--deployment',type=Path,required=True)
p.add_argument('--case',required=True)
p.add_argument('--timeout-seconds',type=int,default=600)
p.add_argument('--runtime-feedback',action='store_true',help='Return independently computed candidate faults to the SAME Native session')
p.add_argument('--input-plan',type=Path,help='Private, controller-prepared immutable synthetic Parquet snapshot')
a=p.parse_args();os.umask(0o077)
cfg=json.loads((a.deployment/'private/gateway.json').read_text())
if not a.case.replace('-','').isalnum():raise RuntimeError('Explicit unique case required')
folder=a.deployment/'acceptance'/a.case;folder.mkdir(parents=True,exist_ok=False)
tenant=next(iter(cfg['tenants']));policy=cfg['tenants'][tenant]
session='s3-'+uuid.uuid4().hex;task='task-'+uuid.uuid4().hex;run='run-'+uuid.uuid4().hex
tools=[t for t in policy['tools'] if t in ['read_files','search_codebase','editor','apply_patch','run_commands','skills']]
grant={'version':2,'tenantId':tenant,'userId':policy['users'][0],'taskId':task,'sessionId':session,
    'workspaceRef':'project','modelProfile':policy['models'][0],
    'budgetPolicy':{'status':'DEFERRED_STAGE_2_REVIEW'},'toolPermissions':tools,'dataPermissions':[],
    'networkPolicy':'broker-only','expiresAt':int((time.time()+a.timeout_seconds)*1000),
    'runtimeProfile':'native-python-silo-v1',
    'deliveryBinding':{'tenantId':tenant,'taskId':task,'runId':run,'inputSnapshotVersion':'explicit-synthetic-fixture-v1'},
    's3':{'origin':cfg['s3']['allowedOrigins'][0],
        'inputPrefix':'/runtime-assets/tenants/'+tenant+'/acceptance/'+task+'/'+run+'/input/',
        'outputPrefix':'/runtime-assets/tenants/'+tenant+'/acceptance/'+task+'/'+run+'/output/',
        'inputManifestSha256':hashlib.sha256(b'[]').hexdigest()}}
inputs=[]
if a.input_plan:
    plan=json.loads(a.input_plan.read_text())
    if plan['tenantId']!=tenant:raise RuntimeError('Input fixture tenant differs from frozen authority')
    inputs=plan['inputs'];grant['taskId']=task=plan['taskId'];grant['deliveryBinding']['taskId']=task
    grant['deliveryBinding']['runId']=run=plan['runId']
    grant['deliveryBinding']['inputSnapshotVersion']=plan['inputSnapshotVersion']
    inventory=[{**{k:i[k] for k in ['path','size','sha256','objectVersion']},'objectPath':urlsplit(i['url']).path} for i in sorted(inputs,key=lambda i:i['path'])]
    grant['s3'].update(inputPrefix=plan['inputPrefix'],outputPrefix=plan['outputPrefix'],
        inputManifestSha256=hashlib.sha256(json.dumps(inventory,separators=(',',':')).encode()).hexdigest())
raw=base64.urlsafe_b64encode(json.dumps(grant,separators=(',',':')).encode()).decode().rstrip('=')
token=raw+'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),raw.encode(),hashlib.sha256).digest()).decode().rstrip('=')
base='http://127.0.0.1:'+str(cfg['port'])
def request(path,value=None,key=None):
    headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}
    if key:headers['Idempotency-Key']=key
    req=urllib.request.Request(base+path,data=None if value is None else json.dumps(value).encode(),headers=headers)
    with urllib.request.urlopen(req,timeout=100) as response:return json.load(response)
def save(name,value):(folder/name).write_text(json.dumps(value,ensure_ascii=False,indent=2))
prompt='''This is an explicitly SYNTHETIC acceptance fixture, not customer/business data. Build a small, meaningful, multi-file Python project to analyze these three test expenses: label,amount\nalpha,12.5\nbeta,20\ngamma,15\n. Save the fixture as CSV in your writable project (input/ is an empty readonly authorized snapshot). Choose your own source file names and directories; use at least two Python modules and actually execute them. To exercise real repair, initially introduce and execute a genuine str-plus-int TypeError in your own source, inspect its real traceback, then repair and execute the project successfully. Read the CSV using pandas, calculate rows/total/mean, and generate a Markdown report, a JSON result with keys rows,total,mean,dataStatus (dataStatus must identify synthetic test data), and a Matplotlib PNG chart. Do not assert business validity for synthetic data. Finally write delivery_manifest.json with schemaVersion coding_node_delivery_selection.v1 and artifacts [{path,type}] identifying SOURCE, REPORT, RESULT, CHART and any TEST/AUXILIARY files you choose to deliver. Paths must be relative to the workspace. Execute the final program and inspect its outputs before finishing. Use native file/edit/command tools. No remote Python Sandbox or invented execution results.'''
if a.runtime_feedback:prompt+=' Structured JSON statistics must preserve arithmetic precision to absolute error <= 1e-9. Human-readable report figures may be rounded if clearly displayed as such.'
if a.input_plan:
    prompt=('This is an explicitly SYNTHETIC fixture delivered from real Silo, not customer data. Read the authorized immutable Parquet file input/data/expenses.parquet with pandas/pyarrow. Do not generate replacement input data. Calculate rows/total/mean from its amount column. '+prompt[prompt.index('Choose your own source'):])
save('intent.json',{'grant':grant,'prompt':prompt,'modelCallsLimit':None,'synthetic':True,'realSiloAcceptance':False})
started=time.monotonic();route='/v1/sessions/'+session
try:
    save('create.json',request('/v1/sessions',{'inputs':inputs},'create-'+session))
    deadline=time.time()+a.timeout_seconds-10
    while time.time()<deadline:
        state=request(route+'/status');save('state.json',state)
        if state['workerState']=='READY':break
        if state['workerState'] in ['RECOVERABLE','START_FAILED','STOPPED']:raise RuntimeError('Native allocation failed')
        time.sleep(.5)
    else:raise RuntimeError('Native startup timeout')
    save('message.json',request(route+'/messages',{'text':prompt},'message-'+session))
    version=0
    while True:
        while time.time()<deadline:
            state=request(route+'/status');save('state.json',state)
            native=state.get('native') or {}
            if native.get('status')=='turn_completed':break
            if state['revoked'] or state['workerState'] in ['RECOVERABLE','START_FAILED','STOPPED']:raise RuntimeError('Native task failed')
            time.sleep(1)
        else:raise RuntimeError('Native task deadline')
        version+=1;delivery_version='v'+str(version)
        if not a.runtime_feedback:break
        try:preview=request(route+'/delivery-preview',{'deliveryVersion':delivery_version})
        except urllib.error.HTTPError as failure:
            raw=failure.read(32769)
            if failure.code!=422 or len(raw)>32768:raise
            receipt=json.loads(raw)
            if receipt.get('code') not in ['DELIVERY_SELECTION_INVALID','DELIVERY_CONTENT_INVALID']:raise
            save(delivery_version+'-actual-protocol-rejection.json',{'httpStatus':failure.code,'receipt':receipt})
            text='Runtime delivery protocol validation rejected this actual candidate. Correct the project delivery selection/content and finish again in this same Session. Actual receipt: '+json.dumps(receipt)
            save(delivery_version+'-protocol-feedback.json',request(route+'/messages',{'text':text},'protocol-feedback-'+session+'-'+delivery_version))
            continue
        save(delivery_version+'-candidate.json',preview)
        errors=[];values=None
        for artifact in preview['artifacts']:
            if artifact['type']!='RESULT':continue
            content=request(route+'/delivery-content',{'deliveryVersion':delivery_version,'path':artifact['path']})
            data=base64.b64decode(content['data'])
            if hashlib.sha256(data).hexdigest()!=artifact['sha256'] or len(data)!=artifact['size']:raise RuntimeError('Candidate content integrity differs')
            value=json.loads(data)
            if isinstance(value,dict) and {'rows','total','mean','dataStatus'}<=value.keys():values=value
        if values is None:errors.append({'code':'STRUCTURED_RESULT_SCHEMA','message':'No declared RESULT with required statistics keys'})
        else:
            for field,expected in [('rows',3),('total',47.5),('mean',47.5/3)]:
                actual=values[field]
                if type(actual) not in [int,float] or abs(actual-expected)>1e-9:errors.append({'code':'STATISTIC_PRECISION_OR_VALUE','field':field,'actual':actual,'absoluteTolerance':1e-9})
        save(delivery_version+'-runtime-validation.json',{'passed':not errors,'errors':errors,'source':'Independent CSV fixture arithmetic; no analysis source supplied'})
        if not errors:break
        feedback='Runtime independent candidate validation failed. Correct the actual project and regenerate its outputs and delivery selection. Actual validation receipt: '+json.dumps({'errors':errors})
        save(delivery_version+'-feedback.json',request(route+'/messages',{'text':feedback},'feedback-'+session+'-'+delivery_version))
    delivery=request(route+'/delivery',{'deliveryVersion':delivery_version});save('delivery_manifest.json',delivery)
    state=request(route+'/status');save('sealed-state.json',state)
    workspace=Path(cfg['workerRoot'])/state['workerId']/'workspace'
    result_files=[f for f in delivery['artifacts'] if f['type']=='RESULT']
    results=[json.loads((workspace/f['path']).read_text()) for f in result_files]
    values=next((v for v in results if {'rows','total','mean','dataStatus'}<=v.keys()),None)
    checks={'rows':values is not None and values['rows']==3,'total':values is not None and abs(values['total']-47.5)<1e-9,
        'mean':values is not None and abs(values['mean']-47.5/3)<1e-9,
        'multipleSourceFiles':sum(f['type']=='SOURCE' and f['path'].endswith('.py') for f in delivery['artifacts'])>=2,
        'png':any(f['type']=='CHART' and (workspace/f['path']).read_bytes().startswith(b'\x89PNG\r\n\x1a\n') for f in delivery['artifacts']),
        'markdown':any(f['type']=='REPORT' and (workspace/f['path']).stat().st_size>20 for f in delivery['artifacts']),
        'stopped':state['stopProof']['running'] is False}
    journal=json.loads((workspace.parent/'sealed-native-events.json').read_text());events=journal['events']
    repair_observation=observe_python_repair(events);save('python-repair-observation.json',repair_observation)
    checks['realTypeError']=repair_observation['actualErrorObserved']
    checks['subsequentToolSuccess']=repair_observation['subsequentPythonExecution']
    save('independent-local-verification.json',{'checks':checks,'passed':all(checks.values()),'realModel':True,'realSiloAcceptance':False,'applicationHttpSpiAcceptance':False,
        'elapsedSeconds':time.monotonic()-started,'modelRequests':sum(e['eventType']=='model.iteration.started' for e in events),
        'toolCompleted':sum(e['eventType']=='tool.completed' for e in events),'toolFailed':sum(e['eventType']=='tool.failed' for e in events),
        'finalState':'SEALED_PENDING_UPLOAD','source':'Native journal and actual sealed local artifacts'})
    print(json.dumps({'case':a.case,'checks':checks,'pendingSilo':True,'sessionId':session}))
    if not all(checks.values()):raise RuntimeError('Independent local verification failed; sealed evidence retained, no business acceptance')
except Exception as error:
    save('failure.json',{'error':str(error),'elapsedSeconds':time.monotonic()-started,'failedSampleRetained':True})
    try:save('cancel.json',request(route+'/cancel',{}))
    except Exception as stop:save('cancel-error.json',{'error':str(stop)})
    raise
