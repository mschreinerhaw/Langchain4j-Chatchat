#!/usr/bin/env python3
"""Transport and authority crash injection against real remote processes and JDBC."""
import pathlib,json,subprocess,urllib.request,urllib.error,time,threading,uuid
R=pathlib.Path('/opt/cline-server-p3-stage2-20261108');cfg=json.loads((R/'private/config.json').read_text())
def ip(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]['NetworkSettings']['Networks']['cline-p32-control']['IPAddress']
results=[]
def post(path,b,remote=False):
 url='http://'+ip('cline-p32-remote' if remote else 'cline-p32-authority')+(':18402/control' if remote else ':18502')+path
 req=urllib.request.Request(url,data=json.dumps(b).encode(),headers={'Authorization':'Bearer '+cfg['remoteControlSecret' if remote else 'gatewaySecret'],'content-type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=190) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
def check(name,ok,detail):
 results.append({'case':name,'pass':bool(ok),'detail':detail});(R/'evidence/fault-tests.json').write_text(json.dumps(results,indent=2));print(name,'PASS' if ok else 'FAIL',flush=True)
def setup(label,ms=1500):
 identity={'tenantId':'stage2-fault','userId':'alice','sessionId':uuid.uuid4().hex,'taskId':label,'expiresAt':int(time.time()*1000)+3600000}
 q={'identity':identity,'epoch':'fault-epoch'};assert post('/session',q)[0]==200
 args={'datasetId':'stage2','durationMs':ms};s,b=post('/bind',{**q,'nativeSessionId':'protocol-fault-test','nativeToolCallId':uuid.uuid4().hex,'toolName':'long_mcp','arguments':args});assert s==200,b
 q['operationId']=b['operationId'];rpc={'jsonrpc':'2.0','id':17,'method':'tools/call','params':{'name':'long_mcp','arguments':args}};return q,rpc
mode=R/'remote-state/fault-mode'
for fault in ('drop-request','drop-response','delay-response'):
 mode.write_text(fault);q,rpc=setup(fault);result=[]
 t=threading.Thread(target=lambda:result.append(post('/dispatch',{**q,'rpc':rpc})));t.start()
 if fault=='delay-response':
  for _ in range(50):
   observed=post('/get',q)[1]
   if observed.get('state')=='COMPLETED':break
   time.sleep(.1)
  cancellation=post('/cancel',q);t.join(20);final=post('/get',q)
  check('late-real-response-preserves-terminal',final[1].get('state')=='COMPLETED' and cancellation[1].get('state')=='COMPLETED',{'dispatch':result,'cancel':cancellation,'final':final})
 else:
  t.join(30);observed=post('/get',q);replay=post('/dispatch',{**q,'rpc':{**rpc,'id':99}})
  if fault=='drop-request':
   cancelled=post('/cancel',q);check('lost-request-UNKNOWN-no-replay-real-tombstone',observed[1].get('state')=='UNKNOWN' and replay[0]==503 and cancelled[1].get('state')=='CANCELLED' and cancelled[1].get('receipt',{}).get('dispatchPrevented'),{'dispatch':result,'observed':observed,'replay':replay,'cancel':cancelled})
  else:check('lost-response-query-real-completion',observed[1].get('state')=='COMPLETED' and replay[0]==503 and observed[1].get('receipt',{}).get('resultSha256'),{'dispatch':result,'observed':observed,'replay':replay})
mode.write_text('pass')
q,rpc=setup('authority-kill',12000);result=[]
def dispatch():
 try:result.append(post('/dispatch',{**q,'rpc':rpc}))
 except Exception as e:result.append({'transportFailure':type(e).__name__})
t=threading.Thread(target=dispatch);t.start()
for _ in range(50):
 v=post('/get',q)[1]
 if v.get('state')=='RUNNING':break
 time.sleep(.1)
subprocess.run(['docker','kill','cline-p32-authority'],check=True,stdout=subprocess.DEVNULL)
still=post('/get',{**q,'fencingToken':1},True);check('authority-kill-does-not-stop-remote',still[1].get('state')=='RUNNING' and not still[1].get('executorStopped'),still)
subprocess.run(['docker','start','cline-p32-authority'],check=True,stdout=subprocess.DEVNULL)
for _ in range(100):
 try:recovered=post('/get',q);break
 except (urllib.error.URLError,ConnectionError):time.sleep(.2)
t.join(20);time.sleep(13);final=post('/get',q)
check('JDBC-intent-survives-authority-kill',final[1].get('state')=='COMPLETED' and final[1].get('stopProofRef'),{'dispatch':result,'recovered':recovered,'final':final})
for _ in range(60):
 rotated=post('/rotate',q)
 if rotated[0]==200:break
 time.sleep(.5)
stale=post('/dispatch',{**q,'rpc':rpc});current=post('/cancel',q)
f=rotated[1].get('fencingToken',0)
advanced=post('/cancel',{**q,'fencingToken':f},True);rejected=post('/cancel',{**q,'fencingToken':1},True)
check('expired-lease-new-fence-old-epoch-rejected',rotated[0]==200 and f>1 and stale[0]==409 and rejected[0]==409,{'rotate':rotated,'oldNativeDispatch':stale,'advancedRemoteCancel':advanced,'oldRemoteCancel':rejected})
check('all-fault-cases',all(x['pass'] for x in results),{'total':len(results)})
