"""Start an exact existing image in an isolated candidate Compose project.
No image rebuild, production replacement, daemon reconfiguration or volume deletion.
Private credentials are reused only from existing server configuration.
"""
import argparse,copy,json,os,secrets,subprocess,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--stage',type=Path,required=True);p.add_argument('--image',required=True);p.add_argument('--project',default='runtime-coding-candidate');p.add_argument('--port',type=int,default=18350);p.add_argument('--bind',default='127.0.0.1');p.add_argument('--direct-model',action='store_true');a=p.parse_args()
os.umask(0o077);r=a.root.resolve()
if len(str(r/'workers'/('0'*36)/'bridge/host.sock').encode())>103:
 raise ValueError('Candidate root is too long for Linux Unix-domain Worker sockets')
r.mkdir(parents=True,exist_ok=False)
for d in ['private','evidence','state','workers','template']:(r/d).mkdir()
image=json.loads(subprocess.check_output(['docker','image','inspect',a.image]))[0]['Id']
base=json.loads((a.stage/'config/native-node/executor.json').read_text());model=json.loads((a.stage/'config/native-node/model-audit.json').read_text())
base.update(workerImage=image,instanceId='candidate-'+secrets.token_hex(8),apiToken=secrets.token_hex(32),signingKey=secrets.token_hex(32),ownershipKey=secrets.token_hex(32),workersRoot=str(r/'workers'),hostWorkersRoot=str(r/'workers'),controlRoot=str(r/'executor-control'),socketPath=str(r/'executor.sock'))
for tenant in base['tenants'].values():tenant['workspaces']={'project':str(r/'template')}
# Blank git workspace; no prior scripts, hidden execution state, or analysis code.
subprocess.run(['git','init','--quiet',str(r/'template')],check=True)
subprocess.run(['chown','-R','10001:10001',str(r/'template')],check=True)
model['proxyToken']=secrets.token_hex(32)
gateway={'host':a.bind,'port':a.port,'stateRoot':str(r/'state'),'workerRoot':base['workersRoot'],'workerRuntime':'cline-python311','workerImage':image,'signingKey':base['signingKey'],'executor':{'socketPath':base['socketPath'],'token':base['apiToken']},'tenants':base['tenants'],'maxWorkers':2,'maxPerTenant':2,'s3':{'allowedOrigins':['http://127.0.0.1:9000'],'maxInputBytes':1073741824,'maxOutputBytes':1073741824,'autoApproveAuthorizedTools':True},'modelProfiles':{k:{'model':v,'baseUrl':'http://127.0.0.1:18569/{sessionId}/v1','apiKey':model['proxyToken']} for k,v in base['modelNames'].items()}}
for name,value in [('executor',base),('gateway',gateway),('model-audit',model)]:
 f=r/'private'/(name+'.json');f.write_text(json.dumps(value));f.chmod(0o600)
daemon=['docker','--host','unix://'+base['dockerSocket']]
if subprocess.run(daemon+['image','inspect',image],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
 save=subprocess.Popen(['docker','image','save',image],stdout=subprocess.PIPE);load=subprocess.run(daemon+['image','load'],stdin=save.stdout);save.stdout.close()
 if save.wait() or load.returncode:raise RuntimeError('Pinned Worker image import failed')
service='/opt/runtime-os/coding-node/service'
common={'image':image,'pull_policy':'never','user':'0:0','network_mode':'host','restart':'unless-stopped','volumes':[str(r)+':'+str(r)],'logging':{'driver':'json-file','options':{'max-size':'20m','max-file':'5'}}}
def node(name,file,key):return {**common,'container_name':name.replace('runtime-coding-candidate',a.project),'entrypoint':['node',service+'/src/'+file],'environment':{key:str(r/'private'/('executor.json' if file=='worker-executor.mjs' else 'gateway.json'))}}
ex=node('runtime-coding-candidate-executor','worker-executor.mjs','CLINE_EXECUTOR_CONFIG');ex.update(pid='host',cap_add=['SYS_PTRACE'],volumes=common['volumes']+[base['dedicatedDaemonRoot']+':'+base['dedicatedDaemonRoot']+':ro'])
gw=node('runtime-coding-candidate-node','governance-server.mjs','CLINE_GOVERNANCE_CONFIG');gw['depends_on']=['executor','model-audit']
audit={**common,'container_name':a.project+'-model-audit','entrypoint':['python',service+'/scripts/s3-model-audit-proxy.py','--settings',str(r/'private/model-audit.json'),'--root',str(r),'--audit-module',service+'/test/live_model_audit.py','--port','18569']}
compose={'services':{'executor':ex,'node':gw,'model-audit':audit}}
if a.direct_model:
 gateway['modelProfiles']={k:{'model':v,'baseUrl':model['baseUrl'],'apiKey':model['apiKey']} for k,v in base['modelNames'].items()}
 gateway['s3'].update(allowedOrigins=['http://127.0.0.1:9000','http://silo:9000'],scopeLayout='tenant-task-v1',bucket='runtime-assets',maxOutputBytes=64*1024*1024)
 (r/'private/gateway.json').write_text(json.dumps(gateway))
 compose['services'].pop('model-audit');gw['depends_on']=['executor'];gw['extra_hosts']=['silo:127.0.0.1']
(r/'compose.json').write_text(json.dumps(compose,indent=2))
subprocess.run(['docker','compose','-p',a.project,'-f',str(r/'compose.json'),'up','-d'],check=True)
for _ in range(60):
 try:
  with urllib.request.urlopen('http://'+a.bind+':'+str(a.port)+'/health/live',timeout=2) as res:health=json.load(res)
  break
 except OSError:time.sleep(1)
else:raise RuntimeError('Candidate gateway failed health check')
record={'imageId':image,'root':str(r),'project':a.project,'health':health,'productionChanged':False,'formalRelease':False}
(r/'evidence/deployment.json').write_text(json.dumps(record,indent=2));print(json.dumps(record))
