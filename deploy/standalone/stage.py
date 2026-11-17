#!/usr/bin/env python3
"""Execute a hash-reviewed private legacy plan without initializing customer data.

Runs only inside the trusted installer image. Configuration/volume paths remain
in the private registered plan; the public package contains only its SHA-256.
"""
import copy, hashlib, http.cookiejar, json, os, pathlib, re, subprocess, sys, time, urllib.request

ROOT=pathlib.Path('/install');PLAN=pathlib.Path('/stage-plan.json')
def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
def write(name,value):
 p=ROOT/name;temp=p.with_suffix(p.suffix+'.new');temp.write_text(json.dumps(value,indent=2)+'\n');temp.chmod(0o600);temp.replace(p)
def execute(args):subprocess.run(args,check=True)
def compose_literal(value):
 if isinstance(value,str):return value.replace('$','$$')
 if isinstance(value,list):return [compose_literal(x) for x in value]
 if isinstance(value,dict):return {k:compose_literal(v) for k,v in value.items()}
 return value
def installation_paths(value,host):
 if isinstance(value,str):return value.replace('${INSTALL_ROOT}',host)
 if isinstance(value,list):return [installation_paths(x,host) for x in value]
 if isinstance(value,dict):return {k:installation_paths(v,host) for k,v in value.items()}
 return value
def own(container,project,owner):
 labels=container['Config'].get('Labels') or {}
 return labels.get('com.docker.compose.project')==project and labels.get('deployment-ops.owner')==owner
