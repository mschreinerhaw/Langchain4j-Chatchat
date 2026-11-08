#!/usr/bin/env python3
"""Read-only export from this task's isolated services. Never executes model code."""
import pathlib,json,subprocess,hashlib,gzip,sys,shutil,difflib,datetime
R=pathlib.Path('/opt/cline-server-p3-stage2-20261108');OUT=pathlib.Path(sys.argv[1]);OUT.mkdir(parents=True,exist_ok=True)
cfg=json.loads((R/'private/config.json').read_text());gateway=json.loads((R/'control/config.json').read_text())
secrets=[cfg[k] for k in ('dbPassword','cryptoKey','internalSecret','gatewaySecret','mcpSecret','dispatchSecret','remoteControlSecret','mcpToken')]+[gateway['signingKey']]+[p['apiKey'] for p in gateway['modelProfiles'].values()]
registries=[]
for p in (R/'control/state/workers').glob('*/registry.json'):
 r=json.loads(p.read_text());secrets.extend([r['apiToken'],r['brokerToken']]);registries.append(r)
def sha(b):return hashlib.sha256(b).hexdigest()
def text(value):return value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2)+'\n'
def save(name,value,compress=False):
 content=text(value)
 for secret in secrets:
  if secret:content=content.replace(secret,'[REDACTED]')
 p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);data=content.encode()
 if compress:p=p.with_name(p.name+'.gz');data=gzip.compress(data,mtime=0)
 p.write_bytes(data)
def command(*a):return subprocess.check_output(a,text=True,stderr=subprocess.STDOUT)
for p in (R/'evidence').rglob('*'):
 if p.is_file():save('runs/'+str(p.relative_to(R/'evidence')),p.read_text(),p.stat().st_size>150000)
save('remote/fault-proxy.jsonl',(R/'remote-state/fault-proxy.jsonl').read_text())
remotes=[]
for p in (R/'remote-state').glob('cline-*.json'):
 v=json.loads(p.read_text());remotes.append(v);save('remote/operations/'+p.name,v)
for p in (R/'remote-state').glob('*.progress'):save('remote/progress/'+p.name,p.read_text())
sql="select json_build_object('tenantId',p.tenant_id,'userId',p.user_id,'runId',p.run_id,'envelope',e.record_json::json) from runtime_evidence_partition p join runtime_evidence_entry e on e.partition_id=p.id where e.entry_kind='EXEC_CHECKPOINT' order by p.id"
rows=[json.loads(x) for x in command('docker','exec','cline-p32-db','psql','-U','stage2','-d','stage2','-At','-c',sql).splitlines() if x]
save('journal/checkpoints.json',rows,True);matrix=[]
for row in rows:
 journal=json.loads(row['envelope']['value'])
 for taskId,task in journal['tasks'].items():
  for opId,op in task['operations'].items():
   b=task.get('privateState',{}).get('native:'+opId,{})
   matrix.append({'tenantId':row['tenantId'],'userId':row['userId'],'runId':row['runId'],'taskId':taskId,'operationId':opId,'kind':op.get('kind'),'intentPersisted':op.get('startIntent'),'startingFence':op.get('startingFencingToken'),'handle':op.get('executionHandle'),'stopProofRef':op.get('stopProofRef'),'state':b.get('state'),'taskStatus':task['outcome']['status'],'taskStopReason':task['outcome'].get('executionControl',{}).get('stopReason'),'unresolved':bool(op.get('startIntent') and not op.get('stopProofRef'))})
save('journal/reconciliation-matrix.json',matrix)
save('journal/python-rows.json',json.loads(command('docker','exec','cline-p32-db','psql','-U','stage2','-d','stage2','-At','-c',"select coalesce(json_agg(row_to_json(x)),'[]') from (select tenant_id,owner_id,run_id,task_id,operation_id,fencing_token,status,container_name,image_digest,code_hash,cancel_requested,executor_stopped,exit_code,manifest_hash from mcp_python_managed_operation order by created_at) x")))
for reg in registries:
 root=R/'control/state/workers'/reg['workerId'];inspect=json.loads(command('docker','inspect',reg['containerId']))[0] if reg.get('containerId') else None
 if inspect:assert not inspect['State']['Running'],'Stop all isolated task Workers before evidence collection'
 safe={k:v for k,v in reg.items() if k not in ('apiToken','brokerToken','monitorBusy')};save('workers/'+reg['workerId']+'/registry.json',safe)
 if inspect:save('workers/'+reg['workerId']+'/inspect.json',{k:inspect[k] for k in ('Id','Image','State','Mounts','HostConfig')})
 for p in (root/'state').glob('*/events.jsonl'):save('workers/'+reg['workerId']+'/native-events.jsonl',p.read_text(),True)
 workspace=root/'workspace';before={};after={}
 template=R/'templates'/pathlib.Path(gateway['tenants'][reg['contract']['tenantId']]['workspaces'][reg['contract']['workspaceRef']]).relative_to('/templates')
 from evidence_files import tree_bytes
 a,skipA=tree_bytes(template);b,skipB=tree_bytes(workspace)
 for path in sorted(set(a)|set(b)):
  before[path]=sha(a[path]) if path in a else None;after[path]=sha(b[path]) if path in b else None
 changed=[p for p in before if before[p]!=after[p]]
 save('workers/'+reg['workerId']+'/workspace-hashes.json',{'before':before,'after':after,'changed':changed,'skipped':skipB})
 patch=''
 for p in changed:
  patch+=''.join(difflib.unified_diff(a.get(p,b'').decode(errors='replace').splitlines(True),b.get(p,b'').decode(errors='replace').splitlines(True),fromfile='before/'+p,tofile='after/'+p))
 save('workers/'+reg['workerId']+'/workspace.diff',patch)
