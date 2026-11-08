#!/usr/bin/env python3
"""Rootless review prototype only. Keeps all previous services/configurations intact."""
import pathlib,subprocess,json,os,shutil,secrets
R=pathlib.Path('/opt/cline-server-p33-stage1-20261108');S=R/'source/cline-agent-server'
account=json.loads((R/'evidence/rootless-account.json').read_text());di=json.loads((R/'evidence/rootless-info.json').read_text())
assert 'name=rootless' in di['SecurityOptions'];D=['docker','-H','unix://'+account['socket']]
def run(*a,**kw):
 p=subprocess.run(a,**kw)
 if p.returncode:raise RuntimeError('Prototype command failed: '+a[0]+' (arguments withheld)')
 return p
def output(*a):return subprocess.check_output(a,text=True).strip()
(R/'input').mkdir(exist_ok=True);image='cline-agent-server:p3-stage2';baseline=output('docker','image','inspect',image,'--format','{{.Id}}')
if baseline!='sha256:6ee943241411781d729fc5fcdd5608c1906424740228f55919b0009c524dfccb':raise RuntimeError('Expected 32e08b765 acceptance Worker image; mutable tag changed')
present=subprocess.run([*D,'image','inspect',baseline],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
if not present:
 archive=R/'input/worker-image.tar'
 if not archive.exists():run('docker','image','save','-o',str(archive),image)
 with (R/'evidence/image-load.log').open('w') as f:run(*D,'image','load','-i',str(archive),stdout=f,stderr=subprocess.STDOUT)
build=R/'image';build.mkdir(exist_ok=True);shutil.copytree(S,build/'service',dirs_exist_ok=True)
(build/'Dockerfile').write_text('FROM cline-agent-server:p3-stage2\nCOPY service/ /poc/service/\n')
with (R/'evidence/image-build.log').open('w') as f:run(*D,'build','--network','none','-t','cline-agent-server:p331-review',str(build),stdout=f,stderr=subprocess.STDOUT)
runtime=output(*D,'image','inspect','cline-agent-server:p331-review','--format','{{.Id}}')
old=json.loads(pathlib.Path('/opt/cline-server-p3-stage2-20261108/control/config.json').read_text())
template=R/'templates'
if not list(template.iterdir()):shutil.copytree('/opt/cline-server-p3-stage2-20261108/templates',template,dirs_exist_ok=True)
for p in [template,*template.rglob('*')]:
 if p.is_symlink():raise RuntimeError('Symlink in trusted template')
 # Templates are readable only, ownership is deliberately not the Gateway UID.
 os.chmod(p,0o755 if p.is_dir() else 0o644)
private=R/'config';secretfile=private/'executor.json'
if secretfile.exists():ec=json.loads(secretfile.read_text())
else:ec={'apiToken':secrets.token_hex(32),'ownershipKey':secrets.token_hex(32),'instanceId':secrets.token_hex(16)}
ec.update(signingKey=old['signingKey'],expectedDaemonId=di['ID'],dockerSocket='/run/rootless-docker.sock',socketPath='/executor-bridge/executor.sock',controlRoot='/executor-control',workersRoot='/workers',hostWorkersRoot=str(R/'worker-mounts'),workerImage=baseline,maxWorkers=2,maxPerTenant=1,allowUnenforcedPrototype=True,operationContext=False,resources={'memoryBytes':2147483648,'nanoCpus':2000000000,'pidsLimit':256,'cpuSet':'8,9'},modelNames={k:v['model'] for k,v in old['modelProfiles'].items()})
ec['tenants']={k:{**v,'tools':['read_files','search_codebase','editor','apply_patch','run_commands'],'mcp':False} for k,v in old['tenants'].items()}
secretfile.write_text(json.dumps(ec));os.chown(secretfile,account['daemonUid'],account['daemonGid']);secretfile.chmod(0o400)
gc={**old,'host':'0.0.0.0','port':18331,'stateRoot':'/control/state','workerRoot':'/workers','workerImage':baseline,'operations':None,'tenants':ec['tenants'],'executor':{'socketPath':'/executor-bridge/executor.sock','token':ec['apiToken']}}
gc.pop('dockerSocket',None);gc.pop('hostStateRoot',None)
gp=private/'gateway.json';gp.write_text(json.dumps(gc));os.chown(gp,account['workerHostUid'],account['workerHostGid']);gp.chmod(0o400)
os.chown(R/'control-state',account['workerHostUid'],account['workerHostGid']);os.chmod(R/'control-state',0o700)
os.chown(R/'executor-bridge',account['daemonUid'],account['workerHostGid']);os.chmod(R/'executor-bridge',0o750)
names=output(*D,'ps','-a','--format','{{.Names}}').splitlines()
if 'cline-p331-executor' not in names:
 run(*D,'run','-d','--name','cline-p331-executor','--label','cline.stage=P3.3.1','--user','0:0','--network','none','--read-only','--cap-drop','ALL','--cap-add','CHOWN','--security-opt','no-new-privileges','--no-healthcheck','--mount',f'type=bind,src={secretfile},dst=/run/executor.json,readonly','--mount',f'type=bind,src={account["socket"]},dst=/run/rootless-docker.sock','--mount',f'type=bind,src={R}/executor-bridge,dst=/executor-bridge','--mount',f'type=bind,src={R}/executor-control,dst=/executor-control','--mount',f'type=bind,src={R}/worker-mounts,dst=/workers','--mount',f'type=bind,src={template},dst=/templates,readonly','-e','CLINE_EXECUTOR_CONFIG=/run/executor.json','--entrypoint','node',runtime,'/poc/service/src/worker-executor.mjs')
if 'cline-p331-gateway' not in names:
 run(*D,'run','-d','--name','cline-p331-gateway','--label','cline.stage=P3.3.1','--user','1000:1000','--network','host','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--no-healthcheck','--mount',f'type=bind,src={gp},dst=/run/gateway.json,readonly','--mount',f'type=bind,src={R}/control-state,dst=/control/state','--mount',f'type=bind,src={R}/worker-mounts,dst=/workers','--mount',f'type=bind,src={R}/executor-bridge,dst=/executor-bridge,readonly','-e','CLINE_GOVERNANCE_CONFIG=/run/gateway.json','--entrypoint','node',runtime,'/poc/service/src/governance-server.mjs')
(R/'evidence/deployment.json').write_text(json.dumps({'baseline':'32e08b765','daemonId':di['ID'],'rootless':True,'workerImage':baseline,'controlImage':runtime,'gatewayPort':18331,'resourceEnforcement':False,'productionReady':False,'pythonRuntimeMigrated':False},indent=2))
print('Rootless review Gateway and Restricted Executor deployed; cgroup enforcement explicitly unavailable.')
