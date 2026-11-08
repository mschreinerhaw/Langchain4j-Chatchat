"""Real-model acceptance driver. Never edits model output or executes its coding task.

Run on the POC host: python3 live-http.py CONFIG_FILE OUTPUT_DIR editor|apply_patch
Config includes local API token; it is never written to evidence.
"""
import json,pathlib,sys,time,urllib.request,urllib.error,uuid,hashlib
config=json.loads(pathlib.Path(sys.argv[1]).read_text());out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
profile=sys.argv[3];base='http://127.0.0.1:'+str(config.get('port',18326));trace=[]
def api(route,body=None,headers=None,expected=200):
 h={'Authorization':'Bearer '+config['apiToken'],'Content-Type':'application/json'};h.update(headers or {})
 req=urllib.request.Request(base+route,None if body is None else json.dumps(body).encode(),h)
 try:
  with urllib.request.urlopen(req,timeout=75) as res:code=res.status;result=json.load(res)
 except urllib.error.HTTPError as e:code=e.code;result=json.load(e)
 trace.append({'method':req.get_method(),'path':route,'status':code,'request':body,'response':result})
 (out/'http.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2))
 if code!=expected:raise RuntimeError(str((route,code,result)))
 return result
def state():return api('/v1/sessions/'+session+'/status')
def wait_for(predicate,seconds=110):
 start=time.monotonic()
 while time.monotonic()-start<seconds:
  s=state()
  if predicate(s):return s
  if s['status']=='failed':raise RuntimeError('Native task failed; inspect events')
  time.sleep(1)
 raise TimeoutError('State deadline')
def sse_read(after,limit):
 req=urllib.request.Request(base+'/v1/sessions/'+session+'/events',headers={'Authorization':'Bearer '+config['apiToken'],'Last-Event-ID':str(after),'Accept':'text/event-stream'})
 records=[];current={}
 with urllib.request.urlopen(req,timeout=20) as res:
  while len(records)<limit:
   line=res.readline().decode().rstrip('\r\n')
   if line.startswith('data: '):current=json.loads(line[6:])
   if not line and current:records.append(current);current={}
 (out/('sse-after-'+str(after)+'.json')).write_text(json.dumps(records,ensure_ascii=False,indent=2))
 return records
for attempt in range(60):
 try:
  with urllib.request.urlopen(base+'/health/live',timeout=2) as res:
   if res.status==200:break
 except (OSError,urllib.error.URLError):time.sleep(0.5)
else:raise RuntimeError('POC API did not become live')
created=api('/v1/sessions',{'workspaceRef':'monitor-patch' if profile=='apply_patch' else 'monitor-editor','editTool':profile},expected=201)
session=created['sessionId'];(out/'session.json').write_text(json.dumps(created,indent=2))
wait_for(lambda s:s['status']=='idle')
first=sse_read(0,1);replay=sse_read(first[-1]['eventId'],1)
assert int(replay[0]['eventId'])>int(first[-1]['eventId'])
prompt=pathlib.Path(__file__).with_name('task.txt').read_text()
(out/'task-manifest.json').write_text(json.dumps({'profile':profile,'prompt':prompt,'promptSha256':hashlib.sha256(prompt.encode()).hexdigest(),'model':config['model'],'workspaceRef':'monitor-patch' if profile=='apply_patch' else 'monitor-editor','sourceCommit':'a459f1d04dea030833b558be645011ab739c463b','realProjectSourceCommit':'7d076d0dc','budgetSeconds':1000},ensure_ascii=False,indent=2))
key=uuid.uuid4().hex;body={'text':prompt}
accepted=api('/v1/sessions/'+session+'/messages',body,{'Idempotency-Key':key},202)
again=api('/v1/sessions/'+session+'/messages',body,{'Idempotency-Key':key},202);assert accepted==again
api('/v1/sessions/'+session+'/messages',{'text':'different'}, {'Idempotency-Key':key},409)
start=time.monotonic();approved=set();count=0
while time.monotonic()-start<1000:
 s=state()
 for p in s.get('pendingPermissions',[]):
  if p['id'] in approved:continue
  # Explicit task-scope policy. Native executors remain responsible for the action.
  tool=p['toolName'];allowed=tool in ['read_files','search_codebase','editor','apply_patch']
  if tool=='run_commands':
   text=json.dumps(p['input'])
   allowed=not any(word in text for word in ['/poc/private','/poc/state','/etc/','curl ','wget ','sudo ','rm -'])
  decision='approve' if allowed else 'deny'
  result=api('/v1/permissions/'+p['id']+'/respond',{'decision':decision,'reason':None if allowed else 'Outside fixed monitor client coding task'})
  assert result.get('applied')
  if count==0:
   api('/v1/permissions/'+p['id']+'/respond',{'decision':decision})
   api('/v1/permissions/'+p['id']+'/respond',{'decision':'deny' if decision=='approve' else 'approve'},expected=409)
  approved.add(p['id']);count+=1
 if s['status'] in ['turn_completed','failed','cancelled','awaiting_input']:break
 time.sleep(1)
else:
 api('/v1/sessions/'+session+'/cancel',{},expected=202);raise TimeoutError('Real task exceeded budget')
events=api('/v1/sessions/'+session+'/events',headers={'Accept':'application/json'})['events']
(out/'events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2))
diff=api('/v1/sessions/'+session+'/diff');(out/'native-changes.patch').write_text(diff['patch'])
tools=[e['payload'] for e in events if e['eventType'] in ['tool.completed','tool.failed']]
def tool_failed(t):
 output=t.get('output');values=output if isinstance(output,list) else [output]
 return bool(t.get('error')) or any(isinstance(v,dict) and (v.get('success') is False or v.get('error')) for v in values)
report={'sessionId':session,'taskId':accepted['taskId'],'profile':profile,'model':config['model'],'status':s['status'],'durationSeconds':round(time.monotonic()-start,2),'approvalCount':count,'completedTools':[{'name':t.get('toolName'),'failed':tool_failed(t),'toolCallId':t.get('toolCallId')} for t in tools],'sseReconnect':'PASS','idempotency':'PASS','independentVerification':'PENDING','coreToolsObserved':{name:any(t.get('toolName')==name and not tool_failed(t) for t in tools) for name in ['read_files','search_codebase',profile,'run_commands']}}
(out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False))
