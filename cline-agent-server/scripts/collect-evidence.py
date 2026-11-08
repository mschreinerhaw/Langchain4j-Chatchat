"""Export scrubbed source/build/native HTTP evidence without executing or repairing tasks."""
import pathlib,sys,json,hashlib,tarfile,difflib,subprocess,shutil,gzip,datetime
root=pathlib.Path(sys.argv[1]).resolve();out=pathlib.Path(sys.argv[2]).resolve();out.mkdir(parents=True,exist_ok=True)
up=root/'build/cline-a459f1d04dea030833b558be645011ab739c463b'
cfg=json.loads((root/'private/server.json').read_text())
secrets=[cfg['apiKey'],cfg['apiToken']]
def scrub(text):
 for value in secrets:text=text.replace(value,'[REDACTED]')
 return text
def save(path,data):
 p=out/path;p.parent.mkdir(parents=True,exist_ok=True)
 text=data if isinstance(data,str) else json.dumps(data,ensure_ascii=False,indent=2)+'\n'
 p.write_text(scrub(text))
def digest(data):return hashlib.sha256(data).hexdigest()
def public_events(value):
 if isinstance(value,dict):
  if value.get('eventType')=='native.event':
   payload=value.get('payload',{})
   if payload.get('type')=='chunk' and payload.get('payload',{}).get('stream')=='agent':return None
   if payload.get('type')=='agent_event' and payload.get('payload',{}).get('event',{}).get('contentType')=='reasoning':return None
   if payload.get('type')=='session_snapshot':
    payload=dict(payload);inner=dict(payload.get('payload',{}));snapshot=dict(inner.get('snapshot',{}));snapshot.pop('messages',None);snapshot.pop('metadata',None);inner['snapshot']=snapshot;payload['payload']=inner;value=dict(value);value['payload']=payload
  return {k:public_events(v) for k,v in value.items()}
 if isinstance(value,list):
  items=[public_events(v) for v in value];return [v for v in items if v is not None]
 return value
def failure(t):
 output=t.get('output');values=output if isinstance(output,list) else [output]
 return bool(t.get('error')) or any(isinstance(v,dict) and (v.get('success') is False or bool(v.get('error'))) for v in values)
changes=[];core_checked=0;core_changes=[];patches=[]
with tarfile.open(root/'input/source.tgz') as archive:
 for member in archive:
  if not member.isfile():continue
  relative=member.name.split('/',1)[1] if '/' in member.name else ''
  watched=relative.startswith(('sdk/packages/agents/src/','sdk/packages/core/src/','apps/vscode/src/')) and relative.endswith(('.ts','.tsx'))
  if not watched:continue
  after=up/relative
  if not after.exists():continue
  before_bytes=archive.extractfile(member).read();after_bytes=after.read_bytes()
  if relative.startswith(('sdk/packages/agents/src/','sdk/packages/core/src/')):
   core_checked+=1
   if before_bytes!=after_bytes:core_changes.append(relative)
  if before_bytes!=after_bytes:
   changes.append({'path':relative,'beforeSha256':digest(before_bytes),'afterSha256':digest(after_bytes)})
   if relative in ['apps/vscode/src/standalone/cline-core.ts','apps/vscode/src/sdk/sdk-interaction-coordinator.ts','apps/vscode/src/sdk/sdk-session-event-coordinator.ts','apps/vscode/src/sdk/vscode-session-host.ts']:
    patches.extend(difflib.unified_diff(before_bytes.decode().splitlines(True),after_bytes.decode().splitlines(True),fromfile='a/'+relative,tofile='b/'+relative))
save('native-source-integrity.json',{'upstreamCommit':'a459f1d04dea030833b558be645011ab739c463b','comparedCoreAndAgentsSourceFiles':core_checked,'changedCoreAndAgentsSourceFiles':core_changes,'changedProductSourceFiles':changes})
save('native-host-diff.patch',''.join(patches))
artifacts=[]
for p in [root/'input/source.tgz',root/'input/bun-linux.zip',root/'input/poc-service-r3.tgz',root/'input/poc-service.tgz',up/'bun.lock',up/'apps/vscode/dist-standalone/bun.lock',root/'runtime/cline-core.js',root/'runtime/proto/descriptor_set.pb',root/'runtime/plugin-runtime/plugin-sandbox-bootstrap.js']:
 if p.is_file():artifacts.append({'path':str(p.relative_to(root)),'bytes':p.stat().st_size,'sha256':digest(p.read_bytes())})