python_paths=[]
for p in (R/'python/workspace').rglob('*'):
 if p.is_file() and p.name in ('receipt.json','manifest.json','stdout.txt','stderr.txt','analysis.py','runtime-diagnostic.json'):
  relative=p.relative_to(R/'python/workspace');short='python/'+sha(str(relative.parent).encode())+'/'+p.name
  python_paths.append({'path':short,'originalPath':str(relative)});save(short,p.read_text())
save('python/path-map.json',python_paths)
old=pathlib.Path('/opt/cline-agent-server-poc-20261108/build/cline-a459f1d04dea030833b558be645011ab739c463b');new=R/'upstream'
core=[]
for root in ('sdk/packages/core/src','sdk/packages/agents/src'):
 for p in (old/root).rglob('*'):
  if p.is_file():
   rel=p.relative_to(old);q=new/rel;core.append({'path':str(rel),'beforeSha256':sha(p.read_bytes()),'afterSha256':sha(q.read_bytes()) if q.exists() else None})
save('source/core-source-hashes.json',{'unchanged':all(x['beforeSha256']==x['afterSha256'] for x in core),'files':core})
changes=[]
for p in (old/'apps/vscode/src').rglob('*'):
 if p.is_file():
  rel=p.relative_to(old);q=new/rel
  if q.exists() and p.read_bytes()!=q.read_bytes():
   changes.append({'path':str(rel),'beforeSha256':sha(p.read_bytes()),'afterSha256':sha(q.read_bytes())});save('source/'+str(rel)+'.diff',''.join(difflib.unified_diff(p.read_text().splitlines(True),q.read_text().splitlines(True),fromfile='stage1/'+str(rel),tofile='stage2/'+str(rel))))
save('source/upstream-host-diff-matrix.json',changes)
for name in ('cline-p32-authority','cline-p32-remote','cline-p32-control','cline-p32-db','cline-p32-fault'):
 i=json.loads(command('docker','inspect',name))[0];save('docker/'+name+'.json',{k:i[k] for k in ('Id','Image','State','Mounts','HostConfig','NetworkSettings')});save('logs/'+name+'.log',command('docker','logs',name))
save('source/input-jars.json',[{'name':p.name,'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in (R/'input').glob('*.jar')])
save('source/adapter-files.json',[{'path':str(p.relative_to(R/'source')),'sha256':sha(p.read_bytes())} for p in (R/'source/cline-agent-server').rglob('*') if p.is_file()])
save('runtime-manifest.json',json.loads((R/'runtime/runtime-manifest.json').read_text()))
save('collection.json',{'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'baseline':'db60dec41','realModelProfile':gateway['modelProfiles'][next(iter(gateway['modelProfiles']))]['model'],'workers':len(registries),'journalOperations':len(matrix),'unresolvedJournalOperations':sum(x['unresolved'] for x in matrix),'remoteOperations':len(remotes),'coreFiles':len(core),'coreUnchanged':bool(core) and all(x['beforeSha256']==x['afterSha256'] for x in core),'changedHostFiles':len(changes),'defaultsChanged':False})
manifest=[{'path':str(p.relative_to(OUT)),'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in sorted(OUT.rglob('*')) if p.is_file() and p.name!='SHA256SUMS.json'];save('SHA256SUMS.json',manifest)
print(json.dumps({'evidenceFiles':len(manifest),'unresolvedJournalOperations':sum(x['unresolved'] for x in matrix),'coreFiles':len(core),'hostDiffFiles':len(changes)}))
