#!/usr/bin/env python3
"""Read-only, credential-redacted export of this private review prototype."""
import pathlib,subprocess,json,hashlib,gzip,sys,os,datetime
R=pathlib.Path('/opt/cline-server-p33-stage1-20261108');O=pathlib.Path(sys.argv[1]);O.mkdir(parents=True,exist_ok=True)
ec=json.loads((R/'config/executor.json').read_text());gc=json.loads((R/'config/gateway.json').read_text());D=['docker','-H','unix://'+str(R/'run/docker.sock')]
secrets=[ec['apiToken'],ec['ownershipKey'],ec['signingKey']]+[x['apiKey'] for x in gc['modelProfiles'].values()]
for p in (R/'worker-mounts').glob('*/config.json'):
 c=json.loads(p.read_text());secrets.extend([c['apiToken'],c['apiKey']])
def sha(b):return hashlib.sha256(b).hexdigest()
def save(path,value):
 text=value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2)+'\n'
 for secret in secrets:
  if secret:text=text.replace(secret,'[REDACTED]')
 data=text.encode();p=O/path;p.parent.mkdir(parents=True,exist_ok=True)
 if len(data)>180000:p=p.with_name(p.name+'.gz');data=gzip.compress(data,mtime=0)
 p.write_bytes(data)
def out(*args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT)
for p in (R/'evidence').rglob('*'):
 if p.is_file():save('runs/'+str(p.relative_to(R/'evidence')),p.read_text())
save('executor-audit.jsonl',(R/'executor-control/executor-audit.jsonl').read_text())
for name in ['cline-p331-gateway','cline-p331-executor']:
 i=json.loads(out(*D,'inspect',name))[0];save('docker/'+name+'.json',i);save('logs/'+name+'.log',out(*D,'logs',name))
names=out(*D,'ps','-aq','--filter','label=cline.executor='+ec['instanceId']).splitlines();states=[]
for cid in names:
 i=json.loads(out(*D,'inspect',cid))[0];assert not i['State']['Running'],'Stop all prototype Workers before collection';states.append({'id':cid,'labels':i['Config']['Labels'],'state':i['State']});save('docker/workers/'+cid+'.json',i)
save('worker-states.json',states)
from evidence_files import tree_bytes
for path in (R/'control-state/workers').glob('*/registry.json'):
 r=json.loads(path.read_text());r.pop('apiToken',None);r.pop('brokerToken',None);wid=r['workerId'];save('workers/'+wid+'/registry.json',r)
 root=R/'worker-mounts'/wid
 for p in (root/'state').glob('*/events.jsonl'):save('workers/'+wid+'/native-events.jsonl',p.read_text())
 tid=r['contract']['tenantId'];ref=r['contract']['workspaceRef'];template=R/'templates'/pathlib.Path(gc['tenants'][tid]['workspaces'][ref]).relative_to('/templates');before,skip=tree_bytes(template);after,skip2=tree_bytes(root/'workspace');save('workers/'+wid+'/project-hashes.json',{'before':{k:sha(v) for k,v in before.items()},'after':{k:sha(v) for k,v in after.items()},'changed':[k for k in set(before)|set(after) if before.get(k)!=after.get(k)],'skipped':skip2})
unknown=[]
known={i['labels']['cline.workerId'] for i in states}
for p in (R/'worker-mounts').iterdir():
 if p.is_dir() and p.name not in known:unknown.append({'workerId':p.name,'configPresent':(p/'config.json').exists(),'closed':(p/'closed').exists(),'note':'Prepared/closed directory is not itself a tool no-start receipt; no automatic reuse'})
save('retained-directories.json',unknown)
canary=R/'host-root-canary';st=canary.stat();save('host-root-canary.json',{'uid':st.st_uid,'mode':oct(st.st_mode&0o777),'sha256':sha(canary.read_bytes())})
code="const f=require('fs'),c=require('crypto');console.log(JSON.stringify(f.readdirSync('/poc/service/src').filter(n=>n.endsWith('.mjs')).map(n=>({path:'cline-agent-server/src/'+n,sha256:c.createHash('sha256').update(f.readFileSync('/poc/service/src/'+n)).digest('hex')}))))"
save('source/deployed-src.json',json.loads(out(*D,'exec','cline-p331-executor','node','-e',code)))
runtime=out(*D,'run','--rm','--network','none','--user','1000:1000','--entrypoint','node',ec['workerImage'],'/poc/service/scripts/verify-runtime.mjs','/poc/runtime');save('source/official-runtime-verification.json',json.loads(runtime))
save('collection.json',{'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'baseline':'32e08b765','scope':'P3.3.1 review only','rootless':True,'resourceEnforced':False,'productionReady':False,'pythonRuntimeMigrated':False,'workerImage':ec['workerImage'],'daemonId':ec['expectedDaemonId'],'retainedWorkerContainers':len(states),'retainedUnmatchedDirectories':len(unknown),'noNextStageImplemented':True})
manifest=[{'path':str(p.relative_to(O)),'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in sorted(O.rglob('*')) if p.is_file() and p.name!='SHA256SUMS.json'];save('SHA256SUMS.json',manifest);print(json.dumps({'files':len(manifest),'productionReady':False}))