for p in (root/'runtime').rglob('better_sqlite3.node'):artifacts.append({'path':str(p.relative_to(root)),'bytes':p.stat().st_size,'sha256':digest(p.read_bytes())})
container=json.loads(subprocess.check_output(['docker','inspect','cline-agent-server-poc']))[0]
save('build-and-runtime.json',{'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'artifacts':artifacts,'container':{'id':container['Id'],'imageId':container['Image'],'user':container['Config']['User'],'memoryLimit':container['HostConfig']['Memory'],'nanoCpus':container['HostConfig']['NanoCpus'],'cpusetCpus':container['HostConfig']['CpusetCpus'],'capDrop':container['HostConfig']['CapDrop'],'securityOpt':container['HostConfig']['SecurityOpt'],'portBindings':container['HostConfig']['PortBindings']},'modelProfile':{'model':cfg['model'],'protocol':'OpenAI-compatible','credential':'authorized existing platform profile, never exported','pricing':'UNKNOWN; native zero pricing is not billing evidence'}})
for name in ['install.log','build-sdk.log','compile.log','package-linux.log','baseline-project-build.log']:
 if (root/name).exists():save('build-logs/'+name,(root/name).read_text())
for case in ['editor-r1-adapter-failure','editor-r2-resource-failure','editor','patch','controls','search']:
 src=root/'evidence'/case
 if not src.exists() or not (src/'summary.json').exists():continue
 for p in src.iterdir():
  if not p.is_file():continue
  text=scrub(p.read_text())
  if p.name in ['events.json','http.json']:text=json.dumps(public_events(json.loads(text)),ensure_ascii=False,indent=2)
  if p.name in ['events.json','http.json']:
   dest=out/case/(p.name+'.gz');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(gzip.compress(text.encode(),mtime=0))
  else:save(case+'/'+p.name,text)
 # Recompute outcome from native structured output; retain the original transport events unchanged.
 if (src/'events.json').exists():
  events=json.loads((src/'events.json').read_text())
  tools=[]
  for e in events:
   if e['eventType'] in ['tool.completed','tool.failed']:
    tools.append({'eventId':e['eventId'],'timestamp':e['timestamp'],'transportEventType':e['eventType'],'normalizedOutcome':'FAILED' if failure(e['payload']) else 'SUCCEEDED','native':e['payload']})
  save(case+'/native-tool-results.json',tools)
  usage=[e['payload'] for e in events if e['eventType']=='model.usage']
  save(case+'/native-usage.json',{'usageEvents':len(usage),'lastUsage':usage[-1] if usage else None,'billingCost':'UNKNOWN'})
for case,folder in [('editor','project'),('patch','project-patch'),('stock','project-stock')]:
 project=root/folder/'coding-agent-node'
 summary_file=root/'stock-state/summary.json' if case=='stock' else root/'evidence'/case/'summary.json'
 if not project.exists() or not summary_file.exists():continue
 cmd=['git','-c','safe.directory='+str(project),'-C',str(project)]
 result=subprocess.run(cmd+['diff','--no-ext-diff','HEAD','--'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if result.returncode:save(case+'/diff-error.txt',result.stderr.decode());continue
 text=result.stdout.decode()
 others=subprocess.check_output(cmd+['ls-files','--others','--exclude-standard','-z']).decode().split('\0')
 for name in others:
  if not name or not (project/name).is_file():continue
  if not (name.startswith(('src/','test/')) or name=='README.md'):continue
  text+=''.join(difflib.unified_diff([], (project/name).read_text().splitlines(True),fromfile='/dev/null',tofile='b/'+name))
 save(case+'/complete-project-changes.patch',text)
 files=[]
 for name in ['src/monitor-client.ts','src/monitor-types.ts','README.md','test/monitor-client-parameters.test.js','dist/monitor-client.js']:
  p=project/name
  if p.exists():files.append({'path':name,'bytes':p.stat().st_size,'sha256':digest(p.read_bytes())})
 save(case+'/result-files.json',{'sourceProjectCommit':'7d076d0dc','frozenFixtureCommit':subprocess.check_output(cmd+['rev-parse','HEAD']).decode().strip(),'files':files})
stock=root/'stock-state'
for name in ['source-verification.json','task-manifest.json','submission.json','final-state.json','summary.json','failure.json','independent-verification.log','host-events.json','fixture-restoration.json','restored-regression.log','restored-regression.json']:
 if (stock/name).exists():save('stock/'+name,(stock/name).read_text())
if (root/'stock-build.log').exists():save('build-logs/stock-build.log',(root/'stock-build.log').read_text())
if (root/'evidence/authentication.json').exists():save('authentication.json',(root/'evidence/authentication.json').read_text())
if (root/'evidence/final-smoke.json').exists():save('final-smoke.json',(root/'evidence/final-smoke.json').read_text())
manifest=[]
for p in sorted(out.rglob('*')):
 if p.is_file() and p.name!='evidence-manifest.json':
  data=p.read_bytes()
  # Compressed exports were scrubbed before compression; check the original bytes as well.
  inspected=gzip.decompress(data) if p.suffix=='.gz' else data
  if any(secret.encode() in inspected for secret in secrets):raise RuntimeError('Credential found in export: '+str(p))
  manifest.append({'path':str(p.relative_to(out)),'bytes':len(data),'sha256':digest(data)})
save('evidence-manifest.json',{'files':manifest,'credentialScan':'PASS_FOR_CONFIGURED_MODEL_AND_OPERATOR_SECRETS','rawReasoning':'omitted, including duplicate serialized agent chunks; original event IDs are preserved and may have gaps'})
print(json.dumps({'exportedFiles':len(manifest),'coreSourceFilesChecked':core_checked,'changedCoreFiles':core_changes,'destination':str(out)}))
