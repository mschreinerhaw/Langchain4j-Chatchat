"""Isolated real Worker crash after PASSED facts, before the next model admission.

Uses the unchanged C fixture, original model/budgets and existing task resume API.
No product repair, task-status rewriting or code generation occurs here.
"""
import hashlib,importlib.util,json,os,subprocess,time,urllib.request
from pathlib import Path
os.umask(0o077)
r=Path('/var/tmp/runtime-native-coding-acceptance-20261107')
def module(name,file):
 s=importlib.util.spec_from_file_location(name,r/file);v=importlib.util.module_from_spec(s);s.loader.exec_module(v);return v
h=module('acceptance','runtime-generic-acceptance.py');p=module('projection','project-evidence-projection.py')
def run(args):return subprocess.check_output(args,stderr=subprocess.PIPE)
def inspect(name):return json.loads(run(['docker','inspect',name]))[0]
api=inspect('runtime-native-acceptance-api');old=inspect('runtime-native-acceptance-worker')
assert api['Image']=='sha256:21beb4fb86e09d648f868b0740b07877ebf3670cf9ef54911b3fd7ec04a967db'
assert old['Image']=='sha256:e2f0334145750f38a97ffd2f785ff9b4ec2640cfb9f1e3827dc724abf71d76da'
cfg=json.loads(dict(v.split('=',1) for v in api['Config']['Env'] if '=' in v)['SPRING_APPLICATION_JSON']);secret=cfg['chatchat.coding.cline.shared-secret']
def ready():
 req=urllib.request.Request('http://127.0.0.1:18291/readyz',headers={'Authorization':'Bearer '+secret})
 with urllib.request.urlopen(req,timeout=10) as response:return json.load(response)
assert ready()['activeSessions']==0
assert not run(['docker','ps','-q','--filter','ancestor='+h.IMAGE]).strip()
label='F-phase4-verified-r1';d=r/'runs'/label;d.mkdir(exist_ok=False)
context=d/'build';context.mkdir()
(context/'observer.mjs').write_bytes((r/'phase4-observed-bootstrap.mjs').read_bytes())
(context/'Dockerfile').write_text('\n'.join(['FROM runtime-native-acceptance-worker:20261107-phase4-observed','USER root',
 'COPY --chown=1000:1000 observer.mjs /srv/coding-agent/experiments/phase4-observed-bootstrap.mjs','USER 1000:1000','']))
build=subprocess.run(['docker','build','-t','runtime-native-acceptance-worker:20261107-phase4-restore',str(context)],capture_output=True)
(d/'build.log').write_bytes(build.stdout+build.stderr);assert build.returncode==0
image=inspect('runtime-native-acceptance-worker:20261107-phase4-restore')['Id']
def start(fault,log):
 args=['docker','create','-i','--name','runtime-native-acceptance-worker','--network','container:'+api['Id'],
  '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','512m','--pids-limit','256',
  '--tmpfs','/tmp:rw,nosuid,noexec,size=64m','-e','RUNTIME_CODING_BUILD=e107dffff']
 if fault:args+=['-e','RUNTIME_ACCEPTANCE_FAULT=AFTER_PASSED_FACTS']
 wid=run(args+['--entrypoint','node',image,'/srv/coding-agent/experiments/phase4-observed-bootstrap.mjs']).decode().strip()
 with log.open('ab') as stream:
  proc=subprocess.Popen(['docker','start','-i',wid],stdin=subprocess.PIPE,stdout=stream,stderr=stream,start_new_session=True)
  proc.stdin.write(json.dumps({'secret':secret}).encode());proc.stdin.close()
 for attempt in range(60):
  try:
   assert ready()['status']=='READY';break
  except Exception:
   if attempt==59:raise
   time.sleep(1)
 return wid
assert ready()['activeSessions']==0
run(['docker','stop','-t','15',old['Id']]);run(['docker','rename',old['Id'],'runtime-native-worker-before-phase4-restore'])
faultlog=d/'fault-worker.log';crashed=start(True,faultlog)
fixture=json.loads((r/'generic-cases-fixed.json').read_text());case=next(c for c in fixture['cases'] if c['id']=='C')
token=json.loads((r/'api-auth-private.json').read_text())['token']
request={k:case[k] for k in ['instruction','files','readOnlyPaths','acceptanceCommand']};request.update(modelName='qwen3.8-flash',editStrategy='auto',skills=[])
h.write(d/'request.json',request)
reply=h.call('PUT','/api/v1/coding/projects/generic-'+label,token,request);h.write(d/'submit.json',reply);task=h.data(reply)['taskId']
h.write(d/'intent.json',{'taskId':task,'case':'C','cohort':label,'sourceCommit':'e107dffff','fixtureSha256':fixture['fixtureSha256'],
 'difference':'Fault injection only: unchanged C fixture; pause after real PASSED facts before next Java model admission'})
def observations(log):
 values=[]
 for line in log.read_text().splitlines():
  try:
   value=json.loads(line)
   if value.get('acceptanceObservation') and value.get('taskId')==task:values.append(value)
  except ValueError:pass
 return values
deadline=time.monotonic()+300;held=None
while time.monotonic()<deadline:
 held=next((v for v in observations(faultlog) if v['acceptanceObservation']=='PHASE4_VERIFIED_BEFORE_MODEL'),None)
 if held:break
 status=h.data(h.call('GET','/api/v1/coding/projects/'+task,token))
 if status['status'] in h.TERMINAL:raise RuntimeError('No actual post-verification fault window; original result retained')
 time.sleep(.3)
