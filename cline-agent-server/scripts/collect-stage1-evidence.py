"""Read-only export of real P3.1 acceptance. Never executes or repairs task code."""
import pathlib,sys,json,hashlib,gzip,subprocess,tarfile,difflib,base64,hmac
from evidence_files import safe_bytes,tree_bytes
r=pathlib.Path(sys.argv[1]).resolve();out=pathlib.Path(sys.argv[2]).resolve();out.mkdir(parents=True,exist_ok=True)
cfg=json.loads((r/'control/config.json').read_text());secrets=[cfg['signingKey']]+[p['apiKey'] for p in cfg['modelProfiles'].values()]+[t['mcp']['token'] for t in cfg['tenants'].values() if t.get('mcp')]
registries=[json.loads(p.read_text()) for p in (r/'control/workers').glob('*/registry.json')]
running=subprocess.check_output(['docker','ps','--filter','label=cline.p3=stage1','--format','{{.ID}}'],text=True).splitlines();assert not running,'Stop and inspect all task containers before reading workspace snapshots'
for record in registries:secrets.extend([record['apiToken'],record['brokerToken']])
def sha(b):return hashlib.sha256(b).hexdigest()
def scrub(text):
 for value in secrets:text=text.replace(value,'[REDACTED]')
 return text
def save(path,value,compressed=False):
 p=out/path;p.parent.mkdir(parents=True,exist_ok=True);text=value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2)+'\n';data=scrub(text).encode()
 if compressed:p=p.with_name(p.name+'.gz');data=gzip.compress(data,mtime=0)
 p.write_bytes(data)
live=r/'evidence/live';summary=json.loads((live/'stage1-summary.json').read_text());assert summary['status']=='PASS'
for p in sorted(live.rglob('*')):
 if not p.is_file():continue
 relative=p.relative_to(live);text=p.read_text()
 if p.name=='revocation-native-events.json':text=json.dumps([json.loads(line) for line in text.splitlines() if line],ensure_ascii=False,indent=2)
 save(str(relative),text,compressed=p.name in ['http.json','native-events.json','revocation-native-events.json','revocation-http.json'])
for name in ['build.log','build-r2.log','build-final.log','finish.log','live-final.log','adversarial-final.log','revoke-final.log','sse-final.log']:
 p=r/name
 if p.exists():save('logs/'+name,p.read_text())
if (r/'evidence/mcp-fixture-initial-failure.log').exists():save('failures/mcp-fixture-initial-failure.log',(r/'evidence/mcp-fixture-initial-failure.log').read_text())
for folder in ['startup-r1-failure','policy-r2-failure']:
 for p in (r/'evidence'/folder).rglob('*'):
  if p.is_file():save('failures/'+folder+'/'+str(p.relative_to(r/'evidence'/folder)),p.read_text(),compressed=p.name=='http.json')
previous=r/'evidence/live-r3-pass'
for name in ['coding-summary.json','adversarial-summary.json','revocation-summary.json']:
 if (previous/name).exists():save('previous-pass/'+name,(previous/name).read_text())
contracts=json.loads((live/'contracts.json').read_text())
for name in ['a','b']:
 record=next(x for x in registries if x['contract']['sessionId']==contracts[name]['sessionId']);root=r/'control/workers'/record['workerId'];workspace=root/'workspace'
 events=json.loads((live/name/'native-events.json').read_text());results=[]
 for e in events:
  if e['eventType'] in ['tool.completed','tool.failed']:
   payload=e['payload'];values=payload.get('output');values=values if isinstance(values,list) else [values];failed=bool(payload.get('error')) or any(isinstance(v,dict) and (v.get('success') is False or v.get('error')) for v in values)
   results.append({'eventId':e['eventId'],'transportEventType':e['eventType'],'normalizedOutcome':'FAILED' if failed else 'SUCCEEDED','native':payload})
 save(name+'/native-tool-results.json',results)
 template=r/'templates'/pathlib.Path(cfg['tenants'][record['contract']['tenantId']]['workspaces'][record['contract']['workspaceRef']]).relative_to('/templates')
 before,baseline_skipped=tree_bytes(template);after,skipped=tree_bytes(workspace);assert not baseline_skipped
 modified=[path for path,data in before.items() if after.get(path)!=data];assert not modified,'Existing business files unexpectedly changed: '+str(modified)
 patch='';files=[]
 for path,data in sorted(after.items()):
  if path in before:continue
  files.append({'path':path,'bytes':len(data),'sha256':sha(data)});patch+=''.join(difflib.unified_diff([],data.decode().splitlines(True),fromfile='/dev/null',tofile='b/'+path))
 tree_hash=sha(json.dumps(sorted((path,sha(data)) for path,data in before.items())).encode())
 save(name+'/complete-project-changes.patch',patch);save(name+'/result-files.json',{'frozenFixtureTreeSha256':tree_hash,'existingTrackedFilesChanged':modified,'files':files,'untrustedLinksOrSpecialFilesNotDereferenced':skipped,'verification':'Direct immutable-template byte comparison; no Git execution against untrusted workspace'})
 mapping={k:record.get(k) for k in ['workerId','containerId','nativeSessionId','workerState','taskState','revoked','slot','stopProof']};mapping['contractHash']=record['contractHash'];save(name+'/final-mapping.json',mapping)
 # Preserve the exact policy failure from R2; no rewritten success history.
