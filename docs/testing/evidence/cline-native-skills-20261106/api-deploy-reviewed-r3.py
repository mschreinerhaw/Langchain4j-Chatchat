"""Apply the reviewed native Skills class families using the existing Docker API replacement workflow."""
import pathlib,json,subprocess,importlib.util,copy,urllib.parse,urllib.request,time,hashlib,re,os
os.umask(0o077)
r=pathlib.Path('/opt/chatchat-releases/cline-native-skills-20261106-r3');stage=pathlib.Path('/opt/runtime-os-docker-stage');r2=r.with_name('cline-precode-20261106-r2');name='runtime-os-app-stage-api-1'
def load(p):
 s=importlib.util.spec_from_file_location(p.stem,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
docker=load(r2/'deploy-coding-repair-context.py').docker
def run(*args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT).strip()
inspect=lambda n:json.loads(run('docker','inspect',n))[0]
m=json.loads((r/'api-artifact-proof.json').read_text());old=inspect(name);assert old['State']['Running'];assert run('docker','exec',name,'sha256sum','/srv/app/app.jar').split()[0]==m['baselineJarSha256']
assert run('docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',"select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")=='0'
assert not run('docker','ps','--filter','name=chatchat-py-','--format','{{.ID}}')
probe="import fs from 'node:fs';const secret=fs.readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim();const r=await fetch('http://127.0.0.1:3100/readyz',{headers:{Authorization:'Bearer '+secret}});const j=await r.json();if(!r.ok||j.activeSessions!==0)throw Error('Active Coding sessions');console.log('IDLE');"
assert run('docker','exec','runtime-os-app-stage-coding-agent-1','node','--input-type=module','-e',probe)=='IDLE'
for mount in old['Mounts']:
 if mount['Type']=='bind':assert pathlib.Path(mount['Source']).exists(),'Required mount absent'
image=m['image'];assert run('docker','run','--rm','--network','none','--entrypoint','sha256sum',image,'/srv/app/app.jar').split()[0]==m['candidateJarSha256']
backup=r/'api-proxy-deployment-backup';backup.mkdir(mode=0o700);(backup/'container-private.json').write_text(json.dumps(old));paths=[stage/'compose.yaml',stage/'config/deployment.env'];files={p:p.read_bytes() for p in paths}
for p,content in files.items():(backup/p.name).write_bytes(content)
fleet={inspect(n)['Name']:inspect(n)['Id'] for n in run('docker','ps','-q').split()};config=copy.deepcopy(old['Config']);config['Image']=image;host=copy.deepcopy(old['HostConfig']);endpoints={n:{'Aliases':d.get('Aliases',[]),'IPAMConfig':d.get('IPAMConfig')} for n,d in old['NetworkSettings']['Networks'].items()};retired=name+'-before-native-skills-'+old['Id'][:12];renamed=False;created=False;disconnected=[]
proxy='runtime-os-app-stage-node-proxy-1';proxy_old=inspect(proxy);assert proxy_old['HostConfig']['NetworkMode']=='container:'+old['Id'];(backup/'proxy-container-private.json').write_text(json.dumps(proxy_old));proxy_retired=proxy+'-before-native-skills-'+proxy_old['Id'][:12];proxy_renamed=False;proxy_created=False
auth=json.loads(pathlib.Path('/opt/chatchat-releases/cline-finance-validation-20261105/auth-private.json').read_text())
def routes():
 run('docker','exec','runtime-os-app-stage-node-proxy-1','nginx','-t');run('docker','exec','runtime-os-app-stage-node-proxy-1','nginx','-s','reload');run('bash',str(stage/'restrict-coding-worker.sh'));run('docker','exec','runtime-os-app-stage-gateway-1','nginx','-s','reload')
def healthy(expected):
 for _ in range(90):
  try:
   req=urllib.request.Request('http://127.0.0.1:18180/api/v1/health',headers={'Authorization':'Bearer '+auth['token']})
   with urllib.request.urlopen(req,timeout=3) as response:assert response.status==200
   assert run('docker','exec',name,'sha256sum','/srv/app/app.jar').split()[0]==expected;return
  except urllib.error.HTTPError as error:
   if error.code==401:
    credentials=(r/'login-private.json').read_bytes();req=urllib.request.Request('http://127.0.0.1:18180/api/v1/enterprise/auth/login',data=credentials,headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=10) as response:new_auth=json.load(response)['data']
    assert new_auth.get('token') and new_auth.get('user');auth.clear();auth.update(new_auth);pathlib.Path('/opt/chatchat-releases/cline-finance-validation-20261105/auth-private.json').write_text(json.dumps(new_auth))
   time.sleep(2)
  except Exception:time.sleep(2)
 raise RuntimeError('Authenticated health check timed out')
proof={'status':'FAILED','sourceCommit':m['sourceCommit'],'image':image,'baselineJarSha256':m['baselineJarSha256'],'jarSha256':m['candidateJarSha256'],'backupContainer':retired,'backupDirectory':str(backup)}
try:
 run('docker','stop','--time','30',name);run('docker','rename',name,retired);renamed=True
 for n in endpoints:run('docker','network','disconnect',n,retired);disconnected.append(n)
 docker('POST','/containers/create?name='+urllib.parse.quote(name),{**config,'HostConfig':host,'NetworkingConfig':{'EndpointsConfig':endpoints}});created=True;run('docker','start',name);healthy(m['candidateJarSha256']);current=inspect(name)
 assert current['HostConfig']==old['HostConfig'],'HostConfig changed';assert sorted(current['Mounts'],key=lambda x:x['Destination'])==sorted(old['Mounts'],key=lambda x:x['Destination']),'Mounts changed'
 for k in ['User','Env','Entrypoint','Cmd','WorkingDir','Healthcheck']:assert current['Config'].get(k)==old['Config'].get(k),'Config changed: '+k
 run('docker','stop','--time','10',proxy);run('docker','rename',proxy,proxy_retired);proxy_renamed=True
 proxy_host=copy.deepcopy(proxy_old['HostConfig']);proxy_host['NetworkMode']='container:'+current['Id']
 proxy_config=copy.deepcopy(proxy_old['Config'])
 for key in ['Hostname','Domainname','ExposedPorts']:proxy_config.pop(key,None)
 docker('POST','/containers/create?name='+urllib.parse.quote(proxy),{**proxy_config,'HostConfig':proxy_host});proxy_created=True;run('docker','start',proxy);proxy_current=inspect(proxy)
 assert proxy_current['HostConfig']==proxy_host,'Proxy security settings changed';assert sorted(proxy_current['Mounts'],key=lambda x:x['Destination'])==sorted(proxy_old['Mounts'],key=lambda x:x['Destination']),'Proxy mounts changed'
 for key in set(proxy_current['Config'])|set(proxy_old['Config']):
  if key!='Hostname':assert proxy_current['Config'].get(key)==proxy_old['Config'].get(key),'Proxy configuration changed: '+key
 assert proxy_current['Config']['Hostname']==current['Config']['Hostname'],'Proxy must inherit API namespace hostname'
 routes();proof['callback']=load(r2/'verify-coding-callback.py').verify('runtime-os-app-stage-coding-agent-1')
 c=json.loads(files[paths[0]]);c['services']['api']['image']=image;paths[0].write_text(json.dumps(c,indent=2));env=files[paths[1]].decode();paths[1].write_text(re.sub(r'^API_IMAGE=.*$', 'API_IMAGE='+image,env,flags=re.M))
 for n,id in fleet.items():
  if n not in ['/'+name,'/'+proxy]:assert inspect(n)['Id']==id,'Unrelated container changed'
 req=urllib.request.Request('http://127.0.0.1:18100/api/v1/health',headers={'Authorization':'Bearer '+auth['token']})
 with urllib.request.urlopen(req,timeout=15) as response:assert response.status==200
 proof.update(status='DEPLOYED',imageId=current['Image'],authenticatedHealthVerified=True,gatewayHealthVerified=True,hostConfigAndMountsUnchanged=True,otherContainerIdsUnchanged=True,changedContainers=[name,proxy],proxyBackupContainer=proxy_retired,proxyHostConfigOnlyChange='NetworkMode binds new API container namespace; same whitelist/config/image/mounts',proxyConfigurationAndMountsUnchanged=True)
except Exception:
 for p,content in files.items():p.write_bytes(content)
 if proxy_created:run('docker','rm','-f',proxy)
 if proxy_renamed:run('docker','rename',proxy_retired,proxy)
 if created:run('docker','rm','-f',name)
 if renamed:run('docker','rename',retired,name)
 for n in disconnected:docker('POST','/networks/'+urllib.parse.quote(n)+'/connect',{'Container':name,'EndpointConfig':endpoints[n]})
 run('docker','start',name);healthy(m['baselineJarSha256']);run('docker','restart',proxy);routes();proof['rollbackCallback']=load(r2/'verify-coding-callback.py').verify('runtime-os-app-stage-coding-agent-1');proof['status']='ROLLED_BACK';raise
finally:(r/'api-deployment.json').write_text(json.dumps(proof,indent=2))
print(json.dumps(proof),flush=True)
