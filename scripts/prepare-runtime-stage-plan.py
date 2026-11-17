#!/usr/bin/env python3
"""Review an existing stage into a private launch plan and a secret-free recipe.

The private plan stays on the target data disk. No database initialization,
volume deletion, directory ownership change, or service start is performed.
"""
import argparse,copy,hashlib,json,pathlib,subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--images',type=pathlib.Path,required=True)
p.add_argument('--installer-image',required=True)
p.add_argument('--plan',type=pathlib.Path,required=True)
p.add_argument('--recipe',type=pathlib.Path,required=True)
p.add_argument('--version',required=True)
a=p.parse_args();assert not a.plan.exists() and not a.recipe.exists()
STAGE=pathlib.Path('/opt/runtime-os-docker-stage');NATIVE=pathlib.Path('/opt/runtime-os/native-temporal/rc-20261112')
def run(cmd):return subprocess.check_output([str(x) for x in cmd],text=True)
def inspect(ref):return json.loads(run(['docker','image','inspect',ref]))[0]
def resolved(path,env=None):
 cmd=['docker','compose']
 if env:cmd+=['--env-file',env]
 return json.loads(run(cmd+['-f',path,'config','--format','json']))
def alias(role,ref):
 identity=inspect(ref)['Id'];target='runtime-os-stage-'+role+':1.3'
 current=subprocess.run(['docker','image','inspect',target],capture_output=True,text=True)
 if not current.returncode:assert json.loads(current.stdout)[0]['Id']==identity,'Immutable stage image alias changed'
 else:subprocess.run(['docker','tag',identity,target],check=True)
 return {'reference':target,'imageId':identity}
images=json.loads(a.images.read_text())
images['temporal']=alias('temporal','temporalio/server:1.29.7')
images['gateway']=alias('gateway','nginx:1.28-alpine')
images['installer']={'reference':a.installer_image,'imageId':inspect(a.installer_image)['Id']}
legacy=resolved(STAGE/'compose.yaml',STAGE/'config/deployment.env')
native=json.loads((NATIVE/'compose.private.json').read_text())
native['services']['studio-executor']=json.loads(pathlib.Path('/opt/runtime-os/cn2/compose.json').read_text())['services']['studio-executor']
frontend=resolved('/opt/frontend-resource-hub/1.0.0/compose.yaml')
gateway=json.loads((STAGE/'compose.gateway-ui.json').read_text())
compose={'services':{},'networks':{}}
roles={};rename={'temporal-control-api':'api','studio-gateway':'studio','frontend-preview-worker':'frontend-worker','frontend-resource-hub':'frontend-resource-hub','gateway':'gateway'}
def role_image(name):
 if name.startswith(('node','executor','recovery','studio')):return 'node'
 if name.startswith('worker'):return 'api'
 if name.startswith('frontend'):return 'frontend'
 return name
