"""Real official CLI + Silo comparison with same-Session Java feedback.

Only the official ACP process runs the Agent. The harness provides permissions,
unchanged model transport, immutable input transfer, genuine validation feedback
and evidence. It never writes an analysis program or forges Native receipts.
"""
from pathlib import Path
import argparse, hashlib, http.server, json, os, secrets, subprocess, sys, threading, time
import live_model_audit as audit
from official_acp_client import OfficialAcpClient
from native_silo_control import SiloControl,feedback_for
from resource_registry import register_container

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--fixture',type=Path,required=True)
p.add_argument('--case',required=True);p.add_argument('--image',required=True);p.add_argument('--prompt',type=Path,required=True)
p.add_argument('--proxy-port',type=int,default=18540);p.add_argument('--delivery-selection',action='store_true');a=p.parse_args()
if not a.image.startswith('sha256:') or not a.case.replace('-','').isalnum():raise ValueError('Pinned image and unique case required')
os.umask(0o077);folder=a.root/'runs'/a.case;folder.mkdir(mode=0o700)
workspace=folder/'workspace';state=folder/'state'
for path in [workspace,state]:path.mkdir();os.chown(path,10001,10001);path.chmod(0o700)
harness=folder/'harness';harness.mkdir();sources=[]
for source in [Path(__file__),Path(audit.__file__),Path(sys.modules[OfficialAcpClient.__module__].__file__),Path(sys.modules[SiloControl.__module__].__file__),Path(sys.modules[register_container.__module__].__file__),a.prompt,a.root/'official-cli-entrypoint.py',a.root/'configure-official-cli.py']:
    raw=source.read_bytes();(harness/source.name).write_bytes(raw);sources.append({'name':source.name,'sha256':hashlib.sha256(raw).hexdigest()})
    if source.name in ['official-cli-entrypoint.py','configure-official-cli.py']:(harness/source.name).chmod(0o444)
(harness/'versions.json').write_text(json.dumps(sources,indent=2))
profile=json.loads((a.root/'private/settings.json').read_text());profile['proxyToken']=secrets.token_hex(32)
audit.configure(a.root,profile)
plan=json.loads((a.fixture/'worker-plan.private.json').read_text());config=json.loads((a.root/'private'/(a.fixture.name+'.json')).read_text())
local='/workspace/'+plan['binding']['taskId'];prompt=a.prompt.read_text().replace('{{customerNumber}}',config['customerNumber'])
connection=folder/'connection.private.json'
connection.write_text(json.dumps({'protocol':'acp','outputTokenPolicy':'provider-default','workspace':local,'apiKey':profile['proxyToken'],
    'model':profile['model'],'baseUrl':f'http://cline-model-audit:{a.proxy_port}/{a.case}/v1'}));os.chown(connection,10001,10001);connection.chmod(0o400)
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2))
def docker(*args,timeout=60):
    result=subprocess.run(['docker',*args],text=True,capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stderr[-1200:])
    return result.stdout.strip()
