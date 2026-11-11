"""Independent offline replay of a stopped Native delivery exported by Java.

Requires export-replay-snapshot from the immutable registered Silo versions.
Executes the Agent's actual successful command, never an analysis replacement.
Only source/input/auxiliary files are present before execution. This records
replay facts and does not by itself declare full report/business acceptance.
"""
from pathlib import Path
import json,subprocess,shutil,os,hashlib,sys,argparse,shlex
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--case',required=True);p.add_argument('--fixture',type=Path,required=True);p.add_argument('--official-cli',action='store_true');options=p.parse_args()
if not options.case.replace('-','').isalnum():raise ValueError('Explicit owned case required')
r=options.root;folder=r/'runs'/options.case;export=options.fixture/('independent-cli-replay-export' if options.official_cli else 'independent-replay-export')
summary=json.loads((folder/'summary.json').read_text());image=summary.get('imageId') or json.loads((folder/'intent.json').read_text())['imageId'];manifest=json.loads((export/'delivery_manifest.json').read_text())
if options.official_cli:
 descriptor=json.loads((options.fixture/'cli-delivery-archive.private.json').read_text())
 if manifest['schemaVersion']!='official_cli_comparison.v1' or manifest['artifacts']!=[{key:item[key] for key in ['path','type','size','sha256']} for item in descriptor['artifacts']]:raise RuntimeError('Silo CLI export differs from immutable comparison inventory')
else:
 if manifest!=json.loads((folder/'delivery_manifest.json').read_text()):raise RuntimeError('Silo export manifest differs from sealed delivery')
def source_program(command):
 if not command.startswith('cd /workspace/') or '\n' in command:return False
 try:tokens=list(shlex.shlex(command,posix=True,punctuation_chars=';&|'))
 except ValueError:return False
 sources={a['path'] for a in manifest['artifacts'] if a['type'] in ['SOURCE','TEST']}
 for index,token in enumerate(tokens):
  if Path(token).name not in ['python','python3','python3.11']:continue
  arguments=tokens[index+1:]
  while arguments and arguments[0] in ['-u','-B','-E','-s']:arguments=arguments[1:]
  if not arguments:continue
  if arguments[0]=='-m' and len(arguments)>1:
   module=arguments[1].replace('.','/')
   if module+'.py' in sources or module+'/__main__.py' in sources:return True
  elif not arguments[0].startswith('-'):
   script=arguments[0].removeprefix('./')
   if script in sources or any(Path(path).name==script for path in sources):return True
 return False
events=[] if options.official_cli else json.loads((folder/'events.json').read_text())['events'];starts={};commands=[]
for e in events:
 p=e.get('payload',{})
 if e['eventType']=='tool.started':starts[p.get('toolCallId')]=p
 if e['eventType']=='tool.completed' and p.get('toolName')=='run_commands':
  original=starts.get(p.get('toolCallId'),{}).get('input',{}).get('commands',[])
  for n,result in enumerate(p.get('output',[])):
   if n<len(original) and result.get('success') is True:
    command=original[n]
    if source_program(command):commands.append(({'source':'Native tool.completed','eventId':e['eventId']},command))
if options.official_cli:
 for line_number,line in enumerate((folder/'acp.jsonl').read_text().splitlines(),1):
  record=json.loads(line);message=record['message']
  if record['direction']!='official-cli-to-client' or message.get('method')!='session/update':continue
  update=message.get('params',{}).get('update',{});call_id=update.get('toolCallId')
  if update.get('sessionUpdate')=='tool_call':starts[call_id]=update
  call=starts.get(call_id,{})
  if not call.get('title','').startswith('run_commands') or not isinstance(update.get('rawOutput'),list):continue
  for index,result in enumerate(update['rawOutput']):
   original=call.get('rawInput',{}).get('commands',[])
   if index>=len(original) or result.get('success') is not True:continue
   command=original[index]
   if source_program(command):commands.append(({'source':'Actual official ACP tool output','journalLine':line_number,'toolCallId':call_id},command))