def main():
 assert hashlib.sha256(PLAN.read_bytes()).hexdigest()==os.environ['STAGE_PLAN_SHA256'],'Private stage plan changed after confirmation'
 plan=json.loads(PLAN.read_text());assert plan['format']=='runtime-os-stage-plan/v1'
 project=os.environ['PROJECT'];assert re.fullmatch(r'ops-env-[a-z0-9-]{1,32}',project)
 self=inspect(os.environ['HOSTNAME']);owner=self['Config']['Labels']['deployment-ops.owner']
 host=next(m['Source'] for m in self['Mounts'] if m['Destination']=='/install')
 compose=installation_paths(copy.deepcopy(plan['compose']),host);compose.pop('name',None)
 for name,profile in plan.get('generatedProfiles',{}).items():
  assert re.fullmatch(r'[a-z0-9-]+\.json',name);(ROOT/'profiles').mkdir(exist_ok=True);write('profiles/'+name,profile)
 daemon=plan['executorDaemon'];docker=['docker','--host','unix://'+daemon['socket']]
 actual=json.loads(subprocess.check_output(docker+['info','--format','{{json .}}']))
 assert actual['ID']==daemon['expectedId'],'Dedicated executor daemon identity changed; reconcile it before deployment'
 if subprocess.run(docker+['image','inspect',plan['executionImage']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
  save=subprocess.Popen(['docker','save',plan['executionImage']],stdout=subprocess.PIPE)
  try:subprocess.run(docker+['load'],stdin=save.stdout,check=True)
  finally:save.stdout.close()
  assert save.wait()==0,'Execution image transfer failed'
 for role,service in compose['services'].items():
  assert re.fullmatch(r'[a-z][a-z0-9-]{0,39}',role)
  name=project+'-'+role
  prior=subprocess.run(['docker','inspect',name],capture_output=True,text=True)
  if not prior.returncode:assert own(json.loads(prior.stdout)[0],project,owner),'Existing container is outside this reviewed environment'
  service['container_name']=name;service['pull_policy']='never'
  labels=service.setdefault('labels',{});labels.update({'deployment-ops.owner':owner,'deployment-ops.environment':project.removeprefix('ops-env-'),'deployment-ops.component':role,'deployment-ops.application':'runtime-os'})
  assert inspect(service['image'])['Id']==service['image'],'Image is missing or changed'
  for mount in service.get('volumes',[]):
   if isinstance(mount,dict) and mount.get('type')=='bind':mount['bind']={'create_host_path':False}
 path=ROOT/'compose.json';previous=path.read_bytes() if path.exists() else None
 write('compose.json',compose_literal(compose))
 cp=['docker','compose','-p',project,'-f',str(path)]
 action=sys.argv[1] if len(sys.argv)>1 else 'install'
 if action=='stop':execute(cp+['stop']);return
 assert action in ('install','start')
 try:
  for group in plan['stages']:
   execute(cp+['up','-d','--no-deps','--no-build',*group])
   for role in group:
    deadline=time.monotonic()+240
    while True:
     state=inspect(project+'-'+role)['State'];healthy=state.get('Health',{}).get('Status')
     if state.get('Running') and healthy in (None,'healthy'):break
     if time.monotonic()>deadline:raise RuntimeError('Readiness failed for '+role+'; inspect its retained container logs')
     time.sleep(2)
  # Credentials are existing customer input, not a bootstrap/reset instruction.
  opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
  deadline=time.monotonic()+240
  while True:
   try:
    for base in plan['administratorUrls']:
     request=urllib.request.Request(base+'/login',data=json.dumps({'username':'admin','password':os.environ['ADMIN_PASSWORD']}).encode(),headers={'Content-Type':'application/json'})
     with opener.open(request,timeout=20) as response:token=json.load(response).get('data',{}).get('token')
     assert token,'Administrator authentication failed; existing passwords are not reset'
     with opener.open(urllib.request.Request(base+'/me',headers={'Authorization':'Bearer '+token}),timeout=20) as response:assert json.load(response)['data']['username']=='admin'
    break
   except Exception as error:
    if getattr(error,'code',0) in (400,401,403):raise RuntimeError('Existing administrator credentials were rejected; no password was reset') from None
    if time.monotonic()>deadline:raise RuntimeError('Existing administrator service readiness timed out') from None
    time.sleep(3)
  for slot,role in [('1','executor1'),('2','executor2'),('s','studio-executor')]:
   deadline=time.monotonic()+90
   script="require('http').get({socketPath:process.argv[1],path:'/health/live'},r=>{let b='';r.on('data',d=>b+=d);r.on('end',()=>{if(r.statusCode!==200||!JSON.parse(b).resourceEnforced)process.exit(1);console.log('Executor resource policy admitted')})}).on('error',()=>process.exit(1))"
   while subprocess.run(['docker','exec',project+'-'+role,'node','-e',script,plan['executorSockets'][slot]],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
    if time.monotonic()>deadline:raise RuntimeError('Executor resource policy not admitted: '+role)
    time.sleep(2)
  # Exercise the actual dedicated execution engine, with enforced isolation.
  execute(docker+['run','--rm','--pull=never','--network=none','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--memory=768m','--cpus=1','--pids-limit=128','--tmpfs=/tmp:rw,nosuid,nodev,size=128m','-e','OPENBLAS_NUM_THREADS=1','-e','OMP_NUM_THREADS=1','--entrypoint=bash',plan['executionImage'],'-ec','node /opt/runtime-os/coding-node/service/scripts/verify-runtime.mjs /opt/runtime-os/cline/runtime && node /opt/runtime-os/coding-node/service/docker/verify-native-modules.mjs /opt/runtime-os/cline/runtime && python /opt/runtime-os/coding-node/service/docker/verify-python-runtime.py'])
  write('installation.json',{'schemaVersion':1,'project':project,'status':'READY','stageRoot':plan['stageRoot'],'planSha256':hashlib.sha256(PLAN.read_bytes()).hexdigest(),'httpPort':int(os.environ['HTTP_PORT']),'mcpPort':int(os.environ['MCP_PORT'])})
  write('verification.json',{'status':'PASSED','nativeCoreVerified':True,'pythonRuntimeVerified':True,'privateExecutorDaemon':True,'offlineImages':True,'productionDataImported':False,'existingStageDataReused':True,'administratorLogins':['api','mcp'],'executorSockets':plan['executorSockets']})
  print('Existing-stage Runtime ready; administrator identities verified, existing databases/configuration/mounts retained.',flush=True)
 except Exception:
  if previous is not None:
   path.write_bytes(previous);path.chmod(0o600)
   execute(cp+['up','-d','--no-build'])
   print('Previous scoped Compose configuration restored; customer data retained.',flush=True)
  else:
   execute(cp+['stop'])
   print('Partial new environment stopped; containers/logs/data retained for explicit recovery.',flush=True)
  raise
if __name__=='__main__':main()