failure_ids=set()
for folder in ['startup-r1-failure','policy-r2-failure']:
 p=r/'evidence'/folder/'contracts.json'
 if p.exists():failure_ids.update(c['sessionId'] for c in json.loads(p.read_text()).values())
for record in registries:
 if record['contract']['sessionId'] not in failure_ids:continue
 faults=[]
 for p in (r/'control/workers'/record['workerId']/'state').glob('*/events.jsonl'):
  try:raw=safe_bytes(r/'control/workers'/record['workerId']/'state',p.relative_to(r/'control/workers'/record['workerId']/'state')).decode()
  except (OSError,ValueError):continue
  faults.extend(json.loads(line) for line in raw.splitlines() if json.loads(line)['eventType'] in ['tool.failed','permission.decision','permission.resolved','task.failed'])
 if faults:save('failures/native-'+record['workerId']+'.json',faults)
save('control-audit.json',(r/'control/audit.jsonl').read_text().splitlines() and [json.loads(line) for line in (r/'control/audit.jsonl').read_text().splitlines()])
save('mcp-real-file-receipts.json',[json.loads(line) for line in (r/'mcp/requests.jsonl').read_text().splitlines()])
old=pathlib.Path('/opt/cline-agent-server-poc-20261108');up=old/'build/cline-a459f1d04dea030833b558be645011ab739c463b';checked=0;changed=[];host_changes=[]
with tarfile.open(old/'input/source.tgz') as archive:
 for m in archive:
  if not m.isfile() or '/' not in m.name:continue
  path=m.name.split('/',1)[1]
  if not path.endswith(('.ts','.tsx')):continue
  target=up/path
  if not target.exists():continue
  if path.startswith(('sdk/packages/core/src/','sdk/packages/agents/src/')):
   checked+=1
   if archive.extractfile(m).read()!=target.read_bytes():changed.append(path)
  elif path.startswith('apps/vscode/src/'):
   before=archive.extractfile(m).read();after=target.read_bytes()
   if before!=after:host_changes.append({'path':path,'beforeSha256':sha(before),'afterSha256':sha(after)})
assert checked==608 and not changed and len(host_changes)==4
save('native-source-integrity.json',{'upstreamCommit':'a459f1d04dea030833b558be645011ab739c463b','comparedCoreAndAgentsSourceFiles':checked,'changedCoreAndAgentsSourceFiles':changed,'existingHostPatches':host_changes,'newUpstreamPatchesInP3':[]})
sources=[{'path':str(p.relative_to(r/'context/service')),'sha256':sha(p.read_bytes())} for p in sorted((r/'context/service/src').glob('*.mjs'))];save('adapter-files.json',sources)
runtime=subprocess.check_output(['docker','exec','cline-p3-control','node','/poc/service/scripts/verify-runtime.mjs','/poc/runtime'],text=True);save('runtime-verification.json',json.loads(runtime))
running=subprocess.check_output(['docker','ps','--filter','label=cline.p3=stage1','--format','{{.ID}}'],text=True).splitlines();assert not running
parent=json.loads(subprocess.check_output(['docker','image','inspect','cline-agent-server:4.1.26-clean-poc']))[0];assert parent['Id']=='sha256:f23feae55353f57f2e7fb68455a41b2f26cd383b0ca04f928e0072ad7b869ace'
save('deployment-final.json',{'pocBaselineCommit':'62ce5d9e5','workerImageId':cfg['workerImage'],'verifiedParentImageId':parent['Id'],'gatewayLoopbackPort':cfg['port'],'runningStage1WorkerContainers':running,'root':str(r),'productionDefaultModified':False,'testMCP':'Official SDK, actual scoped private files; separate service; no production MCP/Sandbox configuration changes'})
manifest=[]
for p in sorted(out.rglob('*')):
 if not p.is_file() or p.name=='evidence-manifest.json':continue
 data=p.read_bytes();inspected=gzip.decompress(data) if p.suffix=='.gz' else data
 assert not any(value.encode() in inspected for value in secrets),'Private credential in export: '+str(p)
 assert b'"contentType": "reasoning"' not in inspected,'Raw reasoning in export'
 manifest.append({'path':str(p.relative_to(out)),'bytes':len(data),'sha256':sha(data)})
save('evidence-manifest.json',{'files':manifest,'credentialScan':'PASS configured issuer/model/MCP/worker credentials','reasoning':'Excluded at native event boundary; raw tool receipts preserved','scope':'P3.1 foundation only; recovery and MCP/Python remote stop NOT_RUN'})
print(json.dumps({'exportedFiles':len(manifest),'coreFilesChecked':checked,'coreFilesChanged':changed,'nextStage':'STOP_FOR_REVIEW'}))
