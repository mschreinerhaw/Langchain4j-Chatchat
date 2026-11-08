"""Exercise native code search and same-session follow-up through HTTP, without changing code."""
import json,pathlib,sys,time,uuid,urllib.request
c=json.loads(pathlib.Path(sys.argv[1]).read_text());out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True);prior=json.loads(pathlib.Path(sys.argv[3]).read_text());s=prior['sessionId'];base='http://127.0.0.1:'+str(c['port']);trace=[]
def api(path,body=None):
 req=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),{'Authorization':'Bearer '+c['apiToken'],'Content-Type':'application/json','Accept':'application/json','Idempotency-Key':uuid.uuid4().hex})
 with urllib.request.urlopen(req,timeout=70) as r:d=json.load(r);status=r.status
 trace.append({'method':req.get_method(),'path':path,'status':status,'request':body,'response':d});(out/'http.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2));return d
before=api('/v1/sessions/'+s+'/status');assert before['status']=='turn_completed'
prompt='继续同一会话，只做源码定位而不修改文件：请使用原生 search_codebase 搜索 createMonitorClient 的实现以及新增参数校验测试，再使用 read_files 核对其中的关键位置，报告文件路径和参数边界。此次不要使用 Shell 替代 search_codebase，不需要重新构建。'
accepted=api('/v1/sessions/'+s+'/messages',{'text':prompt});start=time.monotonic()
while time.monotonic()-start<300:
 d=api('/v1/sessions/'+s+'/status')
 for p in d.get('pendingPermissions',[]):
  decision='approve' if p['toolName'] in ['search_codebase','read_files'] else 'deny'
  api('/v1/permissions/'+p['id']+'/respond',{'decision':decision,'reason':None if decision=='approve' else 'Read-only native search fixture'})
 if d['status'] in ['turn_completed','failed','awaiting_input']:break
 time.sleep(1)
events=api('/v1/sessions/'+s+'/events')['events'];events=[e for e in events if int(e['eventId'])>int(before['lastEventId'])]
(out/'events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2))
success=any(e['eventType']=='tool.completed' and e['payload'].get('toolName')=='search_codebase' for e in events)
report={'status':'PASS' if success and d['status']=='turn_completed' else 'FAIL','sessionId':s,'nativeSessionId':d['nativeSessionId'],'sameNativeSession':d['nativeSessionId']==before['nativeSessionId'],'nativeSearchObserved':success,'prompt':prompt,'taskId':accepted['taskId']}
(out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False))
api('/v1/sessions/'+s+'/cancel',{})
for _ in range(40):
 if api('/v1/sessions/'+s+'/status')['status']=='cancelled':break
 time.sleep(0.5)
