"""Real authenticated Java -> Native Cline -> Python -> Silo acceptance.
Only uploads explicitly synthetic input and an objective, never analysis code.
No Node administration, S3 key or task grant is used by this client.
"""
import argparse,hashlib,json,time,urllib.request,urllib.error,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--origin',required=True);p.add_argument('--auth-file',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--case',required=True);p.add_argument('--input-file',type=Path);p.add_argument('--analysis-entry',action='store_true');a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=False);auth=json.loads(a.auth_file.read_text());token=None
def save(name,value):(a.output/name).write_text(json.dumps(value,ensure_ascii=False,indent=2))
def request(path,body=None,raw=False,content_type='application/json'):
    data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
    headers={'Content-Type':content_type}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(a.origin+path,data=data,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=1300) as response:
            value=response.read()
            if raw:return value
            decoded=json.loads(value)
            if decoded.get('code',200)!=200:raise RuntimeError('Java rejected request: '+str(decoded.get('message')))
            return decoded.get('data',decoded)
    except urllib.error.HTTPError as error:
        value=error.read();save('http-failure-'+str(time.time_ns())+'.json',{'path':path,'httpStatus':error.code,'receipt':json.loads(value) if value else None});raise
started=time.monotonic()
try:
    token=request('/api/v1/enterprise/auth/login',auth)['token']
    data=a.input_file.read_bytes() if a.input_file else b'label,amount\nalpha,12.5\nbeta,20\ngamma,15\n';boundary='native-'+uuid.uuid4().hex
    name=a.input_file.name if a.input_file else 'expenses.csv'
    multipart=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode()
    original=request('/api/v1/data-science/user-assets',multipart,content_type='multipart/form-data; boundary='+boundary);save('uploaded-input.json',original)
    assert hashlib.sha256(data).hexdigest()==original['sha256']
    schema={'type':'object','properties':{'rows':{'type':'integer'},'total':{'type':'number'},'mean':{'type':'number'},'dataStatus':{'type':'string'}},'required':['rows','total','mean','dataStatus'],'additionalProperties':False}
    objective='This is explicitly SYNTHETIC acceptance data, not customer data. Read the authorized CSV with pandas; do not replace the input. Compute rows, total and mean of amount. Build a meaningful multi-file Python project with at least two source modules. Initially introduce and execute a real str-plus-int TypeError, inspect the actual traceback, then repair the code and execute it successfully. Do not mask the failure exit code. Generate a Markdown report, a JSON RESULT matching the supplied schema (dataStatus SYNTHETIC), and a Matplotlib PNG chart. JSON mean must preserve precision to absolute error <=1e-9; report display can be formatted. Choose all source names yourself. Execute and inspect final results before finishing.'
    if name.endswith('.parquet'):objective=objective.replace('CSV with pandas','Parquet with pandas')
    objective+=' Required RESULT JSON shape: '+json.dumps(schema)+'.'
    body={'requestId':a.case,'fileIds':[original['id']],'instruction':objective,'modelName':'qwen3.7-plus','artifactTypes':['SOURCE','REPORT','RESULT','CHART'],'resultSchema':schema}
    save('submission.json',body)
    if a.analysis_entry:
        submitted=request('/api/v1/data-science/user-assets/analyses',{k:v for k,v in body.items() if k not in ['artifactTypes','resultSchema']});save('normal-analysis-response.json',submitted)
    else:submitted=request('/api/v1/coding/tasks',body)
    save('accepted-task.json',submitted);print(json.dumps({'accepted':True,'taskId':submitted['taskId'],'runId':submitted['runId']}),flush=True)
    route='/api/v1/coding/tasks/runs/'+submitted['runId']+'/'+submitted['taskId'];deadline=time.time()+1250
    while time.time()<deadline:
        state=request(route);save('state.json',state)
        if state['status'] not in ['CREATED','RUNNING']:break
        time.sleep(1)
    else:raise RuntimeError('Java Native task deadline exceeded')
    if state['status']!='COMPLETED':raise RuntimeError('Java task did not complete: '+state['status']+' '+state.get('summary',''))
    monitor='/api/v1/coding-monitor/tasks/'+submitted['runId']+'/'+submitted['taskId']
    detail=request(monitor);save('java-task-monitor.json',detail);page=request(monitor+'/events?afterSequence=0&limit=256');save('java-events.json',page)
    files=request(monitor+'/artifacts');save('registered-silo-files.json',files);values=[];sources=[];charts=[];reports=[];hashes=[]
    for f in files['files']:
        if f['kind'] not in ['CODE','ARTIFACT']:continue
        content=request(monitor+'/artifacts/'+f['id']+'/content?download=true',raw=True)
        digest=hashlib.sha256(content).hexdigest();assert len(content)==f['size'] and digest==f['sha256']
        hashes.append({'assetId':f['id'],'fileName':f['fileName'],'sha256':digest,'size':len(content)})
        (a.output/(f['id']+'-'+f['fileName'])).write_bytes(content)
        if f['fileName'].endswith('.json'):
            try:
                v=json.loads(content)
                if isinstance(v,dict) and {'rows','total','mean'}<=v.keys():values.append(v)
            except ValueError:pass
        if f['fileName'].endswith('.py'):sources.append(f)
        if f['fileName'].endswith('.png'):charts.append(content.startswith(b'\x89PNG\r\n\x1a\n'))
        if f['fileName'].endswith('.md'):reports.append(len(content)>20)
    correct=any(v['rows']==3 and abs(v['total']-47.5)<1e-9 and abs(v['mean']-47.5/3)<1e-9 and v.get('dataStatus')=='SYNTHETIC' for v in values)
    checks={'normalJavaApiCompleted':True,'independentStatistics':correct,'multipleDynamicSources':len(sources)>=2,'markdown':any(reports),'png':any(charts),'allRegisteredHashesVerified':bool(hashes),'realModelReceipts':bool(detail.get('modelReceipts')),'javaDoesNotClaimBusinessValidation':state['validationStatus']=='INCONCLUSIVE','executorStopConfirmed':state['executionControl']['executorStopped'] is True}
    executions=detail.get('executions',[]);repairs=detail.get('repairs',[])
    if executions and any('stdoutAssetId' in e for e in executions):
        checks['observedPythonException']=any(e.get('exceptionSummary','').startswith('TypeError:') for e in executions)
        checks['sourceChangeAndRerunEvidence']=any(r.get('verified') and r.get('changedSources') for r in repairs)
    result={'passed':all(checks.values()),'checks':checks,'elapsedSeconds':time.monotonic()-started,'taskId':submitted['taskId'],'runId':submitted['runId'],'downloadedObjects':hashes,'realModel':True,'realSilo':True,'applicationHttpSpiAcceptance':True,'existingAnalysisEntry':a.analysis_entry,'businessScenario':'SYNTHETIC_'+('PARQUET' if name.endswith('.parquet') else 'CSV')+'_STATISTICS'}
    save('verification.json',result);print(json.dumps(result),flush=True)
    if not result['passed']:raise RuntimeError('Independent HTTP acceptance checks failed')
except Exception as error:
    save('failure.json',{'type':type(error).__name__,'message':str(error),'elapsedSeconds':time.monotonic()-started,'failedSampleRetained':True});raise