if not commands:raise RuntimeError('No actual successful source-program command; do not invent one')
command_receipt,command=commands[-1];target=r/'verification'/(options.case+'-registered-silo-replay');target.mkdir(parents=True,exist_ok=False);workspace=target/'workspace';shutil.copytree(export/'workspace',workspace,symlinks=True)
for p in [workspace,*workspace.rglob('*')]:
 if p.is_symlink():raise RuntimeError('Replay does not follow generated symlinks')
 os.chown(p,10001,10001)
for a in manifest['artifacts']:
 if a['type'] in ['REPORT','RESULT','CHART'] and (workspace/a['path']).exists():raise RuntimeError('Derived outputs must be absent before replay')
before={a['path']:hashlib.sha256((folder/'workspace'/a['path']).read_bytes()).hexdigest() for a in manifest['artifacts']}
name='coding-node-registered-replay-'+options.case;local='/workspace/'+manifest['taskId']
args=['docker','create','--name',name,'--label','runtime-os.coding.acceptance='+options.case+'-independent-replay','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','3g','--cpus','2','--pids-limit','128','--tmpfs','/tmp:rw,nosuid,nodev,size=128m','--entrypoint','/bin/bash','-v',str(workspace)+':'+local,'-v',str(workspace/'input')+':'+local+'/input:ro',image,'-lc',command]
p=subprocess.run(args,text=True,capture_output=True,timeout=30)
if p.returncode:raise RuntimeError('Replay create failed')
ident=p.stdout.strip();info=json.loads(subprocess.check_output(['docker','inspect',ident]))[0];sys.path.insert(0,str(r));from resource_registry import register_container
register_container(r,info,name,'Offline actual Agent program command, only Silo-registered code and inputs; no old outputs or Agent',options.case+'-independent-replay')
with (target/'stdout.private.log').open('x') as out,(target/'stderr.private.log').open('x') as err:
 try:p=subprocess.run(['docker','start','-a',ident],stdout=out,stderr=err,timeout=120)
 except subprocess.TimeoutExpired:
  subprocess.run(['docker','stop','-t','3',ident],check=True,timeout=20)
  (target/'timeout.json').write_text(json.dumps({'deadlineSeconds':120,'ownedContainerStopped':ident,'businessTaskAcceptance':False}))
  raise
info=json.loads(subprocess.check_output(['docker','inspect',ident]))[0];comparisons=[]
for a in manifest['artifacts']:
 if a['type'] not in ['REPORT','RESULT','CHART']:continue
 original=(export/'expected'/a['path']).read_bytes();actual_path=workspace/a['path'];actual=actual_path.read_bytes() if actual_path.exists() else None
 comparisons.append({'path':a['path'],'type':a['type'],'regenerated':actual is not None,'exactBytesEqual':actual==original,'structuredValueEqual':json.loads(actual)==json.loads(original) if a['type']=='RESULT' and actual is not None else None,'businessSummaryEqual':json.loads(actual).get('verifiedAccountSummary')==json.loads(original).get('verifiedAccountSummary') if a['type']=='RESULT' and actual is not None else None})
after={a['path']:hashlib.sha256((folder/'workspace'/a['path']).read_bytes()).hexdigest() for a in manifest['artifacts']}
proof={'source':'Immutable registered Silo snapshot and actual successful tool command; no replacement analysis program','commandReceipt':command_receipt,'command':command,'imageId':image,'containerId':ident,'exitCode':info['State']['ExitCode'],'network':'none','onlyRegisteredCodeAndInputs':True,'oldDerivedOutputsAbsentBeforeReplay':True,'originalArtifactsUnchanged':before==after,'comparisons':comparisons,'businessTaskAcceptance':False,'replayChecksPassed':p.returncode==0 and info['State']['ExitCode']==0 and not info['State']['Running'] and before==after and all(c['regenerated'] and (c['businessSummaryEqual'] if c['type']=='RESULT' else (workspace/c['path']).stat().st_size>0) for c in comparisons)}
(target/'replay.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof,indent=2))
