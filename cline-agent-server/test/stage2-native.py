#!/usr/bin/env python3
"""Real model -> official Cline Agent -> native MCP -> existing Java journal/executor.
No tool selection/loop is implemented here: this client submits requests and responds
to actual native approvals, then injects Worker loss or remote cancellation.
"""
import pathlib,json,subprocess,urllib.request,urllib.error,time,uuid,hmac,hashlib,base64,threading,os,signal
R=pathlib.Path('/opt/cline-server-p3-stage2-20261108');cfg=json.loads((R/'control/config.json').read_text());E=R/'evidence/native';E.mkdir(exist_ok=True)
results=[];tenantId=next(iter(cfg['tenants']));tenant=cfg['tenants'][tenantId]
def save(name,value):(E/name).write_text(json.dumps(value,indent=2))
def sign(c):
 b=base64.urlsafe_b64encode(json.dumps(c,separators=(',',':')).encode()).rstrip(b'=');s=base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),b,hashlib.sha256).digest()).rstrip(b'=');return (b+b'.'+s).decode()
def api(c,path,body=None,key=None):
 req=urllib.request.Request('http://127.0.0.1:18330'+path,data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Bearer '+sign(c),'content-type':'application/json','accept':'application/json',**({'idempotency-key':key} if key else {})})
 try:
  with urllib.request.urlopen(req,timeout=100) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
def check(name,ok,detail):results.append({'case':name,'pass':bool(ok),'detail':detail});save('summary.json',results);print(name,'PASS' if ok else 'FAIL',flush=True)
for tool,action in [('long_mcp','kill'),('python_execute','cancel'),('python_execute','kill-core')]:
 label=tool if action!='kill-core' else tool+'-core-kill'
 c={'version':1,'tenantId':tenantId,'userId':tenant['users'][0],'taskId':'native-'+uuid.uuid4().hex,'sessionId':'session-'+uuid.uuid4().hex,'workspaceRef':next(iter(tenant['workspaces'])),'modelProfile':tenant['models'][0],'budgetPolicy':{'status':'DEFERRED_STAGE_2_REVIEW'},'toolPermissions':tenant['tools'],'dataPermissions':tenant['datasets'],'networkPolicy':'broker-only','expiresAt':int(time.time()*1000)+7200000}
 save(label+'-contract.json',c);root='/v1/sessions/'+c['sessionId'];created=api(c,'/v1/sessions',{},'create-'+label);check(label+'-created',created[0]==201,created)
 for _ in range(180):
  s=api(c,root+'/status')[1]
  if s.get('workerState') in ('READY','START_FAILED','RECOVERABLE'):break
  time.sleep(.5)
 check(label+'-ready',s.get('workerState')=='READY',s)
 if s.get('workerState')!='READY':continue
 dataset=c['dataPermissions'][0]
 instruction=(f'Call the connected MCP tool tenant_data__{tool} exactly once, with datasetId="{dataset}". '
  +( 'Set durationMs=60000. This is a real remote cancellation acceptance test. Do not use shell to implement the MCP task.' if tool=='long_mcp' else 'Supply Python source which prints "native-sandbox-started" with flush=True, then time.sleep(90), then prints "should-not-run". This is a real remote Sandbox cancellation acceptance test. Do not run Python through a shell.')
  +' Do not create or change project files. Wait for the MCP tool receipt before reporting its result.')
 submitted=api(c,root+'/messages',{'text':instruction},'task-'+label);check(label+'-real-model-submitted',submitted[0]==202,submitted)
 approvals=[];running=None;observations=[]
 for _ in range(300):
  s=api(c,root+'/status')[1];observations.append(s)
  for p in (s.get('native') or {}).get('pendingPermissions',[]):
   r=api(c,'/v1/permissions/'+p['id']+'/respond',{'decision':'approve','reason':'Stage2 real external operation acceptance'})
   approvals.append({'permission':p,'response':r});save(label+'-approvals.json',approvals)
  ops=api(c,root+'/operations')
  if ops[0]==200:
   running=next((o for o in ops[1] if o['toolName']==tool and o['state']=='RUNNING'),None)
   if running:break
  if s.get('workerState') in ('START_FAILED','RECOVERABLE','STOPPED','COMPLETED'):break
  time.sleep(.5)
 save(label+'-observations.json',observations);save(label+'-events-before.json',api(c,root+'/events'))
 check(label+'-native-operation-running',bool(running),{'operation':running,'status':s,'approvals':approvals})
 if not running:api(c,root+'/cancel',{});time.sleep(4);continue
 registry=json.loads((R/'control/state/workers'/s['workerId']/'registry.json').read_text());container=registry['containerId']
 if action=='kill':
  subprocess.run(['docker','kill',container],check=True,stdout=subprocess.DEVNULL)
  check('worker-kill-injected',True,{'containerId':container,'operationId':running['operationId']})
 elif action=='kill-core':
  inspect=json.loads(subprocess.check_output(['docker','inspect',container]))[0];assert inspect['Config']['Labels']['cline.workerId']==registry['workerId']
  rows=subprocess.check_output(['docker','top',container,'-eo','pid,args'],text=True).splitlines()[1:];pids=[int(row.split()[0]) for row in rows if '/poc/runtime/cline-core.js' in row]
  if len(pids)==1:os.kill(pids[0],signal.SIGKILL)
  else:api(c,root+'/cancel',{})
  check('official-core-kill-injected',len(pids)==1,{'containerId':container,'hostPids':pids,'operationId':running['operationId'],'outerContainerStillRunning':json.loads(subprocess.check_output(['docker','inspect',container]))[0]['State']['Running']})
 else:check('python-remote-cancel-request',api(c,root+'/cancel',{})[0]==202,{'operationId':running['operationId']})
 for _ in range(120):
  final=api(c,root+'/status')[1];ops=api(c,root+'/operations')
  if final.get('remoteStopConfirmed'):break
  time.sleep(.5)
 save(label+'-final-operations.json',ops)
 check(label+'-remote-final-reconciliation',final.get('remoteStopConfirmed') and ops[0]==200 and all(o['state']=='CANCELLED' and o.get('receipt',{}).get('stopConfirmed') for o in ops[1]),{'status':final,'operations':ops})
 save(label+'-worker-inspect.json',json.loads(subprocess.check_output(['docker','inspect',container]))[0]['State'])
check('native-real-model-stage2',all(r['pass'] for r in results),{'checks':len(results)})
