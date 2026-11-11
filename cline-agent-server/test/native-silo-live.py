"""Real Native + Python + Silo fixture. No generated analysis or rescue prompts.

Run on the authorized test host after NativeSiloAcceptanceDriver prepare. Private
model settings, signed transfer plans and customer evidence never belong in Git.
The fixture validates execution/delivery, NOT application HTTP authorization.
"""
from pathlib import Path
import argparse, datetime, hashlib, http.server, json, os, shutil
import secrets, subprocess, sys, threading, time, urllib.request, uuid
import urllib.error
import live_model_audit as audit
from native_silo_control import SiloControl,feedback_for
from resource_registry import register_container

parser=argparse.ArgumentParser()
parser.add_argument('--root',type=Path,required=True)
parser.add_argument('--fixture',type=Path,required=True)
parser.add_argument('--case',required=True)
parser.add_argument('--image',required=True)
parser.add_argument('--prompt',type=Path,required=True)
parser.add_argument('--port',type=int,default=18531)
parser.add_argument('--proxy-port',type=int,default=18530)
parser.add_argument('--runtime-feedback',action='store_true',help='Return real Java validation faults to the SAME live Native session before sealing')
parser.add_argument('--output-token-policy',choices=['8192','provider-default'],default='8192',help='Freeze the same supported request policy on BOTH comparison sides')
parser.add_argument('--delivery-selection',action='store_true',help='Agent explicitly declares artifact types; Host computes hashes/receipts')
parser.add_argument('--restricted-executor-config',type=Path,help='Private opt-in fixture authority; actual network-none Worker via restricted Executor')
args=parser.parse_args()
if not args.image.startswith('sha256:') or not args.case.replace('-','').isalnum():raise ValueError('Pinned image and unique case required')
os.umask(0o077)
profile=json.loads((args.root/'private/settings.json').read_text())
# Per-task control/proxy credentials. Shared provider credentials/configuration
# remain unchanged and never enter the Core container.
profile['apiToken']=secrets.token_hex(32)
profile['proxyToken']=secrets.token_hex(32)
model_options={'thinking':True,'reasoningEffort':'low'}
if args.output_token_policy=='8192':model_options['maxOutputTokens']=8192
audit.configure(args.root,profile)
plan=json.loads((args.fixture/'worker-plan.private.json').read_text())
case=args.case;folder=args.root/'runs'/case
if folder.exists():raise RuntimeError('Refusing redispatch of an existing live fixture')
folder.mkdir(parents=True);workspace=folder/'workspace';state=folder/'state'
harness=folder/'harness';harness.mkdir();source_versions=[]
sources=[Path(__file__),Path(audit.__file__),Path(sys.modules[SiloControl.__module__].__file__),Path(sys.modules[register_container.__module__].__file__),args.prompt]
if args.restricted_executor_config:
 from native_restricted_control import RestrictedNative
 sources.append(Path(sys.modules[RestrictedNative.__module__].__file__))
for source in sources:
 raw=source.read_bytes();(harness/source.name).write_bytes(raw);source_versions.append({'name':source.name,'sha256':hashlib.sha256(raw).hexdigest()})
(harness/'versions.json').write_text(json.dumps(source_versions,indent=2))
for path in [workspace,state]:path.mkdir();os.chown(path,10001,10001);path.chmod(0o700)
tools=['read_files','search_codebase','editor','apply_patch','skills','run_commands','submit_and_exit']
fixture_config=json.loads((args.root/'private'/(args.fixture.name+'.json')).read_text())
prompt=args.prompt.read_text().replace('{{customerNumber}}',fixture_config['customerNumber']);session=None;container=None;terminal='PREPARING';started=time.monotonic();container_stopped=False;control=None;feedback_receipts=0;protocol_feedbacks=0;delivery_version='v1';business_state=None
restricted=None
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2))
def command(*parts,timeout=90):
 result=subprocess.run(list(parts),text=True,capture_output=True,timeout=timeout)
 if result.returncode:raise RuntimeError(f'{parts[0]} failed: '+result.stderr[-1200:])
 return result.stdout.strip()
