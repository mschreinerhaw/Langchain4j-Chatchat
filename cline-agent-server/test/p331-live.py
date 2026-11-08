#!/usr/bin/env python3
"""Actual private rootless daemon, capability API, kernel permissions and native Cline.
No Docker/Agent/receipt mock. No production daemon mutations or execution-node recovery work.
"""
import pathlib,json,subprocess,http.client,socket,uuid,time,hashlib,hmac,base64,urllib.request,urllib.error,datetime
R=pathlib.Path('/opt/cline-server-p33-stage1-20261108');E=R/'evidence';cfg=json.loads((R/'config/executor.json').read_text());gc=json.loads((R/'config/gateway.json').read_text());account=json.loads((E/'rootless-account.json').read_text());D=['docker','-H','unix://'+account['socket']];results=[]
def save(name,v):(E/name).write_text(v if isinstance(v,str) else json.dumps(v,indent=2))
def record(name,ok,detail,blocking=True):
 results.append({'case':name,'pass':bool(ok),'blocking':blocking,'detail':detail});save('live-results.json',results);print(name,'PASS' if ok else 'FAIL',flush=True)
def out(*a):return subprocess.check_output(a,text=True).strip()
def inspect(name):return json.loads(out(*D,'inspect',name))[0]
class Unix(http.client.HTTPConnection):
 def connect(self):self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.sock.settimeout(30);self.sock.connect(str(R/'executor-bridge/executor.sock'))
