"""Authenticate on the deployment host and validate workbench receipts with synthetic data.

Credentials and raw task payloads stay in the private release directory. Public
evidence contains execution identities, states and bounded presentation fields.
"""
import argparse,json,os,time,uuid,urllib.request,urllib.parse
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--auth-file',required=True,type=Path)
p.add_argument('--output-dir',required=True,type=Path)
p.add_argument('--action',choices=['inspect','run','collect'],default='run')
p.add_argument('--task-id')
p.add_argument('--case',choices=['normal','cancel'],default='normal')
p.add_argument('--model-source-task',default='56d610a8-d065-4389-aefa-252b42c3ccb5')
a=p.parse_args();os.umask(0o077);a.output_dir.mkdir(parents=True,exist_ok=True)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}));token=None
def call(path,body=None,content='application/json'):
    headers={'Content-Type':content}
    if token:headers['Authorization']='Bearer '+token
    data=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
    with client.open(urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=data,headers=headers),timeout=60) as r:
        return json.load(r)['data']
auth_input=json.loads(a.auth_file.read_text())
if 'token' in auth_input:
    token=auth_input['token'];auth=auth_input
    if 'user' not in auth:auth['user']=call('/enterprise/auth/me')
else:
    auth=call('/enterprise/auth/login',json.dumps(auth_input).encode());token=auth['token']
tenant=auth['user']['tenantId'];q='?tenantId='+urllib.parse.quote(tenant)
(a.output_dir/'browser-auth.private.json').write_text(json.dumps({'token':token,'user':auth['user']}))
prior=call('/agent/tasks/'+a.model_source_task+q)
if a.action=='inspect':
    print(json.dumps({key:prior.get(key) for key in ['taskId','modelName','canonicalState','status']}));raise SystemExit
if a.action=='run':
    boundary='wb'+uuid.uuid4().hex
    body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="workbench-validation.csv"\r\nContent-Type: text/csv\r\n\r\namount\n1.25\n2.50\n\r\n--'+boundary+'--\r\n').encode()
    asset=call('/interactions/user-assets',body,'multipart/form-data; boundary='+boundary)
    request={'mode':'llm_chat','modelName':prior.get('modelName'),'requestSource':'workbench-acceptance',
      'query':'Execute the SQL using ONLY the attached CSV. Map it to an in-memory SQLite table named input_csv. Compute the actual total with Python, report its numeric value, cite the file, and never connect to an external datasource.\n```sql\nSELECT SUM(amount) AS total FROM input_csv;\n```',
      'toolInput':{'__userAssetIds':[asset['id']]},'sessionId':str(uuid.uuid4()),'idempotencyKey':str(uuid.uuid4())}
    task=call('/agent/tasks',request);task_id=task['taskId']
    (a.output_dir/'request.private.json').write_text(json.dumps(request))
    (a.output_dir/'submission.json').write_text(json.dumps(task))
    print(json.dumps({'submittedTaskId':task_id}),flush=True)
else:task_id=a.task_id
assert task_id
events=[];cursor=0;deadline=time.monotonic()+660;last=None
cancel_at=time.monotonic()+10;cancel_requested=False
while True:
    task=call('/agent/tasks/'+task_id+q)
    page=call('/agent/tasks/'+task_id+'/events'+q+'&limit=200&afterSequence='+str(cursor))
    if page:
        following=max(int(e.get('sequence') or 0) for e in page)
        assert following>cursor,'History cursor stalled'
        cursor=following;events.extend(page)
    state=task.get('canonicalState')
    if state!=last:print(json.dumps({'taskId':task_id,'state':state,'events':len(events)}),flush=True);last=state
    (a.output_dir/'task.private.json').write_text(json.dumps({'task':task,'events':events}))
    if state in {'SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED'} and len(page)<200:break
    if a.action=='run' and a.case=='cancel' and not cancel_requested and time.monotonic()>=cancel_at:
        call('/agent/tasks/'+task_id+'/cancel'+q,{})
        cancel_requested=True
        print(json.dumps({'taskId':task_id,'cancellationRequested':True}),flush=True)
    if a.action=='collect' and not page:break
    if time.monotonic()>deadline:
        call('/agent/tasks/'+task_id+'/cancel'+q,{})
        raise RuntimeError('Acceptance task exceeded verifier deadline; cancellation requested')
    time.sleep(2)
safe=[];operations=[]
for event in events:
    try:payload=json.loads(event.get('payload') or '{}') if isinstance(event.get('payload'),str) else event.get('payload') or {}
    except ValueError:continue
    runtime=payload.get('payload',payload);meta=runtime.get('metadata',{})
    facts={k:meta[k] for k in ['eventKind','eventState','stage','taskId','stepId','eventId','sequence','occurredAt','codingEventType','operationId','attemptId','executorConfirmed','executorStopped','observedAt','timeBasis','planningOwner'] if k in meta}
    worker=meta.get('workerActivity',meta.get('payload',{}))
    if isinstance(worker,dict):facts['workerActivity']={k:worker[k] for k in ['type','timestamp','modelCall','startedAt','finishedAt','durationMs','repairAttempt','repairAttempts','scriptVersion','tool','operationId','toolCallId','codeSha256','status','validationStatus','admissionPending'] if k in worker}
    if facts.get('eventKind')=='ANALYSIS_OPERATION':operations.append(facts)
    safe.append({**{k:event[k] for k in ['eventId','attemptId','parentEventId','sequence','type','status','timestamp','createTime'] if k in event},'payload':{'metadata':facts}})
fixture={'id':'live-workbench','role':'assistant','taskId':task_id,'taskTitle':'真实 CSV 与 SQL 分析验收','status':task.get('status'),
  'canonicalState':task.get('canonicalState'),'streaming':False,'steps':safe,'executionSnapshot':{k:task.get(k) for k in ['attemptId','createTime','finishedAt','canonicalState']}}
(a.output_dir/'presentation-fixture.json').write_text(json.dumps(fixture,ensure_ascii=False))
summary={'case':a.case,'taskId':task_id,'attemptId':task.get('attemptId'),'canonicalState':task.get('canonicalState'),'eventCount':len(events),
  'executionObservations':len(operations),'confirmedExecutionObserved':any(e.get('executorConfirmed') for e in operations),
  'stoppedExecutionObserved':any(e.get('executorStopped') for e in operations),'workerTypes':sorted({e['payload']['metadata'].get('workerActivity',{}).get('type','') for e in safe}-{''})}
summary['expectedTotalReported']=any('3.75' in json.dumps(e.get('payload','')) for e in events if e.get('type') in {'RESULT','COMPLETE','RUNTIME_COMPLETED'})
try:
    coding=call('/agent/runtime/runs/'+task['attemptId']+'/coding')
    (a.output_dir/'coding.private.json').write_text(json.dumps(coding))
    summary['coding']=[{'taskId':child['taskId'],'status':child['outcome']['status'],
      'validationStatus':child['outcome']['validationStatus'],'usage':child['outcome'].get('usage',{}).get('consumed',{})} for child in coding.get('tasks',[])]
except urllib.error.HTTPError as error:summary['codingHttpStatus']=error.code
(a.output_dir/'verification.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary),flush=True)