def docker(*parts,**options):return command('docker',*parts,**options)
def register(ident,name,purpose):
 info=json.loads(docker('inspect',ident))[0]
 register_container(args.root,info,name,purpose,case)
def api(path,value=None):
 if restricted:return restricted.api(path,value)
 request=urllib.request.Request(f'http://127.0.0.1:{args.port}'+path,data=None if value is None else json.dumps(value).encode(),headers={'Authorization':'Bearer '+profile['apiToken'],'Accept':'application/json','Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
 with urllib.request.urlopen(request,timeout=95) as response:return json.load(response)

def core_inspect():return restricted.inspect() if restricted else json.loads(docker('inspect',container))[0]
def core_stop():return restricted.stop() if restricted else docker('stop','-t','5',container)
def versions():
 for path in workspace.rglob('*'):
  if path.is_symlink() or not path.is_file() or any(part in path.parts for part in ['input','.git','__pycache__','.pytest_cache']):continue
  if path.stat().st_size>4*1024*1024:continue
  try:
   raw=path.read_bytes();digest=hashlib.sha256(raw).hexdigest();target=folder/'versions'/digest/path.relative_to(workspace)
   if not target.exists():target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
  except OSError:pass
def inventory():
 files=[]
 for path in sorted(workspace.rglob('*')):
  rel=path.relative_to(workspace)
  if str(rel)=='delivery_manifest.json' or rel.name.startswith('.delivery-manifest-'):continue
  if path.is_symlink() or any(p in ['input','logs','evidence','.git','__pycache__','.pytest_cache','.cline'] for p in rel.parts) or not path.is_file():continue
  kind={'.py':'SOURCE','.md':'REPORT','.png':'CHART','.svg':'CHART'}.get(path.suffix,'AUXILIARY')
  if path.suffix=='.json':
   try:
    if isinstance(json.loads(path.read_text()),dict) and 'verifiedAccountSummary' in json.loads(path.read_text()):kind='RESULT'
   except (ValueError,UnicodeDecodeError):pass
  files.append({'path':str(rel),'type':kind})
 return files
def await_turn():
 while time.monotonic()-started<1200:
  versions();observation=api('/v1/sessions/'+session);write(folder/'latest-status.json',observation)
  for permission in observation.get('pendingPermissions',[]):api('/v1/permissions/'+permission['id']+'/respond',{'decision':'approve' if permission['toolName'] in tools else 'deny','reason':'Authorized isolated task workspace; fixed Native tool profile'})
  write(folder/'events.json',api('/v1/sessions/'+session+'/events'))
  if observation['status'] in ['turn_completed','failed','awaiting_input','recoverable']:return observation['status']
  time.sleep(.5)
 return 'DEADLINE_EXCEEDED'

write(folder/'intent.json',{'case':case,'prompt':prompt,'imageId':args.image,'model':profile['model'],'modelOptions':model_options,'outputTokenPolicy':args.output_token_policy,'deliverySelectionRequired':args.delivery_selection,'modelRequestLimit':'unlimited','toolCallLimit':'unlimited','timeoutSeconds':1200,'inputSnapshotVersion':plan['binding']['inputSnapshotVersion'],'executionTransport':'RESTRICTED_EXECUTOR_NETWORK_NONE' if args.restricted_executor_config else 'DIRECT_DOCKER_AUDIT_BRIDGE','startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'authorizationScope':'Previously authorized frozen customer snapshot; isolated Java service fixture, not application HTTP acceptance'})
proxy=http.server.ThreadingHTTPServer(('127.0.0.1' if args.restricted_executor_config else '0.0.0.0',args.proxy_port),audit.AuditProxy)
threading.Thread(target=proxy.serve_forever,daemon=True).start()
try:
 if args.restricted_executor_config:
  if not args.runtime_feedback or not args.delivery_selection:raise ValueError('Restricted fixture requires Java verification and declared artifacts')
  restricted=RestrictedNative(args.root,folder,args.restricted_executor_config,plan,profile,model_options,args.image,case,args.proxy_port,tools)
  container=restricted.provision()
 # Input-only short-lived Silo capabilities are mounted for the transfer phase;
 # the subsequent Core container gets only the verified read-only input tree.
 transfer=folder/'download-plan.private.json';write(transfer,plan);os.chown(transfer,10001,10001);transfer.chmod(0o400)
 helper_name='coding-node-input-'+case
 helper=docker('create','--name',helper_name,'--label','runtime-os.coding.acceptance='+case,'--network','host','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','512m','--pids-limit','64','--tmpfs','/tmp:rw,nosuid,nodev,size=64m','-v',str(workspace)+':/workspace/task','-v',str(transfer)+':/run/transfer-plan.json:ro',args.image,'node','/poc/service/src/workspace-transfer.mjs','download','/workspace/task','/run/transfer-plan.json')
 register(helper,helper_name,'Silo input transfer; no Core')
 result=subprocess.run(['docker','start','-a',helper],text=True,capture_output=True,timeout=330)
 (folder/'download.stdout.json').write_text(result.stdout);(folder/'download.stderr.log').write_text(result.stderr)
 info=json.loads(docker('inspect',helper))[0]
 if result.returncode or info['State']['ExitCode']:raise RuntimeError('Silo input transfer failed; Native was not started')
 local='/workspace/'+plan['binding']['taskId']
 if not restricted:
  config={'sourceRoot':'/not-mounted/upstream','distDir':'/poc/runtime','stateDir':'/state','apiToken':profile['apiToken'],'apiKey':profile['proxyToken'],'model':profile['model'],'baseUrl':f'http://cline-model-audit:{args.proxy_port}/{case}/v1','workspaces':{'task':local},'modelOptions':model_options,'toolPermissions':tools,'host':'0.0.0.0','port':args.port,'requireSubmitAndExit':False,'deliveryBinding':plan['binding'],'allowDeliveryPreview':args.runtime_feedback,'requireDeliverySelection':args.delivery_selection}
  config_file=folder/'native-config.private.json';write(config_file,config);os.chown(config_file,10001,10001);config_file.chmod(0o400)
  name='coding-node-core-'+case
  container=docker('create','--name',name,'--label','runtime-os.coding.acceptance='+case,'--init','--network','cline-parquet-isolation','--add-host','cline-model-audit:host-gateway','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','3g','--cpus','2','--pids-limit','256','--tmpfs','/tmp:rw,nosuid,nodev,size=256m','-p',f'127.0.0.1:{args.port}:{args.port}','-e','CLINE_SERVER_CONFIG=/run/native-config.json','-v',str(workspace)+':'+local,'-v',str(workspace/'input')+':'+local+'/input:ro','-v',str(state)+':/state','-v',str(config_file)+':/run/native-config.json:ro',args.image)
  register(container,name,'Standalone Core + native Python; no Sandbox')
 audit.active[case]=time.monotonic()
 if restricted:restricted.start()
 else:docker('start',container)
 for _ in range(120):
  try:
   if api('/health/ready').get('ready'):break
  except Exception:time.sleep(.25)
 else:raise RuntimeError('Native HTTP readiness failed')
 session=api('/v1/sessions',{'workspaceRef':'project' if restricted else 'task'})['sessionId'];write(folder/'session.json',{'sessionId':session})
 while time.monotonic()-started<1200:
  status=api('/v1/sessions/'+session)
  if status['status']=='idle':break
  if status['status']=='failed':raise RuntimeError('Native worker startup failed')
  time.sleep(.5)
 else:raise TimeoutError('Native worker startup deadline')
 write(folder/'submission.json',api('/v1/sessions/'+session+'/messages',{'text':prompt}))
 terminal=await_turn()
 if args.runtime_feedback:
  control=SiloControl(args.root,args.fixture,case,args.image,register);seen=set();protocol_seen=set();revision=1
  original_native_id=api('/v1/sessions/'+session)['nativeSessionId']
  while terminal=='turn_completed':
   delivery_version='v'+str(revision)
   try:preview=api('/v1/sessions/'+session+'/delivery-preview',{'deliveryVersion':delivery_version,**({} if args.delivery_selection else {'files':inventory()})})
   except urllib.error.HTTPError as failure:
    if not args.delivery_selection or failure.code!=422:raise
    receipt=json.load(failure)
    if receipt.get('code') not in ['DELIVERY_SELECTION_INVALID','DELIVERY_CONTENT_INVALID']:raise
    identity=(receipt.get('code'),receipt.get('selectionSha256') or receipt.get('message'))
    write(folder/('delivery-protocol-error-'+delivery_version+'.json'),{'source':'Actual Coding Node delivery protocol validation','receipt':receipt,'nativeSessionId':original_native_id})
    if identity in protocol_seen:terminal='NO_PROGRESS_AFTER_DELIVERY_DECLARATION_FEEDBACK';break
    protocol_seen.add(identity)
    message='Coding Node 的真实交付协议校验未通过：'+json.dumps(receipt,ensure_ascii=False)+'。请使用当前工作区的原生文件工具修复 delivery_manifest.json 的产物声明、路径或文件，保留正确的原始数据和业务计算。'
    api('/v1/sessions/'+session+'/messages',{'text':message});protocol_feedbacks+=1;revision+=1;terminal=await_turn()
    if api('/v1/sessions/'+session)['nativeSessionId']!=original_native_id:raise RuntimeError('Delivery feedback must preserve the original native session')
    continue
   fingerprint=hashlib.sha256(json.dumps(preview['artifacts'],sort_keys=True).encode()).hexdigest()
   if fingerprint in seen:terminal='NO_PROGRESS_AFTER_REAL_VALIDATION_FEEDBACK';break
   seen.add(fingerprint);events=api('/v1/sessions/'+session+'/events')
   business_state,validation=control.review(preview,events,delivery_version)
   if business_state['status']=='BUSINESS_VALIDATED_PENDING_SEAL':break
   feedback=feedback_for(validation,delivery_version)
   if business_state['status']!='BUSINESS_VALIDATION_FAILED' or feedback is None:terminal='EXPLICIT_RUNTIME_INPUT_OR_VERIFIER_FAILURE';break
   if time.monotonic()-started>=1200:terminal='DEADLINE_EXCEEDED';break
   control.continue_after_receipt(delivery_version)
   write(folder/('validation-feedback-'+delivery_version+'.json'),{'source':'Actual Java independent verifier','text':feedback,'nativeSessionId':original_native_id,'taskRedispatched':False,'workerRestarted':False})
   api('/v1/sessions/'+session+'/messages',{'text':feedback});feedback_receipts+=1;revision+=1
   terminal=await_turn()
   if api('/v1/sessions/'+session)['nativeSessionId']!=original_native_id:raise RuntimeError('Validation feedback must preserve the original native session')
 if terminal=='turn_completed':
  write(folder/'seal.json',api('/v1/sessions/'+session+'/seal',{}))
  delivery=api('/v1/sessions/'+session+'/delivery',{'deliveryVersion':delivery_version,**({} if args.delivery_selection else {'files':inventory()})})
  if args.runtime_feedback and delivery!=preview:raise RuntimeError('Sealed workspace differs from independently reviewed immutable delivery')
  write(folder/'delivery_manifest.json',delivery);write(args.fixture/'delivery_manifest.json',delivery)
  events=api('/v1/sessions/'+session+'/events');write(folder/'events.json',events);write(args.fixture/'native-events.json',events)
  if restricted:(folder/'sse-wire-first-event.txt').write_text(restricted.first_sse_frame(session))
  else:
   request=urllib.request.Request(f'http://127.0.0.1:{args.port}/v1/sessions/{session}/events',headers={'Authorization':'Bearer '+profile['apiToken'],'Accept':'text/event-stream'})
   with urllib.request.urlopen(request,timeout=10) as response:
    frame=[]
    for _ in range(5):
     line=response.readline().decode();frame.append(line)
     if not line.strip():break
    (folder/'sse-wire-first-event.txt').write_text(''.join(frame))
  if args.runtime_feedback:
   core_stop();container_stopped=True;observed=core_inspect()
   proof={'id':container,'state':observed['State']};write(folder/'container-final.json',proof)
   business_state=control.finalize(delivery,events,proof,delivery_version)
   if business_state['status']!='COMPLETED':raise RuntimeError('Java finality not confirmed after seal')
   terminal='BUSINESS_COMPLETED'
  else:terminal='NATIVE_DELIVERY_PREPARED_PENDING_JAVA'
except Exception as error:
 terminal='PREPARATION_OR_EXECUTION_FAILURE';write(folder/'failure.json',{'error':repr(error)})
finally:
 if restricted and restricted.container and container is None:container=restricted.container
 versions()
 if container:
  try:
   if session and terminal not in ['NATIVE_DELIVERY_PREPARED_PENDING_JAVA','BUSINESS_COMPLETED']:
    try:write(folder/'cancel.json',api('/v1/sessions/'+session+'/cancel',{}))
    except Exception as error:write(folder/'native-cancel-error.json',{'error':repr(error)})
   if not container_stopped:core_stop()
   info=core_inspect();write(folder/'container-final.json',{'id':container,'state':info['State'],'resources':{k:info['HostConfig'].get(k) for k in ['Memory','NanoCpus','PidsLimit','ReadonlyRootfs','NetworkMode']},**({'daemonSocket':restricted.cfg['dockerSocket'],'daemonId':restricted.cfg['expectedDaemonId']} if restricted else {})})
   if restricted:(folder/'container.log').write_text(restricted.logs())
   else:
    log=subprocess.run(['docker','logs',container],text=True,capture_output=True,timeout=20);(folder/'container.log').write_text(log.stdout+log.stderr)
  except Exception as error:write(folder/'stop-error.json',{'error':repr(error)})
 if control and terminal!='BUSINESS_COMPLETED':
  try:
   current=json.loads((args.fixture/'java-state.private.json').read_text())
   if current['status'] in ['RUNNING','RECEIVED_PENDING_BUSINESS_VALIDATION','BUSINESS_VALIDATED_PENDING_SEAL']:
    (args.fixture/'failure-reason.txt').write_text(terminal);control.java('fail-active',delivery_version)
  except Exception as error:write(folder/'java-stop-error.json',{'error':repr(error)})
 if restricted:restricted.close()
 proxy.shutdown();audit.active.pop(case,None)
 receipts=[json.loads(p.read_text()) for p in (folder/'model').glob('*-receipt.json') if p.name[0].isdigit()]
 usage_complete=bool(receipts) and all(isinstance((r.get('usage') or {}).get(k),int) for r in receipts for k in ['prompt_tokens','completion_tokens','total_tokens'])
 totals={key:sum((r.get('usage') or {}).get(key,0) or 0 for r in receipts) for key in ['prompt_tokens','completion_tokens','total_tokens']}
 summary={'case':case,'terminal':terminal,'imageId':args.image,'nativeSessionId':session,'modelRequests':len(receipts),'usage':totals if usage_complete else None,'observedUsageSubtotal':totals,'meteringComplete':usage_complete,'elapsedSeconds':round(time.monotonic()-started,3),'businessAcceptance':'COMPLETED' if terminal=='BUSINESS_COMPLETED' else 'NOT_ACCEPTED' if args.runtime_feedback else 'PENDING_JAVA_VERIFICATION','actualValidationFeedbackReceipts':feedback_receipts,'hostDeliveryProtocolFeedbackReceipts':protocol_feedbacks,'deliveryVersion':delivery_version,'cost':None,'fullNativeEvidence':'events.json','sourceVersions':'versions/'}
 write(folder/'summary.json',summary);print(json.dumps(summary,ensure_ascii=False))
