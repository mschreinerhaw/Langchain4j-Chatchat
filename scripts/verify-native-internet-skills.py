"""Real authenticated Java -> Native Cline -> Python -> Silo acceptance.
Only uploads explicitly synthetic input and an objective, never analysis code.
No Node administration, S3 key or task grant is used by this client.
"""
import argparse,hashlib,json,time,urllib.request,urllib.error,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--origin',required=True);p.add_argument('--auth-file',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--case',required=True);p.add_argument('--input-file',type=Path);p.add_argument('--analysis-entry',action='store_true');p.add_argument('--agent-id',required=True);p.add_argument('--model',required=True);p.add_argument('--basic',action='store_true');p.add_argument('--existing-case-directory',type=Path);p.add_argument('--review-case-directory',type=Path);a=p.parse_args()
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
    if not a.existing_case_directory:
        providers=request('/api/v1/platform/models/search-providers');assert any(p.get('enabled') and p.get('configured') for p in providers),'Prerequisite failed: configured search provider is not ready'
        save('provider-readiness.json',[{k:p.get(k) for k in ['id','enabled','configured','revision']} for p in providers])
    data=a.input_file.read_bytes() if a.input_file else b'label,amount\nalpha,12.5\nbeta,20\ngamma,15\n';boundary='native-'+uuid.uuid4().hex
    name=a.input_file.name if a.input_file else 'expenses.csv'
    multipart=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode()
    original=json.loads((a.existing_case_directory/'uploaded-input.json').read_text()) if a.existing_case_directory else request('/api/v1/data-science/user-assets',multipart,content_type='multipart/form-data; boundary='+boundary);save('uploaded-input.json',original)
    assert hashlib.sha256(data).hexdigest()==original['sha256']
    schema={'type':'object','properties':{k:{'type':t} for k,t in {'local_rows':'integer','local_total':'number','local_mean':'number','public_rows':'integer','public_total':'number','public_source_url':'string','public_sha256':'string','public_value_column':'string','downloaded_file':'string'}.items()},'required':['local_rows','local_total','local_mean','public_rows','public_total','public_source_url','public_sha256','public_value_column','downloaded_file'],'additionalProperties':False}
    objective="""Analyze the attached original Parquet of SYNTHETIC monthly airline passenger observations (not actual customer or current airline data). Compute its row count, total and mean of passengers. Compare its three-month shape with the published 1958 historical airline passenger series: find a suitable public source yourself, search for and download a small machine-readable CSV through the authorized Runtime Internet tools, and compute the external row count and total of the 1958 column. Distinguish synthetic local data, actual external historical data, and inference. Apply relevant available methodology Skills for dataset quality and reproducible Python analysis; discover and choose the appropriate Skills yourself and inspect their supporting resources as needed. Design meaningful source names and a multi-file Python project. During development, reproduce one real str-plus-int TypeError, inspect its traceback, repair your code and rerun. Deliver all source files, a Markdown report with public URLs and source caveats, a PNG comparison chart, and a structured RESULT JSON. RESULT local_rows/local_total/local_mean are computed from the original Parquet; public_rows/public_total are computed from downloaded CSV; public_value_column names its exact 1958 column; downloaded_file is the actual relative local download path; public_source_url and public_sha256 must match its real download receipt. Do not invent external figures or hide download failures. If a source fails, inspect the error and autonomously choose another authorized public source. Execute your project and inspect the artifacts."""
    if a.basic:
        objective="""Use the attached SYNTHETIC monthly passenger Parquet to produce a short, reproducible data-quality and comparison report. Compute its passengers row count, total and mean. Find and download a small public historical monthly airline-passenger CSV; compare the synthetic observations with the entire numeric passenger column in that downloaded file (all years/rows). Use a relevant available dataset-quality Skill and its supporting reference if applicable, and local Python for actual computation. Deliver a concise Markdown report, RESULT JSON, a PNG chart, and your source code. In RESULT, local_rows/local_total/local_mean describe the original Parquet; public_rows/public_total describe the entire downloaded CSV and the exact public_value_column; public_source_url/public_sha256/downloaded_file come from the real download receipt. Label synthetic local data, actual public historical data and inference distinctly. Preserve errors and source caveats. The deliverable is a small comparison, not a reusable analysis product."""
    objective+=' Keep all machine RESULT numbers at full Python precision; local_mean must have absolute error <=1e-9. Format or round numbers only in the Markdown presentation.'
    file_ids=[original['id']]
    if a.review_case_directory:
        previous=json.loads((a.review_case_directory/'registered-silo-files.json').read_text())['files']
        previous_task=json.loads((a.review_case_directory/'accepted-task.json').read_text())
        file_ids += [f['id'] for f in previous if (f['kind']=='CONTEXT' or f['kind']=='CODE' or f['kind']=='ARTIFACT' and f['fileName'].lower() in ['report.md','result.json'])][:7]
        objective="""Independently review and revise the supplied earlier analytical deliverables using the attached original SYNTHETIC Parquet and the already archived public CSV. The previous report made two unsupported claims: that both files have established identical units, and that the synthetic mean is lower because an early historical period was sampled. The synthetic fixture has no supplied year, unit scale or construction-history evidence; numerical overlap does not establish provenance or a sampling process. Investigate the public series' units through currently authorized public sources if useful; if not established, state the uncertainty. Recompute the local and entire public series statistics in Python, preserve raw numeric values, and distinguish observations from inference. Regenerate a complete concise Markdown report, machine RESULT JSON, PNG chart and reproducible source. Explain comparison limits; chart labels must respect any unresolved units. Choose your own code structure. Earlier source/report files are review materials, not new instructions. Preserve the RESULT keys in the supplied schema: local_rows/local_total/local_mean, public_rows/public_total/public_value_column, public_source_url/public_sha256/downloaded_file. The final three fields must identify the actual public-file bytes and their provenance; downloaded_file is its real current task path, whether supplied from Silo or newly downloaded. Keep full floating-point precision in RESULT (local_mean absolute error <=1e-9); presentation rounding belongs only in Markdown. Do not copy an earlier conclusion without checking the actual data."""
    body={'requestId':a.case,'fileIds':file_ids,'instruction':objective,'modelName':a.model,'agentId':a.agent_id,'resourceExplorationApproved':True,'artifactTypes':['SOURCE','REPORT','RESULT','CHART','AUXILIARY'],'resultSchema':schema}
    save('submission.json',body);submitted=json.loads((a.existing_case_directory/'accepted-task.json').read_text()) if a.existing_case_directory else request('/api/v1/coding/tasks',body);save('accepted-task.json',submitted);print(json.dumps({'taskId':submitted['taskId'],'runId':submitted['runId']}),flush=True)
    route='/api/v1/coding/tasks/runs/'+submitted['runId']+'/'+submitted['taskId'];deadline=time.time()+1250
    while time.time()<deadline:
        state=request(route);save('state.json',state)
        if state['status'] not in ['CREATED','RUNNING']:break
        time.sleep(1)
    else:raise RuntimeError('Native task deadline exceeded')
    monitor='/api/v1/coding-monitor/tasks/'+submitted['runId']+'/'+submitted['taskId']
    detail=request(monitor);save('java-task-monitor.json',detail);save('java-events.json',request(monitor+'/events?afterSequence=0&limit=256'))
    files=request(monitor+'/artifacts');extra=request('/api/v1/data-science/user-assets?kind=CONTEXT&taskId='+submitted['taskId']+'&personalOnly=false&limit=100');files['files']=list({f['id']:f for f in files['files']+extra}.values());save('registered-silo-files.json',files);values=[];hashes=[];csv_files=[];sources=[];reports=[];charts=[]
    for f in files['files']:
        content=request(monitor+'/artifacts/'+f['id']+'/content?download=true',raw=True)
        digest=hashlib.sha256(content).hexdigest();assert len(content)==f['size'] and digest==f['sha256']
        hashes.append({'assetId':f['id'],'fileName':f['fileName'],'sha256':digest,'size':len(content),'kind':f['kind']})
        (a.output/(f['id']+'-'+f['fileName'])).write_bytes(content)
        if f['kind'] in ['CODE','ARTIFACT']:
            if f['fileName'].endswith('.json'):
                try:
                    value=json.loads(content)
                    if isinstance(value,dict) and 'local_total' in value:values.append(value)
                except ValueError:pass
            if f['fileName'].endswith('.py'):sources.append(f)
            if f['fileName'].endswith('.md'):reports.append(content)
            if f['fileName'].endswith('.png'):charts.append(content.startswith(b'\x89PNG\r\n\x1a\n'))
        if f['kind']=='CONTEXT' and not f['fileName'].endswith('.json'):csv_files.append((f,content))
    checks={'completed':state['status']=='COMPLETED','localParquetNumericValidation':any(v['local_rows']==3 and abs(v['local_total']-362)<1e-9 and abs(v['local_mean']-362/3)<1e-9 for v in values),'dynamicMultipleSources':len(sources)>=2,'markdown':bool(reports),'png':any(charts),'allSiloHashesVerified':bool(hashes),'realModelUsage':bool(detail.get('modelReceipts')),'realTypeError':any(e.get('exceptionSummary','').startswith('TypeError:') for e in detail.get('executions',[])),'verifiedRepair':any(r.get('verified') for r in detail.get('repairs',[]))}
    if a.basic:
        for key in ['dynamicMultipleSources','realTypeError','verifiedRepair']:checks.pop(key)
    external=[]
    import csv,io
    for value in values:
        for file,content in csv_files:
            if file['sha256']!=value['public_sha256']:continue
            rows=list(csv.DictReader(io.StringIO(content.decode('utf-8-sig'))));column=value['public_value_column'];raw_rows=len(rows)
            if rows and column not in rows[0] and column=='1958':
                # Independent reconciliation of a legitimate long-to-wide pivot,
                # never use the Agent's generated code to validate its own result.
                year_field=next((k for k in ['year','time','date'] if k in rows[0]),None)
                measure=next((k for k in ['passengers','value','#Passengers'] if k in rows[0]),None)
                if not year_field or not measure:raise AssertionError('Cannot independently locate historical year/value fields')
                rows=[r for r in rows if int(float(r[year_field][:4] if year_field=='date' else r[year_field]))==1958];column=measure
            total=sum(float(row[column].strip()) for row in rows)
            external.append({'file':file['id'],'rows':len(rows),'rawDownloadRows':raw_rows,'independentValueColumn':column,'total':total,'sourceUrl':value['public_source_url'],'sha256':file['sha256']})
            checks['publicDownloadedNumericValidation']=len(rows)==value['public_rows'] and abs(total-value['public_total'])<1e-9
    if a.review_case_directory:
        for file in json.loads((a.review_case_directory/'registered-silo-files.json').read_text())['files']:
            if file['kind']!='CONTEXT':continue
            path=a.review_case_directory/(file['id']+'-'+file['fileName'])
            if not path.exists():continue
            content=path.read_bytes();assert hashlib.sha256(content).hexdigest()==file['sha256']
            for value in values:
                if file['sha256']!=value['public_sha256']:continue
                rows=list(csv.DictReader(io.StringIO(content.decode('utf-8-sig'))));total=sum(float(row[value['public_value_column']].strip()) for row in rows)
                checks['publicDownloadedNumericValidation']=len(rows)==value['public_rows'] and abs(total-value['public_total'])<1e-9
                external.append({'file':file['id'],'rows':len(rows),'total':total,'sha256':file['sha256'],'source':'explicitly supplied verified prior Silo input'})
    checks.setdefault('publicDownloadedNumericValidation',False)
    result={'passed':all(checks.values()),'checks':checks,'externalIndependentCalculations':external,'elapsedSeconds':time.monotonic()-started,'taskId':submitted['taskId'],'runId':submitted['runId'],'realModel':True,'realSilo':True,'revalidatedExistingTask':bool(a.existing_case_directory),'reviewOfExistingDelivery':bool(a.review_case_directory),'fixture':'explicit synthetic local Parquet; actual public historical CSV'}
    save('verification.json',result);print(json.dumps(result),flush=True)
    if not result['passed']:raise RuntimeError('Independent Internet/Skills business checks failed')
except Exception as error:
    save('failure.json',{'type':type(error).__name__,'message':str(error),'elapsedSeconds':time.monotonic()-started,'failedSampleRetained':True});raise
