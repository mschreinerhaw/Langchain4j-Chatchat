"""Isolated experiment controller, never imported by the product.

Requires the existing private acceptance setup. Kills only its named Worker,
keeps original task budgets and freezes a separate 35-second readonly F fixture.
Persist observations before the crash; a removed container is not a stop proof.
"""
import pathlib,subprocess,json,os,time,importlib.util,hashlib,datetime,sys
os.umask(0o077)
r=pathlib.Path('/var/tmp/runtime-native-coding-acceptance-20261107')
def module(name,file):
 s=importlib.util.spec_from_file_location(name,r/file);v=importlib.util.module_from_spec(s);s.loader.exec_module(v);return v
h=module('acceptance','runtime-generic-acceptance.py');p=module('projection','project-evidence-projection.py')
def run(args,data=None):return subprocess.run(args,input=data,capture_output=True,check=True).stdout
def inspect(name):return json.loads(run(['docker','inspect',name]))[0]
token=json.loads((r/'api-auth-private.json').read_text())['token']
old=inspect('runtime-native-acceptance-worker');api=inspect('runtime-native-acceptance-api')
assert old['Image']==json.loads((r/'pipefail-deployment-public.json').read_text())['images']['worker']
label='F-inflight-native-r'+str(int(sys.argv[1] if len(sys.argv)>1 else '2'))
d=r/'runs'/label;d.mkdir(exist_ok=False)
case=next(c for c in json.loads((r/'generic-cases-fixed.json').read_text())['cases'] if c['id']=='E-key')
case=json.loads(json.dumps(case));test=case['readOnlyPaths'][0]
case['files'][test]='import time\ntime.sleep(35)\n'+case['files'][test]
case['initialFileHashes'][test]=h.sha(case['files'][test].encode())
fixture_hash=h.sha(json.dumps(case,sort_keys=True,separators=(',',':')).encode())
h.write(d/'fixture.json',case)
request={k:case[k] for k in ['instruction','files','readOnlyPaths','acceptanceCommand']};request.update(modelName='qwen3.8-flash',editStrategy='auto',skills=[])
h.write(d/'request.json',request)
reply=h.call('PUT','/api/v1/coding/projects/generic-'+label,token,request);h.write(d/'submit.json',reply);task=h.data(reply)['taskId']
h.write(d/'intent.json',{'taskId':task,'case':'E-key','cohort':'F-inflight','sourceCommit':'0b62c544b','fixtureSha256':fixture_hash,'difference':'Separate F fixture: readonly test waits 35s; original model and budgets unchanged'})
cfg=json.loads(dict(x.split('=',1) for x in api['Config']['Env'] if '=' in x)['SPRING_APPLICATION_JSON'])
active=None;before=None
for _ in range(800):
 state=p.journal(p.rows(task),task)
 code=[(k,v) for k,v in state['operations'].items() if v.get('kind')=='CODE' and v.get('startIntent') and not v.get('stopProofRef')]
 if code:
  ids=run(['docker','ps','-q','--filter','ancestor='+h.IMAGE]).decode().splitlines()
  for cid in ids:
   candidate=inspect(cid)
   elapsed=(datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(candidate['State']['StartedAt'].replace('Z','+00:00'))).total_seconds()
   if elapsed>=8 and any(m['Source'].startswith(str(r)) and m['Destination']=='/workspace/code' for m in candidate['Mounts']):active=candidate;break
  if active:before=state;break
 status=h.data(h.call('GET','/api/v1/coding/projects/'+task,token))
 if status['status'] in h.TERMINAL:raise RuntimeError('Original task stopped before fault window')
 time.sleep(.2)