for group,definition in [('legacy',legacy),('native',native),('frontend',frontend),('gateway',gateway)]:
 for network,spec in definition.get('networks',{}).items():
  if group in ('legacy','gateway'):continue
  key=group+'-'+network
  name=spec.get('name') or (('frontend-resource-hub_' if group=='frontend' else 'runtime-os-app-stage_')+network)
  compose['networks'][key]={'name':name,'external':True}
 for old,original in definition.get('services',{}).items():
  name=rename.get(old,old);service=copy.deepcopy(original)
  assert name not in compose['services']
  service.pop('container_name',None);service.pop('build',None);service.pop('env_file',None)
  if isinstance(service.get('environment'),list):service['environment']=dict(item.split('=',1) for item in service['environment'])
  if role_image(name)=='node':
   for key in ['PATH','JAVA_HOME','JAVA_VERSION','PYTHON_VERSION','PYTHON_SHA256','GPG_KEY']:service.get('environment',{}).pop(key,None)
  role=role_image(name);roles[name]=role;service['image']=images[role]['imageId']
  config=inspect(service['image'])['Config']
  ep=service.get('entrypoint',config.get('Entrypoint') or [])
  cmd=service.get('command',[] if service.get('entrypoint') is not None else config.get('Cmd') or [])
  if ep is None:ep=config.get('Entrypoint') or []
  if cmd is None:cmd=[] if original.get('entrypoint') is not None else config.get('Cmd') or []
  assert isinstance(ep,list) and isinstance(cmd,list),'Startup command must be explicit argv arrays'
  service['entrypoint']=ep;service['command']=cmd
  if service.get('network_mode','').startswith('service:'):
   target=service['network_mode'].split(':',1)[1];service['network_mode']='service:'+rename.get(target,target)
  elif service.get('network_mode')!='host':
   old_networks=service.get('networks',{'default':{}})
   if isinstance(old_networks,list):old_networks={n:{} for n in old_networks}
   service['networks']={('native-control' if group in ('legacy','gateway') else group+'-'+n):v or {} for n,v in old_networks.items()}
  if service.get('depends_on'):service['depends_on']={rename.get(n,n):v for n,v in service['depends_on'].items()}
  for option in service.get('security_opt',[]):
   if option.startswith('seccomp=') and option[8:].startswith('/'):
    profile=pathlib.Path(option[8:]);service['security_opt'][service['security_opt'].index(option)]='seccomp='+json.dumps(json.loads(profile.read_text()),separators=(',',':'))
  for mount in service.get('volumes',[]):
   source=mount.get('source') if isinstance(mount,dict) else mount.split(':')[0]
   if not source.startswith('/'):raise RuntimeError('Only explicit existing bind paths are allowed in this stage: '+name)
   assert pathlib.Path(source).exists(),'Missing existing mount for '+name+': '+source
  if name in ('api','mcp','news'):
   port={'api':8080,'mcp':8090,'news':8091}[name]
   path={'api':'/api/v1/enterprise/auth/me','mcp':'/api/v1/admin/auth/me','news':'/actuator/health'}[name]
   code=200 if name=='news' else 401
   script='exec 3<>/dev/tcp/127.0.0.1/'+str(port)+'; printf "GET '+path+' HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n" >&3; head -n1 <&3 | grep -q " '+str(code)+'"'
   service['healthcheck']={'test':['CMD','bash','-ec',script],'interval':'5s','timeout':'3s','retries':48}
  if name=='gateway':service['healthcheck']={'test':['CMD-SHELL',"wget -S -O /dev/null http://127.0.0.1:8080/api/v1/enterprise/auth/me 2>&1 | grep -q '401'"],'interval':'5s','timeout':'3s','retries':48}
  compose['services'][name]=service
stages=[['news'],['mcp'],['api'],['temporal'],['executor1','executor2','studio-executor'],['recovery1','recovery2'],['node1','node2'],['frontend-resource-hub','frontend-worker'],['worker1','worker2'],['studio'],['gateway']]
assert set(sum(stages,[]))==set(compose['services'])
generated={};sockets={};daemon=None
def update_worker(value):
 if isinstance(value,dict):return {k:(images['node']['imageId'] if k=='workerImage' else update_worker(v)) for k,v in value.items()}
 if isinstance(value,list):return [update_worker(v) for v in value]
 return value
for name,service in compose['services'].items():
 for key,value in service.get('environment',{}).items():
  if key.startswith('CLINE_') and 'CONFIG' in key and isinstance(value,str) and value.startswith('/') and pathlib.Path(value).is_file():
   original=json.loads(pathlib.Path(value).read_text());updated=update_worker(original);filename=name+'-profile.json';generated[filename]=updated
   service.setdefault('volumes',[]).append({'type':'bind','source':'${INSTALL_ROOT}/profiles/'+filename,'target':value,'read_only':True})
   if name in ['executor1','executor2','studio-executor']:
    slot={'executor1':'1','executor2':'2','studio-executor':'s'}[name];sockets[slot]=original['socketPath']
    candidate={'socket':original['dockerSocket'],'expectedId':original['expectedDaemonId']}
    if daemon is None:daemon=candidate
    assert daemon==candidate,'Executor profiles do not share the reviewed dedicated daemon'