def register(ident,name,purpose):register_container(a.root,json.loads(docker('inspect',ident))[0],name,purpose,a.case)
def inventory():
    files=[]
    for path in sorted(workspace.rglob('*')):
        relative=path.relative_to(workspace)
        if path.is_symlink() or not path.is_file() or any(part in ['input','logs','evidence','.git','__pycache__','.pytest_cache','.cline'] for part in relative.parts) or str(relative)=='delivery_manifest.json':continue
        kind={'.py':'SOURCE','.md':'REPORT','.png':'CHART','.svg':'CHART'}.get(path.suffix,'AUXILIARY')
        if path.suffix=='.json':
            try:
                value=json.loads(path.read_text())
                if isinstance(value,dict) and 'verifiedAccountSummary' in value:kind='RESULT'
            except (ValueError,UnicodeDecodeError):pass
        raw=path.read_bytes();files.append({'path':str(relative),'type':kind,'size':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
    return files

class DeliverySelectionError(Exception):pass

def selection(revision,copy=None):
    """Use the same Node validator as Native; never infer business types from names."""
    name='coding-node-selection-'+a.case+'-'+revision
    mounts=['-v',str(workspace)+':/workspace/task:ro'];arguments=['selection','/workspace/task']
    if copy is not None:
        copy.mkdir(mode=0o700);os.chown(copy,10001,10001)
        mounts+=['-v',str(copy)+':/run/selection-copy'];arguments+=['/run/selection-copy']
    helper=docker('create','--name',name,'--label','runtime-os.coding.acceptance='+a.case,'--network','none','--read-only','--cap-drop','ALL',
        '--security-opt','no-new-privileges','--memory','512m','--pids-limit','64','--tmpfs','/tmp:rw,nosuid,nodev,size=64m',
        *mounts,'--entrypoint','node',a.image,'/poc/service/src/workspace-transfer.mjs',*arguments)
    register(helper,name,'Same-image delivery declaration/path/hash validation; no Agent or credentials')
    result=subprocess.run(['docker','start','-a',helper],text=True,capture_output=True,timeout=60)
    receipt={'containerId':helper,'exitCode':json.loads(docker('inspect',helper))[0]['State']['ExitCode'],'stdout':result.stdout,'stderr':result.stderr}
    write(folder/('selection-'+revision+'.json'),receipt)
    if result.returncode or receipt['exitCode']:raise DeliverySelectionError(result.stderr[-6000:] or 'Delivery selection validator failed')
    files=json.loads(result.stdout)
    if copy is not None:
        for path in copy.rglob('*'):
            if path.is_dir():path.chmod(0o555)
        copy.chmod(0o555)
    return files
def versions():
    for path in workspace.rglob('*'):
        if path.is_symlink() or not path.is_file() or path.stat().st_size>4*1024*1024 or any(part in ['input','.git','__pycache__','.pytest_cache','.cline'] for part in path.relative_to(workspace).parts):continue
        try:
            raw=path.read_bytes();target=folder/'versions'/hashlib.sha256(raw).hexdigest()/path.relative_to(workspace)
            if not target.exists():target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
        except OSError:pass
watch_stop=threading.Event()
def watch():
    while not watch_stop.wait(.5):versions()
started=time.monotonic();deadline=started+1200
def remaining():
    seconds=deadline-time.monotonic()
    if seconds<=0:raise TimeoutError('External task resource deadline')
    return seconds
write(folder/'intent.json',{'case':a.case,'entrypoint':'unmodified official CLI ACP','imageId':a.image,'clineRelease':'4.1.26','cliComponentVersion':'3.0.74',
    'model':profile['model'],'outputTokenPolicy':'provider-default','thinking':True,'reasoningEffort':'low','modelRequestLimit':'unlimited','toolCallLimit':'unlimited',
    'timeoutSeconds':1200,'deliverySelectionRequired':a.delivery_selection,'inputSnapshotVersion':plan['binding']['inputSnapshotVersion'],'prompt':prompt,'javaReviewFeedback':'same persistent official Session',
    'toolGrants':['read_files','search_codebase','editor','apply_patch','skills','run_commands','submit_and_exit'],
    'authorizationScope':'Previously authorized frozen snapshot; isolated Java/PostgreSQL fixture, not application HTTP acceptance'})
proxy=http.server.ThreadingHTTPServer(('0.0.0.0',a.proxy_port),audit.AuditProxy);threading.Thread(target=proxy.serve_forever,daemon=True).start()
container=None;process=None;session=None;client=None;terminal='PREPARATION_OR_EXECUTION_FAILURE';feedbacks=0;selection_feedbacks=0;review=None;delivery_version=None;chosen=None
control=SiloControl(a.root,a.fixture,a.case,a.image,register)
with (folder/'acp.jsonl').open('w') as journal,(folder/'cli.stderr.log').open('w') as stderr:
    try:
        # Lifecycle byte transfer runs no Agent and exposes no Silo key/URL to Core.
        transfer=folder/'download-plan.private.json';write(transfer,plan);os.chown(transfer,10001,10001);transfer.chmod(0o400)
        name='coding-node-input-'+a.case
        helper=docker('create','--name',name,'--label','runtime-os.coding.acceptance='+a.case,'--network','host','--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges','--memory','512m','--pids-limit','64','--tmpfs','/tmp:rw,nosuid,nodev,size=64m',
            '-v',str(workspace)+':/workspace/task','-v',str(transfer)+':/run/transfer-plan.json:ro','--entrypoint','node',a.image,
            '/poc/service/src/workspace-transfer.mjs','download','/workspace/task','/run/transfer-plan.json')
        register(helper,name,'Same-image Silo input transfer; no CLI/Core Agent')
        download=subprocess.run(['docker','start','-a',helper],text=True,capture_output=True,timeout=330)
        (folder/'download.stdout.json').write_text(download.stdout);(folder/'download.stderr.log').write_text(download.stderr)
        if download.returncode or json.loads(docker('inspect',helper))[0]['State']['ExitCode']:raise RuntimeError('Input download failed; no Agent dispatched')
        mounts=['-v',str(workspace)+':'+local,'-v',str(workspace/'input')+':'+local+'/input:ro','-v',str(state)+':/state','-v',str(connection)+':/run/connection.json:ro']
        for name in ['official-cli-entrypoint.py','configure-official-cli.py']:mounts+=['-v',str(harness/name)+':/run/'+name+':ro']
        name='coding-node-official-'+a.case
        container=docker('create','-i','--name',name,'--label','runtime-os.coding.acceptance='+a.case,'--init','--network','cline-parquet-isolation',
            '--add-host','cline-model-audit:host-gateway','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','3g','--cpus','2',
            '--pids-limit','256','--tmpfs','/tmp:rw,nosuid,nodev,size=256m','-e','HOME=/state','-e','NO_PROXY=cline-model-audit,127.0.0.1,localhost',
            '-e','no_proxy=cline-model-audit,127.0.0.1,localhost','--entrypoint','python',*mounts,a.image,'/run/official-cli-entrypoint.py')
        register(container,name,'Official CLI persistent ACP Agent; same workspace/Python/tool grants; no Native Server loop')
        audit.active[a.case]=time.monotonic()
        process=subprocess.Popen(['docker','start','-a','-i',container],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True,encoding='utf-8',bufsize=1)
        client=OfficialAcpClient(process,journal,['read_files','search_codebase','editor','apply_patch','skills','run_commands','submit_and_exit'])
        write(folder/'initialize.json',client.call('initialize',{'protocolVersion':1,'clientCapabilities':{'fs':{'readTextFile':False,'writeTextFile':False},'terminal':False},'clientInfo':{'name':'runtime-os-comparison-client','version':'1'}},min(60,remaining())))
        session=client.call('session/new',{'cwd':local,'mcpServers':[]},min(60,remaining()))['sessionId']
        selected=client.call('session/set_config_option',{'sessionId':session,'configId':'model','value':profile['model']},min(30,remaining()))
        write(folder/'selected-model.json',selected)
        if not any(option.get('id')=='model' and option.get('currentValue')==profile['model'] for option in selected.get('configOptions',[])):raise RuntimeError('Fixed model was not confirmed; no prompt dispatched')
        write(folder/'session.json',{'sessionId':session,'newSessionCount':1,'processRestarted':False})
        threading.Thread(target=watch,daemon=True).start();message=prompt;seen=set();revision=1
        while True:
            reply=client.call('session/prompt',{'sessionId':session,'prompt':[{'type':'text','text':message}]},remaining())
            delivery_version='v'+str(revision);write(folder/('prompt-response-'+delivery_version+'.json'),reply)
            if reply.get('stopReason')!='end_turn':terminal='OFFICIAL_CLI_STOP_'+str(reply.get('stopReason'));break
            destination=folder/'delivery-versions'/delivery_version;destination.mkdir(parents=True)
            copy=destination/'readonly-verifier-copy'
            if a.delivery_selection:
                try:files=selection(delivery_version,copy)
                except DeliverySelectionError as error:
                    message='交付声明或文件校验失败。以下是同镜像 Node 校验器的真实错误，请修复 delivery_manifest.json 或实际文件后继续交付：\n'+str(error)
                    fingerprint=hashlib.sha256(message.encode()).hexdigest()
                    if fingerprint in seen:terminal='NO_PROGRESS_AFTER_REAL_DELIVERY_PROTOCOL_FEEDBACK';break
                    seen.add(fingerprint);selection_feedbacks+=1
                    write(destination/'actual-delivery-protocol-feedback.json',{'source':'Actual same-image Node delivery validator','text':message,'sessionId':session,'newSessionCreated':False})
                    revision+=1;continue
            else:files=inventory()
            fingerprint=hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()
            if fingerprint in seen:terminal='NO_PROGRESS_AFTER_REAL_VALIDATION_FEEDBACK';break
            seen.add(fingerprint)
            # Java drops DAC override: use a verified, read-only control copy.
            if not a.delivery_selection:
                copy.mkdir(mode=0o700)
                for item in files:
                    raw=(workspace/item['path']).read_bytes()
                    if hashlib.sha256(raw).hexdigest()!=item['sha256']:raise RuntimeError('Output changed during candidate capture')
                    target=copy/item['path'];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw);target.chmod(0o400)
            request={'workspace':str(copy),'artifacts':files,'archiveToSilo':True,'comparisonCase':a.case,'deliveryVersion':delivery_version,'clineSessionId':session}
            write(a.fixture/'cli-review-request.private.json',request);control.java('review-official-cli',delivery_version)
            review=json.loads((a.fixture/'cli-independent-review.private.json').read_text());archive=json.loads((a.fixture/'cli-delivery-archive.private.json').read_text())
            write(destination/'independent-business-review.private.json',review);write(destination/'silo-artifacts.private.json',archive)
            if review['passed']:
                chosen=files;terminal='CLI_JAVA_VALIDATED_PENDING_RECEIPT_AND_REPORT_REVIEW';break
            message=feedback_for(review,delivery_version)
            if message is None:terminal='EXPLICIT_RUNTIME_INPUT_OR_VERIFIER_FAILURE';break
            write(destination/'actual-validation-feedback.json',{'source':'Actual Java independent verifier of immutable Silo inputs/outputs','text':message,'sessionId':session,'newSessionCreated':False,'processRestarted':False})
            feedbacks+=1;revision+=1
    except Exception as error:write(folder/'failure.json',{'error':repr(error)})
    finally:
        if process:
            try:process.stdin.close()
            except Exception:pass
        if container:
            docker('stop','-t','5',container);write(folder/'container-final.json',{'id':container,'state':json.loads(docker('inspect',container))[0]['State']})
        watch_stop.set();versions()
        if process:
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.terminate()
        if chosen is not None:
            try:
                if (selection('stopped') if a.delivery_selection else inventory())!=chosen:terminal='OUTPUT_CHANGED_AFTER_INDEPENDENT_REVIEW'
            except Exception as error:
                terminal='FINAL_DELIVERY_INTEGRITY_FAILURE';write(folder/'final-integrity-error.json',{'error':repr(error)})
        try:control.java('cancel','close-comparison-input-fence')
        except Exception as error:write(folder/'control-fence-close-error.json',{'error':repr(error)})
        proxy.shutdown();audit.active.pop(a.case,None)
receipts=[json.loads(path.read_text()) for path in (folder/'model').glob('*-receipt.json') if path.name[0].isdigit()]
requests=[json.loads(path.read_text()) for path in sorted((folder/'model').glob('*-request.json'))]
complete=bool(receipts) and all(isinstance((r.get('usage') or {}).get(k),int) for r in receipts for k in ['prompt_tokens','completion_tokens','total_tokens'])
totals={k:sum((r.get('usage') or {}).get(k,0) or 0 for r in receipts) for k in ['prompt_tokens','completion_tokens','total_tokens']}
aligned=bool(requests) and all(r.get('model')==profile['model'] and r.get('reasoning_effort')=='low' and not any(k in r for k in ['max_tokens','max_completion_tokens','max_output_tokens']) for r in requests)
summary={'case':a.case,'terminal':terminal,'clineSessionId':session,'actualValidationFeedbackReceipts':feedbacks,'actualDeliveryProtocolFeedbackReceipts':selection_feedbacks,'newSessionCount':1 if session else 0,'processRestarted':False,
    'modelRequests':len(receipts),'usage':totals if complete else None,'observedUsageSubtotal':totals,'meteringComplete':complete,'cost':None,
    'strictRequestParameterMatch':aligned,'elapsedSeconds':round(time.monotonic()-started,3),'deliveryVersion':delivery_version,
    'javaIndependentBusinessVerification':review.get('passed') if review else None,'overallBusinessAcceptance':'PENDING_RECEIPT_AND_REPORT_REVIEW' if terminal=='CLI_JAVA_VALIDATED_PENDING_RECEIPT_AND_REPORT_REVIEW' else 'NOT_ACCEPTED',
    'rawOfficialEvidence':'acp.jsonl','sourceVersions':'versions/','runtimeCompletionRegistered':False,'fullRawLogsArchivedToSilo':False}
write(folder/'summary.json',summary);print(json.dumps(summary))