if not active:raise RuntimeError('No actual inflight Sandbox observed; original task retained')
sid=before['sdkCheckpoint']['sdkSessionId'];contract_hash=h.sha(json.dumps(before['contract'],sort_keys=True,separators=(',',':')).encode())
before_status=h.data(h.call('GET','/api/v1/coding/projects/'+task,token));before_files=h.data(h.call('GET','/api/v1/coding/projects/'+task+'/files',token))
mounted=pathlib.Path(next(m['Source'] for m in active['Mounts'] if m['Destination']=='/workspace/code'))
assert {name:h.sha((mounted/name).read_bytes()) for name in before_files['files']}=={name:h.sha(source.encode()) for name,source in before_files['files'].items()}
h.write(d/'before-crash-status.json',before_status);h.write(d/'before-crash-files.json',before_files)
h.write(d/'before-crash-observation-public.json',{'taskId':task,'observedAtEpochMs':int(time.time()*1000),'sdkSessionId':sid,'contractSha256':before_status['contractSha256'],'workerId':old['Id'],'workerImage':old['Image'],'sandboxId':active['Id'],'sandboxState':{k:active['State'][k] for k in ['Running','StartedAt','ExitCode']},'sandboxFrozenFilesMatchCurrentManifest':True,'manifestHash':before_files['manifest']['sha256']})
run(['docker','kill',old['Id']]);killed=inspect(old['Id']);assert not killed['State']['Running']
try:still_running=inspect(active['Id'])['State']['Running']
except subprocess.CalledProcessError:still_running=None
h.write(d/'after-crash-observation-public.json',{'taskId':task,'observedAtEpochMs':int(time.time()*1000),'workerId':old['Id'],'workerState':{k:killed['State'][k] for k in ['Running','FinishedAt','ExitCode']},'sandboxId':active['Id'],'sandboxStillRunning':still_running})
run(['docker','rename',old['Id'],'runtime-native-worker-crashed-'+label])
args=['docker','create','-i','--name','runtime-native-acceptance-worker','--network','container:'+api['Id'],'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','512m','--pids-limit','256','--tmpfs','/tmp:rw,nosuid,noexec,size=64m',old['Image']]
wid=run(args).decode().strip()
with (d/'worker-restored.log').open('ab') as log:
 proc=subprocess.Popen(['docker','start','-i',wid],stdin=subprocess.PIPE,stdout=log,stderr=log,start_new_session=True);proc.stdin.write(json.dumps({'secret':cfg['chatchat.coding.cline.shared-secret']}).encode());proc.stdin.close()
time.sleep(2)
initial=h.call('POST','/api/v1/coding/projects/'+task+'/resume',token,{})
attempts=[{'http':initial['http'],'code':initial['body'].get('code'),'atEpochMs':int(time.time()*1000)}];deadline=time.monotonic()+310
while True:
 status=h.data(h.call('GET','/api/v1/coding/projects/'+task,token));h.write(d/'last-status.json',{'http':200,'body':{'code':200,'data':status}})
 if status['status'] in h.TERMINAL or time.monotonic()>deadline:break
 if status['executionControl']['reconciliationRequired']:
  reply=h.call('POST','/api/v1/coding/projects/'+task+'/resume',token,{})
  attempts.append({'http':reply['http'],'code':reply['body'].get('code'),'atEpochMs':int(time.time()*1000)})
  h.write(d/'resume-attempts-public.json',attempts)
 time.sleep(2)
for suffix in ['files','history','events']:h.write(d/(suffix+'.json'),h.call('GET','/api/v1/coding/projects/'+task+'/'+suffix,token))
after=p.journal(p.rows(task),task);files=h.data(json.loads((d/'files.json').read_text()))
code_stops=[{'operationId':k,'stopProofRef':v.get('stopProofRef')} for k,v in after['operations'].items() if v.get('kind')=='CODE' and v.get('startIntent')]
proof={'taskId':task,'sourceCommit':'0b62c544b','fixtureSha256':fixture_hash,'scope':'ACTUAL_WORKER_KILL_DURING_INFLIGHT_SANDBOX','workerKilled':old['Id'],'workerRestored':wid,'workerImage':old['Image'],'killedWorkerStopped':not killed['State']['Running'],'killedWorkerExitCode':killed['State']['ExitCode'],'sandboxContainerId':active['Id'],'sandboxContinuedAfterWorkerDeath':still_running,'sandboxStoppedByVerifiedMcpReceipts':bool(code_stops) and all(v['stopProofRef'] for v in code_stops),'codeStops':code_stops,'beforeSdkSessionId':sid,'afterSdkSessionId':after['sdkCheckpoint']['sdkSessionId'],'sameSdkSessionId':sid==after['sdkCheckpoint']['sdkSessionId'],'contractHashUnchanged':contract_hash==h.sha(json.dumps(after['contract'],sort_keys=True,separators=(',',':')).encode()),'beforeUsage':before_status['usage'],'afterUsage':status['usage'],'status':status['status'],'executionControl':status['executionControl'],'originalManifestRetained':any(x['manifest']['sha256']==before_files['manifest']['sha256'] for x in h.data(json.loads((d/'history.json').read_text()))),'resumeAttempts':attempts,'controllerSha256':h.sha(pathlib.Path(__file__).read_bytes())}
if status['status'] in h.TERMINAL:proof['independent']=h.independent(case,files,d,label)
h.write(d/'result-public.json',proof);print(json.dumps(proof),flush=True)
