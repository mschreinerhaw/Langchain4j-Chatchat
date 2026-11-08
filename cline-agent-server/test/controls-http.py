"""Real native permission denial and in-flight shell cancellation over HTTP only."""
import pathlib,json,sys,time,urllib.request,urllib.error,uuid
config=json.loads(pathlib.Path(sys.argv[1]).read_text());root=pathlib.Path(sys.argv[2]);root.mkdir(parents=True,exist_ok=True)
base='http://127.0.0.1:'+str(config.get('port',18326));records=[]
def api(path,body=None,expected=200):
 req=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),{'Authorization':'Bearer '+config['apiToken'],'Content-Type':'application/json','Accept':'application/json','Idempotency-Key':uuid.uuid4().hex})
 try:
  with urllib.request.urlopen(req,timeout=70) as res:code=res.status;data=json.load(res)
 except urllib.error.HTTPError as e:code=e.code;data=json.load(e)
 records.append({'method':req.get_method(),'path':path,'request':body,'status':code,'response':data});(root/'http.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
 if code!=expected:raise RuntimeError(str((code,data)))
 return data
def status(s):return api('/v1/sessions/'+s+'/status')
def wait(s,states,seconds=100):
 start=time.monotonic()
 while time.monotonic()-start<seconds:
  d=status(s)
  if d['status'] in states:return d
  if d['status']=='failed':raise RuntimeError('Native failure')
  time.sleep(1)
 raise TimeoutError('Native control fixture did not reach '+str(states))
def create():
 s=api('/v1/sessions',{'workspaceRef':'monitor-editor'},201)['sessionId'];wait(s,['idle']);return s
def cancel(s):api('/v1/sessions/'+s+'/cancel',{},202);return wait(s,['cancelled'],30)
def events(s):return api('/v1/sessions/'+s+'/events')['events']
def sse_one(s,after):
 req=urllib.request.Request(base+'/v1/sessions/'+s+'/events',headers={'Authorization':'Bearer '+config['apiToken'],'Last-Event-ID':str(after),'Accept':'text/event-stream'})
 with urllib.request.urlopen(req,timeout=20) as res:
  for line in res:
   if line.startswith(b'data: '):return json.loads(line[6:])
 raise RuntimeError('No SSE record')
# Retire only completed coding workers, preserving their journals and changes.
for p in pathlib.Path(config['stateDir'].replace('/poc/','/opt/cline-agent-server-poc-20261108/')).glob('*/session.json'):
 d=json.loads(p.read_text())
 if d['status']=='turn_completed':cancel(d['id'])
 elif d['status'] not in ['cancelled','recoverable','failed']:raise RuntimeError('Refusing to interrupt unfinished coding acceptance')
workspace=pathlib.Path('/opt/cline-agent-server-poc-20261108/project/coding-agent-node')
assert not (workspace/'poc-denied-marker.txt').exists()
s=create();api('/v1/sessions/'+s+'/messages',{'text':'在当前项目根目录创建 poc-denied-marker.txt，内容为 permission-test。不要创建其他文件，不要修改现有文件。'},202)
denied=0;start=time.monotonic()
while time.monotonic()-start<240:
 d=status(s)
 for p in d.get('pendingPermissions',[]):
  decision='approve' if p['toolName'] in ['read_files','search_codebase'] else 'deny'
  api('/v1/permissions/'+p['id']+'/respond',{'decision':decision,'reason':'Permission denial acceptance: file writes and commands are not authorized for this request'})
  if decision=='deny':denied+=1
 if d['status'] in ['turn_completed','awaiting_input','failed'] and denied:break
 time.sleep(1)
assert denied>0,'No real mutating native permission was observed'
assert not (workspace/'poc-denied-marker.txt').exists(),'Denied operation modified workspace'
denial_events=events(s);(root/'denial-events.json').write_text(json.dumps(denial_events,ensure_ascii=False,indent=2));cancel(s)
# Confirm the old native resolver cannot receive a late HTTP approval.
old=next(e['payload']['id'] for e in denial_events if e['eventType']=='permission.requested' and e['payload']['toolName'] not in ['read_files','search_codebase'])
api('/v1/permissions/'+old+'/respond',{'decision':'approve'},409)
s=create();prompt='执行且等待这条长运行 Shell 命令，以便我测试取消；不要改其他文件：node -e "setTimeout(()=>require(\'fs\').writeFileSync(\'poc-cancel-late.txt\',\'late\'),30000);setInterval(()=>{},1000)"'
api('/v1/sessions/'+s+'/messages',{'text':prompt},202)
wait(s,['awaiting_permission'],180)
d=status(s);p=next(p for p in d['pendingPermissions'] if p['toolName']=='run_commands')
assert 'poc-cancel-late.txt' in json.dumps(p['input'])
api('/v1/permissions/'+p['id']+'/respond',{'decision':'approve'})
start=time.monotonic()
while time.monotonic()-start<30:
 ev=events(s)
 if any(e['eventType']=='tool.started' and e['payload'].get('toolName')=='run_commands' for e in ev):break
 time.sleep(0.5)
else:raise RuntimeError('Native shell did not start')
time.sleep(2)
active=status(s);first=sse_one(s,max(0,int(active['lastEventId'])-1));time.sleep(0.25)
assert status(s)['status']=='running','SSE disconnect stopped native shell'
cancel(s)
replayed=sse_one(s,first['eventId']);assert int(replayed['eventId'])>int(first['eventId'])
ev=events(s);stopped=[e['payload'] for e in ev if e['eventType']=='task.cancelled'][-1]
assert stopped['localStopConfirmed'] and len(stopped['localProcesses'])>=2,'No native shell descendant stop evidence'
time.sleep(3);assert not (workspace/'poc-cancel-late.txt').exists()
(root/'cancel-events.json').write_text(json.dumps(ev,ensure_ascii=False,indent=2))
report={'denial':'PASS','deniedNativeRequests':denied,'deniedMarkerAbsent':True,'lateApproval':'PASS','cancelRunningNativeShell':'PASS','sseDisconnectWhileRunning':'PASS','lastEventIdReplayAfterDisconnect':{'before':first['eventId'],'replayed':replayed['eventId']},'localStopProof':stopped,'lateMarkerAbsent':True,'scope':'No external MCP/Sandbox operations configured in these control cases'}
(root/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report))