plan={'format':'runtime-os-stage-plan/v1','stageRoot':str(STAGE),'compose':compose,'stages':stages,'executionImage':images['node']['imageId'],'administratorUrls':['http://127.0.0.1:18180/api/v1/enterprise/auth','http://127.0.0.1:18190/api/v1/admin/auth'],'executorSockets':sockets,'executorDaemon':daemon,'generatedProfiles':generated}
a.plan.parent.mkdir(parents=True,exist_ok=True);a.plan.write_text(json.dumps(plan,indent=2)+'\n');a.plan.chmod(0o600)
a.recipe.mkdir(parents=True);assets=a.recipe/'assets';assets.mkdir()
public_map={r:v for r,v in images.items() if r!='installer'}
(assets/'runtime-images.json').write_text(json.dumps(public_map,indent=2)+'\n')
components=[]
previous=[]
for group in stages:
 for name in group:
  s=compose['services'][name];dep=list(previous)
  if s.get('network_mode','').startswith('service:'):dep.append(s['network_mode'].split(':',1)[1])
  components.append({'id':name,'image':roles[name],'dependsOn':sorted(set(dep)),'entrypoint':[x.replace('${','$${') for x in s['entrypoint']],'command':[x.replace('${','$${') for x in s['command']]})
 previous=group
manifest={'schemaVersion':1,'name':'runtime-os','version':a.version,'platform':'linux/amd64','description':'Explicit existing-stage data/configuration reuse. No database initialization, password reset, data copying, volume removal or directory ownership change. Private plan is hash-pinned outside this archive.','requirements':{'memoryBytes':3*1024**3,'diskBytes':1024**3},'upgradeFrom':[],'dataCompatibility':'initial-only','parameters':{'serverAddress':{'type':'string','label':'Server address','default':'192.168.195.221'},'httpPort':{'type':'integer','label':'Existing HTTP port','default':18100,'min':18100,'max':18100},'mcpPort':{'type':'integer','label':'Existing MCP port','default':18101,'min':18101,'max':18101},'directoryName':{'type':'string','label':'Management journal directory','default':'stage-v13','pattern':'[a-z0-9-]{1,16}'},'osAdminPassword':{'type':'string','label':'Existing Runtime administrator password (not reset)','secret':True,'required':True,'pattern':'.{1,128}'}},'images':{r:{'source':e['reference']} for r,e in images.items()},'volumes':{},'components':components,'runtimeInstaller':{'protocol':'runtime-os-native/v1','contractVersion':1,'image':'installer','imageMap':'assets/runtime-images.json','services':list(compose['services']),'schemaHashes':{'existing-stage-no-schema-initialization':hashlib.sha256(b'validate-only-no-schema-initialization-v1').hexdigest()},'stagePlan':{'name':a.plan.name,'sha256':hashlib.sha256(a.plan.read_bytes()).hexdigest()}},'links':[{'name':'Runtime OS','url':'http://${serverAddress}:${httpPort}/'},{'name':'MCP','url':'http://${serverAddress}:${mcpPort}/'}]}
(a.recipe/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
mounts=[]
for name,service in compose['services'].items():
 for mount in service.get('volumes',[]):
  if isinstance(mount,dict):source,target,read_only=mount['source'],mount['target'],mount.get('read_only',False)
  else:
   pieces=mount.split(':');source,target=pieces[:2];read_only=len(pieces)>2 and pieces[2]=='ro'
  mounts.append({'component':name,'source':source,'target':target,'mode':'read-only' if read_only else 'read-write'})
manifest['runtimeInstaller']['stagePlan'].update(sourceRoot=str(STAGE),mounts=mounts)
(a.recipe/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({'reviewed':True,'privatePlan':str(a.plan),'planSha256':manifest['runtimeInstaller']['stagePlan']['sha256'],'publicRecipe':str(a.recipe),'managedServices':len(components),'existingCustomerDataIncludedInPublicRecipe':False}))