assert held is not None
before=p.journal(p.rows(task),task);before_status=h.data(h.call('GET','/api/v1/coding/projects/'+task,token))
verification=before['privateState']['native-last-verification']
assert verification['exitCode']==0 and verification['executorStopped'] and verification['stopProofRef']
assert verification['manifestHash']==held['codeManifestHash'] and verification['operationId']==held['executionOperationId']
assert held['operationId'] not in before['operations'], 'Held request must not have entered Java model admission'
assert all(v.get('stopProofRef') for v in before['operations'].values() if v.get('startIntent'))
h.write(d/'before-crash-status.json',before_status)
run(['docker','kill',crashed]);killed=inspect(crashed);assert not killed['State']['Running']
run(['docker','rename',crashed,'runtime-native-worker-crashed-'+label])
restoredlog=d/'restored-worker.log';restored=start(False,restoredlog)
attempts=[];deadline=time.monotonic()+310
needs_resume=True
while True:
 if needs_resume:
  reply=h.call('POST','/api/v1/coding/projects/'+task+'/resume',token,{})
  attempts.append({'http':reply['http'],'code':reply['body'].get('code'),'atEpochMs':int(time.time()*1000)})
 status=h.data(h.call('GET','/api/v1/coding/projects/'+task,token));h.write(d/'last-status.json',{'http':200,'body':{'code':200,'data':status}})
 if status['status'] in h.TERMINAL or time.monotonic()>deadline:break
 needs_resume=status['executionControl']['reconciliationRequired'] or reply['http']!=200 or reply['body'].get('code')!=200
 time.sleep(2)
for suffix in ['files','history','events']:h.write(d/(suffix+'.json'),h.call('GET','/api/v1/coding/projects/'+task+'/'+suffix,token))
after=p.journal(p.rows(task),task);tree=h.data(json.loads((d/'files.json').read_text()))
beforefacts=[v for v in observations(faultlog) if v['acceptanceObservation']=='PHASE4_MODEL_REQUEST_FACTS']
afterfacts=[v for v in observations(restoredlog) if v['acceptanceObservation']=='PHASE4_MODEL_REQUEST_FACTS']
restoredfact=afterfacts[0] if afterfacts else {}
ops=[{'operationId':k,'kind':v.get('kind'),'stopProofRef':v.get('stopProofRef'),'startSequence':v.get('startSequence')} for k,v in after['operations'].items() if v.get('startIntent')]
proof={'scope':'ACTUAL_WORKER_KILL_AFTER_CURRENT_DECLARED_PASS_BEFORE_NEXT_MODEL_ADMISSION','taskId':task,'case':'C',
 'sourceCommit':'e107dffff','fixtureSha256':fixture['fixtureSha256'],'workerImage':image,'workerKilled':crashed,'workerRestored':restored,
 'observerSha256':h.sha((r/'phase4-observed-bootstrap.mjs').read_bytes()),'controllerSha256':h.sha(Path(__file__).read_bytes()),
 'killedWorkerStopped':not killed['State']['Running'],'killedWorkerExitCode':killed['State']['ExitCode'],
 'beforeSdkSessionId':before['sdkCheckpoint']['sdkSessionId'],'afterSdkSessionId':after['sdkCheckpoint']['sdkSessionId'],
 'sameSdkSessionId':before['sdkCheckpoint']['sdkSessionId']==after['sdkCheckpoint']['sdkSessionId'],
 'contractHashUnchanged':h.sha(json.dumps(before['contract'],sort_keys=True).encode())==h.sha(json.dumps(after['contract'],sort_keys=True).encode()),
 'frozenBudget':after['contract']['budget'],'beforeUsage':before_status['usage'],'afterUsage':status['usage'],
 'heldRequestWasNotAdmitted':held['operationId'] not in before['operations'],'beforeHeldFacts':beforefacts[-1],
 'firstRestoredFacts':restoredfact,'sameVerifiedReceiptOnRestore':restoredfact.get('verification',{}).get('executionOperationId')==verification['operationId'],
 'sameModelOrdinalOnRestore':restoredfact.get('operationId')==held['operationId'],
 'originalExecutionsRetained':all(after['operations'].get(k,{}).get('stopProofRef')==v.get('stopProofRef') for k,v in before['operations'].items() if v.get('startIntent')),
 'originalCodeStops':ops,'allStartedOperationsStopped':all(v['stopProofRef'] for v in ops),
 'status':status['status'],'executionControl':status['executionControl'],'resumeAttempts':attempts,
 'compactionCountBefore':before['sdkCheckpoint'].get('compactionCount'),'compactionCountAfter':after['sdkCheckpoint'].get('compactionCount')}
if status['status'] in h.TERMINAL:
 proof['independent']=h.independent(case,tree,d,label)
 proof['archiveReadAgain']=h.data(h.call('GET','/api/v1/coding/projects/'+task+'/files',token))==tree
 proof['taskUsageUnchangedByHoldout']=h.data(h.call('GET','/api/v1/coding/projects/'+task,token))['usage']==status['usage']
proof['completed']=status['status']=='COMPLETED' and proof.get('independent',{}).get('passed') is True and all(proof[k] for k in ['sameSdkSessionId','contractHashUnchanged','sameVerifiedReceiptOnRestore','sameModelOrdinalOnRestore','originalExecutionsRetained','allStartedOperationsStopped','archiveReadAgain','taskUsageUnchangedByHoldout'])
h.write(d/'result-public.json',proof);h.write(d/'model-facts-public.json',beforefacts+afterfacts)
print(json.dumps(proof),flush=True)