def sign(c):
 b=base64.urlsafe_b64encode(json.dumps(c,separators=(',',':')).encode()).rstrip(b'=');return (b+b'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),b,hashlib.sha256).digest()).rstrip(b'=')).decode()
def contract(tenant=None):
 tid=tenant or next(iter(gc['tenants']));t=gc['tenants'][tid];return {'version':1,'tenantId':tid,'userId':t['users'][0],'taskId':'p331-'+uuid.uuid4().hex,'sessionId':'session-'+uuid.uuid4().hex,'workspaceRef':next(iter(t['workspaces'])),'modelProfile':t['models'][0],'toolPermissions':t['tools'],'dataPermissions':t['datasets'],'networkPolicy':'broker-only','budgetPolicy':{'status':'DEFERRED_STAGE_2_REVIEW'},'expiresAt':int(time.time()*1000)+3600000}
def request(c,method,path,b=None,token=True):
 conn=Unix('executor');conn.request(method,path,body=json.dumps(b or {}),headers={'authorization':'Bearer '+(cfg['apiToken'] if token else 'invalid'),'x-cline-task':sign(c),'content-type':'application/json'});r=conn.getresponse();data=json.loads(r.read());conn.close();return r.status,data
def ids():return sorted(out(*D,'ps','-aq').splitlines())
def body():return {'workerId':str(uuid.uuid4()),'resourceProfile':'standard','apiToken':uuid.uuid4().hex+uuid.uuid4().hex,'brokerToken':uuid.uuid4().hex+uuid.uuid4().hex,'editTool':'editor'}
def rootful_snapshot():
 rows=[]
 for name in ('cline-p32-control','cline-p32-authority','cline-p32-remote'):
  i=json.loads(out('docker','inspect',name))[0];rows.append({'name':name,'id':i['Id'],'image':i['Image'],'startedAt':i['State']['StartedAt'],'mounts':sorted(i['Mounts'],key=lambda m:m['Destination'])})
 return rows
before=rootful_snapshot();save('shared-before.json',before)
c=contract();base=body();baseline=ids();attacks=[]
for key,value in {'Image':'alpine','HostConfig':{'Privileged':True},'mounts':['/:/host'],'network':'host','pidMode':'host','devices':['/dev/kvm'],'Cmd':['sh'],'dockerPath':'/containers/create','memoryBytes':0}.items():
 r=request(c,'POST','/internal/workers',{**base,key:value});attacks.append({'field':key,'reply':r,'daemonUnchanged':ids()==baseline})
for method,path in [('POST','/v1.45/containers/create'),('POST','/internal/workers/'+base['workerId']+'/exec'),('POST','/internal/workers?path=/containers/create')]:
 r=request(c,method,path,base);attacks.append({'path':path,'reply':r,'daemonUnchanged':ids()==baseline})
record('parameter-and-Docker-API-injection',all(x['reply'][0]>=400 and x['daemonUnchanged'] for x in attacks),attacks)
unauth=request(c,'POST','/internal/workers',base,False);record('service-authentication',unauth[0]==401 and ids()==baseline,unauth)
created=request(c,'POST','/internal/workers',base);record('real-worker-create',created[0]==200,created);assert created[0]==200,created
wid=base['workerId'];path='/internal/workers/'+wid;ci=inspect(created[1]['Id']);save('created-worker-inspect.json',ci)
record('fixed-container-boundary',ci['Image']==cfg['workerImage'] and ci['Config']['User']=='1000:1000' and ci['HostConfig']['NetworkMode']=='none' and not ci['HostConfig']['Privileged'] and ci['HostConfig']['ReadonlyRootfs'] and ci['HostConfig']['CapDrop']==['ALL'] and all(x['Source'].startswith(str(R/'worker-mounts'/wid)+'/') for x in ci['Mounts']),{'image':ci['Image'],'user':ci['Config']['User'],'hostConfig':ci['HostConfig'],'mounts':ci['Mounts']})
replay=request(c,'POST','/internal/workers',dict(reversed(list(base.items()))));record('create-idempotency',replay[0]==200 and replay[1]['Id']==ci['Id'],replay)
changed=request(c,'POST','/internal/workers',{**base,'apiToken':uuid.uuid4().hex+uuid.uuid4().hex});record('immutable-create-conflict',changed[0]==409,changed)
c2=contract(list(gc['tenants'])[-1]);foreign=request(c2,'GET',path);record('cross-owner-inspection-denied',foreign[0]==404,foreign)
quota=request(contract(),'POST','/internal/workers',body());record('Executor-independent-quota',quota[0]==429,quota)
start=request(c,'POST',path+'/start');record('real-worker-start',start[0]==200 and inspect(ci['Id'])['State']['Running'],start)
worker_probe=json.loads(out(*D,'exec',ci['Id'],'node','-e',"const f=require('fs');console.log(JSON.stringify({socket:f.existsSync('/var/run/docker.sock'),executorConfig:f.existsSync('/run/executor.json'),gatewayConfig:f.existsSync('/run/gateway.json'),hostRoot:f.existsSync('/opt/cline-server-p33-stage1-20261108'),uid:process.getuid(),caps:f.readFileSync('/proc/self/status','utf8').match(/CapEff:\\s*(.*)/)[1]}))"));record('Worker-OS-boundary',not any(worker_probe[k] for k in ('socket','executorConfig','gatewayConfig','hostRoot')) and worker_probe['uid']==1000 and int(worker_probe['caps'],16)==0,worker_probe)
gateway=inspect('cline-p331-gateway');executor=inspect('cline-p331-executor');save('gateway-inspect.json',gateway);save('executor-inspect.json',executor)
code="const f=require('fs'),n=require('net');let a={uid:process.getuid(),uidMap:f.readFileSync('/proc/self/uid_map','utf8'),dockerSocket:f.existsSync('/var/run/docker.sock'),privateSocket:f.existsSync('/run/rootless-docker.sock')};const s=n.connect('/var/run/docker.sock');s.on('error',e=>{a.socketError=e.code;console.log(JSON.stringify(a));});"
isolation=json.loads(out(*D,'exec','cline-p331-gateway','node','-e',code));record('Gateway-no-Docker-socket',not isolation['dockerSocket'] and not isolation['privateSocket'] and isolation['socketError']=='ENOENT' and gateway['Config']['User']=='1000:1000' and not any('docker.sock' in x['Source'] for x in gateway['Mounts']),{'process':isolation,'mounts':gateway['Mounts']})
code="const f=require('fs');try{f.renameSync('/workers/"+wid+"/workspace','/workers/"+wid+"/workspace-replaced');console.log('UNSAFE')}catch(e){console.log(e.code)}"
permission=out(*D,'exec','cline-p331-gateway','node','-e',code);record('Gateway-cannot-replace-mount-anchor',permission in ('EACCES','EPERM'),{'error':permission,'root':out(*D,'exec','cline-p331-executor','node','-e',"console.log(JSON.stringify(require('fs').statSync('/workers/"+wid+"'))) ")})
stop=request(c,'POST',path+'/stop');again=request(c,'POST',path+'/stop');late=request(c,'POST',path+'/start');record('stop-proof-and-late-start-rejection',stop[0]==200 and stop[1]['running']==False and again==stop and late[0]==409,{'stop':stop,'repeat':again,'lateStart':late,'actualState':inspect(ci['Id'])['State']})
# Corrupt an isolated anchor as the trusted administrator, then exercise the actual API.
c3=contract(list(gc['tenants'])[-1]);b3=body();r3=request(c3,'POST','/internal/workers',b3);assert r3[0]==200,r3
w3=b3['workerId'];script="const f=require('fs'),p='/workers/"+w3+"/workspace';f.renameSync(p,p+'.saved');f.symlinkSync('/etc',p);"
subprocess.run([*D,'exec','cline-p331-executor','node','-e',script],check=True);denied=request(c3,'POST','/internal/workers/'+w3+'/start');state=inspect(r3[1]['Id'])['State'];record('symbolic-link-anchor-rejected',denied[0]==403 and not state['Running'] and state['StartedAt'].startswith('0001-'),{'reply':denied,'actualState':state})
restore="const f=require('fs'),p='/workers/"+w3+"/workspace';f.unlinkSync(p);f.renameSync(p+'.saved',p);";subprocess.run([*D,'exec','cline-p331-executor','node','-e',restore],check=True)
started3=request(c3,'POST','/internal/workers/'+w3+'/start');killed=request(c3,'POST','/internal/workers/'+w3+'/kill');record('actual-force-kill-proof',started3[0]==200 and killed[0]==200 and killed[1].get('running')==False and inspect(r3[1]['Id'])['State']['ExitCode']==137,{'start':started3,'kill':killed,'actualState':inspect(r3[1]['Id'])['State']})
deleted=request(c3,'DELETE','/internal/workers/'+w3);record('owned-closed-Worker-cleanup',deleted[0]==200 and deleted[1].get('removed')==True,deleted)
recreate=request(c3,'POST','/internal/workers',body());record('cleanup-does-not-authorize-recreation',recreate[0]==409,recreate)
canary=R/'host-root-canary';canary.write_text(uuid.uuid4().hex);canary.chmod(0o600);sentinel=hashlib.sha256(canary.read_bytes()).hexdigest()
code="const f=require('fs');let v={uid:process.getuid(),uidMap:f.readFileSync('/proc/self/uid_map','utf8')};try{f.readFileSync('/host-canary');v.read='UNSAFE'}catch(e){v.read='DENIED';v.error=e.code}console.log(JSON.stringify(v));"
probe=subprocess.run([*D,'run','--rm','--network','none','--user','0:0','--privileged','--mount',f'type=bind,src={canary},dst=/host-canary,readonly','--entrypoint','node',cfg['workerImage'],'-e',code],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
save('rootless-host-root-probe.log',probe.stdout);read=json.loads(probe.stdout) if probe.returncode==0 else {};record('rootless-root-does-not-become-host-root',read.get('read')=='DENIED' and read.get('error') in ('EACCES','EPERM') and hashlib.sha256(canary.read_bytes()).hexdigest()==sentinel,{'exit':probe.returncode,'result':read})
record('per-worker-cgroup-hard-limits',bool(created[1]['resources']['enforced']),created[1]['resources'],False)
after=rootful_snapshot();save('shared-after.json',after);record('existing-services-unchanged',before==after,{'same':before==after})
save('api-summary.json',{'apiAndIsolationChecksPass':all(x['pass'] for x in results if x['blocking']),'productionReady':False,'resourceLimitsAccepted':False,'checks':len(results),'scope':'P3.3.1 only'})
if not all(x['pass'] for x in results if x['blocking']):raise SystemExit(1)
