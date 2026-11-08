#!/usr/bin/env python3
"""Real HTTP/DB/process/container integration checks; protocol identities are test inputs.
Native Agent invocation is a separate gate, never inferred from these tests.
Run on the isolated Docker host after stage2-deploy.py.
"""
import pathlib,json,subprocess,urllib.request,urllib.error,time,threading,uuid,hashlib
R=pathlib.Path('/opt/cline-server-p3-stage2-20261108');cfg=json.loads((R/'private/config.json').read_text())
ip=json.loads(subprocess.check_output(['docker','inspect','cline-p32-authority']))[0]['NetworkSettings']['Networks']['cline-p32-control']['IPAddress']
URL='http://'+ip+':18502';evidence=[]
def post(path,b):
 req=urllib.request.Request(URL+path,data=json.dumps(b).encode(),headers={'Authorization':'Bearer '+cfg['gatewaySecret'],'content-type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=190) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
def check(name,ok,detail):
 evidence.append({'case':name,'pass':bool(ok),'detail':detail});(R/'evidence/protocol-tests.json').write_text(json.dumps(evidence,indent=2));print(name,'PASS' if ok else 'FAIL',flush=True)
def case(tool,args,label):
 ident={'tenantId':'stage2-protocol','userId':'alice','sessionId':label+'-'+uuid.uuid4().hex[:8],'taskId':label,'expiresAt':int(time.time()*1000)+3600000};base={'identity':ident,'epoch':'protocol-epoch'}
 status,s=post('/session',base);check(label+'-session',status==200,s)
 status,b=post('/bind',{**base,'nativeSessionId':'protocol-test-session','nativeToolCallId':uuid.uuid4().hex,'toolName':tool,'arguments':args});check(label+'-durable-binding',status==200,b)
 if status!=200:return None,None,None
 op=b['operationId'];q={**base,'operationId':op};rpc={'jsonrpc':'2.0','id':17,'method':'tools/call','params':{'name':tool,'arguments':args}}
 return q,rpc,b
def dispatch(q,rpc,result):result.append(post('/dispatch',{**q,'rpc':rpc}))
def state(q):return post('/get',q)[1]
def wait(q,predicate,seconds=25):
 last={}
 for _ in range(seconds*5):
  last=state(q)
  if predicate(last):return last
  time.sleep(.2)
 return last
args={'datasetId':'stage2','durationMs':1500};q,rpc,b=case('long_mcp',args,'complete')
if q:
 result=[];dispatch(q,rpc,result);v=state(q);check('real-mcp-completed',v.get('state')=='COMPLETED' and v.get('receipt',{}).get('resultSha256'),{'dispatch':result,'state':v})
 replay=post('/dispatch',{**q,'rpc':{**rpc,'id':999}});check('different-jsonrpc-id-same-operation',replay[0]==200 and replay[1].get('mcpResponse',{}).get('result')==result[0][1].get('mcpResponse',{}).get('result'),replay)
 stop=post('/cancel',q);check('late-cancel-does-not-rewrite-completion',stop[1].get('state')=='COMPLETED',stop)
 conflict=post('/bind',{**q,'nativeSessionId':b['nativeSessionId'],'nativeToolCallId':b['nativeToolCallId'],'toolName':'long_mcp','arguments':{'datasetId':'stage2','durationMs':1999}});check('same-native-call-mutated-arguments-rejected',conflict[0]==409,conflict)
 forged=post('/dispatch',{**q,'operationId':str(rpc['id']),'rpc':rpc});check('jsonrpc-id-cannot-be-operation-id',forged[0]==409,forged)
for tool,args,label in [('long_mcp',{'datasetId':'stage2','durationMs':60000},'mcp-cancel'),('python_execute',{'datasetId':'stage2','source':'import time\nfrom pathlib import Path\nprint("sandbox-started", flush=True)\ntime.sleep(90)\nPath("/workspace/outputs/should-not-exist.txt").write_text("late")\n'},'python-cancel')]:
 q,rpc,b=case(tool,args,label)
 if not q:continue
 result=[];t=threading.Thread(target=dispatch,args=(q,rpc,result));t.start()
 v=wait(q,lambda v:v.get('state')=='RUNNING');check(label+'-real-running',v.get('state')=='RUNNING',v)
 duplicate=post('/dispatch',{**q,'rpc':{**rpc,'id':918}});check(label+'-duplicate-no-redispatch',duplicate[0] in (200,503) and (duplicate[0]==200 or 'QUERY_ONLY' in duplicate[1].get('message','')),duplicate)
 status,c=post('/cancel',q);v=wait(q,lambda v:v.get('state') in ('CANCELLED','FAILED','COMPLETED'));t.join(30)
 check(label+'-stop-receipt',status==200 and v.get('state')=='CANCELLED' and v.get('receipt',{}).get('stopConfirmed') and v.get('stopProofRef'),{'cancel':c,'final':v,'dispatch':result})
 second=post('/cancel',q);check(label+'-repeat-cancel',second[0]==200 and second[1].get('stopProofRef')==v.get('stopProofRef'),second)
 invalid=post('/invalidate',q);stale=post('/dispatch',{**q,'rpc':rpc});check(label+'-inactive-epoch-rejected',stale[0]==409,stale)
check('all-protocol-cases',all(x['pass'] for x in evidence),{'total':len(evidence)})
