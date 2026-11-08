#!/usr/bin/env python3
"""Actual model task on official Cline Worker provisioned through restricted Executor."""
import pathlib,json,subprocess,time,urllib.request,urllib.error,uuid,base64,hmac,hashlib
R=pathlib.Path('/opt/cline-server-p33-stage1-20261108');E=R/'evidence';cfg=json.loads((R/'config/gateway.json').read_text());D=['docker','-H','unix://'+str(R/'run/docker.sock')]
tid=next(iter(cfg['tenants']));t=cfg['tenants'][tid]
c={'version':1,'tenantId':tid,'userId':t['users'][0],'taskId':'native-'+uuid.uuid4().hex,'sessionId':'session-'+uuid.uuid4().hex,'workspaceRef':next(iter(t['workspaces'])),'modelProfile':t['models'][0],'toolPermissions':t['tools'],'dataPermissions':t['datasets'],'budgetPolicy':{'status':'DEFERRED_STAGE_2_REVIEW'},'networkPolicy':'broker-only','expiresAt':int(time.time()*1000)+3600000}
b=base64.urlsafe_b64encode(json.dumps(c,separators=(',',':')).encode()).rstrip(b'=');token=(b+b'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),b,hashlib.sha256).digest()).rstrip(b'=')).decode();root='/v1/sessions/'+c['sessionId'];checks=[]
def save(name,v):(E/name).write_text(json.dumps(v,indent=2))
def api(path,body=None,key=None):
 req=urllib.request.Request('http://127.0.0.1:18331'+path,data=None if body is None else json.dumps(body).encode(),headers={'authorization':'Bearer '+token,'content-type':'application/json','accept':'application/json',**({'idempotency-key':key} if key else {})})
 try:
  with urllib.request.urlopen(req,timeout=100) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
def check(name,ok,detail):checks.append({'case':name,'pass':bool(ok),'detail':detail});save('native-results.json',checks);print(name,'PASS' if ok else 'FAIL',flush=True)
save('native-contract.json',c);created=api('/v1/sessions',{},'p331-create');check('Gateway-Executor-create',created[0]==201,created)
for _ in range(180):
 s=api(root+'/status')[1]
 if s.get('workerState') in ('READY','START_FAILED','RECOVERABLE'):break
 time.sleep(.5)
check('official-Cline-ready',s.get('workerState')=='READY',s)
if s.get('workerState')!='READY':raise SystemExit(1)
submitted=api(root+'/messages',{'text':'Read /poc/project/README.md using native read_files. Then use native run_commands to execute node --version. Report the results. Do not edit any files, do not use MCP, and do not run other commands.'},'p331-task');check('real-model-submission',submitted[0]==202,submitted)
approvals=[]
for _ in range(300):
 s=api(root+'/status')[1]
 for p in (s.get('native') or {}).get('pendingPermissions',[]):
  response=api('/v1/permissions/'+p['id']+'/respond',{'decision':'approve','reason':'P3.3.1 actual native Worker smoke test'});approvals.append({'permission':p,'response':response})
 if s.get('workerState') in ('COMPLETED','RECOVERABLE','STOPPED','START_FAILED'):break
 time.sleep(.5)
save('native-approvals.json',approvals);events=api(root+'/events');save('native-events.json',events)
tools=[e['payload'] for e in events[1].get('events',[]) if e['eventType']=='tool.completed'] if events[0]==200 else []
names=[x.get('toolName') for x in tools];check('autonomous-native-read-and-shell',s.get('workerState')=='COMPLETED' and 'read_files' in names and 'run_commands' in names,{'status':s,'nativeTools':tools})
wid=s['workerId'];reg=json.loads((R/'control-state/workers'/wid/'registry.json').read_text());ci=json.loads(subprocess.check_output([*D,'inspect',reg['containerId']]))[0];save('native-worker-inspect.json',ci)
diff=api(root+'/diff');check('project-unchanged',diff[0]==200 and diff[1].get('patch')=='',diff)
cancel=api(root+'/cancel',{});check('native-Worker-stop-request',cancel[0]==202,cancel)
for _ in range(100):
 final=api(root+'/status')[1]
 if final.get('workerState') in ('STOPPED','RECOVERABLE'):break
 time.sleep(.3)
actual=json.loads(subprocess.check_output([*D,'inspect',reg['containerId']]))[0]['State'];check('actual-rootless-Worker-stopped',final.get('workerState')=='STOPPED' and actual['Running']==False and final.get('stopProof',{}).get('running')==False,{'status':final,'actualState':actual})
save('native-summary.json',{'pass':all(x['pass'] for x in checks),'model':cfg['modelProfiles'][c['modelProfile']]['model'],'checks':len(checks),'workerId':wid,'containerId':reg['containerId'],'productionReady':False})
if not all(x['pass'] for x in checks):raise SystemExit(1)
