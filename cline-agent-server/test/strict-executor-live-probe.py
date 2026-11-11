"""Live resource/ownership/whole-container cancellation probe; zero model calls.

This is infrastructure evidence, not business or Native Session resume acceptance.
All containers are retained, stopped, and registered with their exact daemon.
"""
from pathlib import Path
import argparse, base64, hashlib, hmac, http.client, json, os, secrets
import re, socket, subprocess, time, uuid

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--case',default='strict-executor-component-1');p.add_argument('--executor-index',type=int,default=1);a=p.parse_args()
if not re.fullmatch(r'strict-executor-component-[1-9][0-9]?',a.case) or not 1<=a.executor_index<=99:raise ValueError('Explicit unique probe and Executor required')
os.umask(0o077)
root=a.root;folder=root/'runs'/a.case
folder.mkdir(mode=0o700)
cfg=json.loads((root/'private'/('strict-executor-'+str(a.executor_index)+'.json')).read_text())
tenant=next(iter(cfg['tenants']));user=cfg['tenants'][tenant]['users'][0]
docker=['docker','--host','unix://'+cfg['dockerSocket']]

class UnixConnection(http.client.HTTPConnection):
    def __init__(self,path):super().__init__('localhost',timeout=30);self.path=path
    def connect(self):
        self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.sock.settimeout(self.timeout)
        if len(os.fsencode(self.path))<104:self.sock.connect(self.path)
        else:
            # Linux sun_path is bounded even when the bind-mounted path inside
            # the Worker is short. Connect through a held canonical directory FD.
            directory=os.open(str(Path(self.path).parent),os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:self.sock.connect('/proc/self/fd/'+str(directory)+'/'+Path(self.path).name)
            finally:os.close(directory)

def request(path,method='GET',body=None,grant=None,sock=None,api_token=None):
    client=UnixConnection(sock or cfg['socketPath'])
    headers={'Authorization':'Bearer '+(api_token or cfg['apiToken']),'Content-Type':'application/json'}
    if grant:headers['x-cline-task']=grant
    try:
        client.request(method,path,None if body is None else json.dumps(body),headers)
        response=client.getresponse();raw=response.read(1024*1024+1)
        if len(raw)>1024*1024:raise RuntimeError('Unbounded control response')
        return response.status,json.loads(raw)
    finally:client.close()

def signed(contract):
    raw=base64.urlsafe_b64encode(json.dumps(contract,separators=(',',':')).encode()).decode().rstrip('=')
    signature=base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),raw.encode(),hashlib.sha256).digest()).decode().rstrip('=')
    return raw+'.'+signature

proof={'schemaVersion':'strict_executor_component_probe.v1','modelCalls':0,'businessAcceptance':False,
       'nativeSessionRecoveryAcceptance':False,'daemonSocket':cfg['dockerSocket'],'checks':[],'containers':[]}

def save(): (folder/'proof.json').write_text(json.dumps(proof,indent=2))
def check(name,passed,**facts):
    proof['checks'].append({'name':name,'passed':bool(passed),**facts});save()
    if not passed:raise RuntimeError('Actual component check failed: '+name)
def inspect(ident):return json.loads(subprocess.check_output(docker+['inspect',ident]))[0]

active=[]
try:
    code,health=request('/health/live');check('executor-real-cgroup-readiness',code==200 and health['resourceEnforced'] and health['daemon']=='dedicated-rootful')
    for label in ['a','b']:
        worker=str(uuid.uuid4());task=a.case+'-'+label
        contract={'version':2,'tenantId':tenant,'userId':user,'taskId':task,'sessionId':worker,
            'workspaceRef':'project','modelProfile':'fixed','budgetPolicy':{'status':'DEFERRED_STAGE_2_REVIEW'},
            'toolPermissions':['read_files','run_commands'],'dataPermissions':[],'networkPolicy':'broker-only',
            'expiresAt':int((time.time()+1800)*1000),'runtimeProfile':'native-python-silo-v1',
            'deliveryBinding':{'tenantId':tenant,'taskId':task,'runId':task,'inputSnapshotVersion':'RESOURCE_PROBE_NO_CUSTOMER_DATA'}}
        grant=signed(contract);token=secrets.token_hex(32)
        body={'workerId':worker,'resourceProfile':'standard','apiToken':token,'brokerToken':secrets.token_hex(32),'editTool':'editor'}
        code,created=request('/internal/workers','POST',body,grant)
        check('create-'+label,code==200,observedStatus=code)
        ident=created['Id'];active.append((worker,grant,ident));info=inspect(ident)
        entry={'id':ident,'name':info['Name'],'image':info['Image'],'daemonSocket':cfg['dockerSocket'],
            'executorInstance':cfg['instanceId'],'workerId':worker,'purpose':'zero-model strict resource and cancellation component probe','retainedUntilAcceptanceArchiveAndPush':True}
        proof['containers'].append(entry);save()
        workspace=Path(cfg['workersRoot'])/worker/'workspace'
        (workspace/'input/probe.txt').write_text('readonly original fixture\n')
        os.chown(workspace/'input/probe.txt',10001,10001)
        code,started=request('/internal/workers/'+worker+'/start','POST',{},grant)
        check('start-'+label,code==200 and started['State']['Running'])
        for step in range(80):
            try:
                code,ready=request('/health/ready',sock=str(Path(cfg['workersRoot'])/worker/'bridge/worker.sock'),api_token=token)
                if code==200 and ready.get('ready'):break
            except (OSError,ValueError) as error:
                (folder/('readiness-last-error-'+label+'.json')).write_text(json.dumps({'error':str(error),'pathBytes':len(os.fsencode(str(Path(cfg['workersRoot'])/worker/'bridge/worker.sock')))}))
            time.sleep(.25)
        else:raise RuntimeError('Actual image Native Server readiness failed')
        check('real-image-native-readiness-'+label,True)
        info=inspect(ident);host=info['HostConfig']
        check('kernel-isolation-'+label,info['Config']['User']=='10001:10001' and host['NetworkMode']=='none'
            and host['ReadonlyRootfs'] and host['Memory']==3*1024**3 and host['NanoCpus']==2*10**9
            and host['PidsLimit']==256 and host.get('Init') is True and not host.get('Privileged') and host['CapDrop']==['ALL'],
            actualMemory=host['Memory'],actualNanoCpus=host['NanoCpus'],actualPidsLimit=host['PidsLimit'],actualInit=host.get('Init'))
        program="""import os,pathlib,json,subprocess
root=pathlib.Path('/poc/project')
try:
 (root/'input/probe.txt').write_text('forbidden')
 readonly=False
except OSError as e: readonly=e.errno==30
print(json.dumps({'uid':os.getuid(),'readOnlyInput':readonly,'cgroup':pathlib.Path('/proc/self/cgroup').read_text(),'otherWorkerVisible':pathlib.Path('/srv').exists()}))
"""
        executed=subprocess.run(docker+['exec',ident,'python','-c',program],capture_output=True,text=True,timeout=20)
        (folder/('python-'+label+'.stdout.json')).write_text(executed.stdout)
        (folder/('python-'+label+'.stderr.log')).write_text(executed.stderr)
        result=json.loads(executed.stdout);check('actual-python-readonly-input-'+label,executed.returncode==0 and result['uid']==10001 and result['readOnlyInput'])
        groups={controller:path for line in result['cgroup'].splitlines() for _,names,path in [line.split(':',2)] for controller in names.split(',')}
        if any('..' in path.split('/') for path in groups.values()):raise RuntimeError('Invalid observed cgroup path')
        memory=int((Path('/sys/fs/cgroup/memory')/groups['memory'].lstrip('/')/'memory.limit_in_bytes').read_text())
        cpu=Path('/sys/fs/cgroup/cpu,cpuacct')/groups['cpu'].lstrip('/')
        quota=int((cpu/'cpu.cfs_quota_us').read_text());period=int((cpu/'cpu.cfs_period_us').read_text())
        pids=int((Path('/sys/fs/cgroup/pids')/groups['pids'].lstrip('/')/'pids.max').read_text())
        check('actual-kernel-cgroup-values-'+label,memory==3*1024**3 and quota==2*period and pids==256,memoryBytes=memory,cpuQuota=quota,cpuPeriod=period,pidsMax=pids)
        wrong=signed({**contract,'taskId':'another-task','deliveryBinding':{**contract['deliveryBinding'],'taskId':'another-task'}})
        code,_=request('/internal/workers/'+worker,grant=wrong);check('cross-task-inspect-denied-'+label,code==404,observedStatus=code)
        code,_=request('/internal/workers','POST',{**body,'image':'alpine'},grant);check('caller-image-override-denied-'+label,code==403,observedStatus=code)
    # Deliberately detach a process so killing only the original process group
    # cannot satisfy this probe. The Executor must stop the entire container.
    worker,grant,ident=active[0];workspace=Path(cfg['workersRoot'])/worker/'workspace'
    child="import pathlib,time;pathlib.Path('/poc/project/detached-ready.txt').write_text('ready');time.sleep(8);pathlib.Path('/poc/project/late-write.txt').write_text('must not be accepted');time.sleep(120)"
    launch="import subprocess,time;subprocess.Popen(['python','-c',"+repr(child)+"],start_new_session=True,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);time.sleep(120)"
    subprocess.run(docker+['exec','-d',ident,'python','-c',launch],check=True,capture_output=True,timeout=15)
    for _ in range(40):
        if (workspace/'detached-ready.txt').exists():break
        time.sleep(.1)
    else:raise RuntimeError('Detached process did not really start')
    code,stop=request('/internal/workers/'+worker+'/stop','POST',{},grant)
    check('whole-container-stop-confirmed',code==200 and stop.get('running') is False and not inspect(ident)['State']['Running'],actualReceipt=stop)
    code,_=request('/internal/workers/'+worker+'/start','POST',{},grant);check('closed-worker-late-start-denied',code==409,observedStatus=code)
    time.sleep(9)
    check('detached-late-write-prevented',not (workspace/'late-write.txt').exists() and (workspace/'input/probe.txt').read_text()=='readonly original fixture\n')
    proof['allChecksPassed']=True;save()
finally:
    for worker,grant,ident in active:
        if inspect(ident)['State']['Running']:
            code,stop=request('/internal/workers/'+worker+'/stop','POST',{},grant)
            if code!=200 or stop.get('running') is not False:raise RuntimeError('Owned probe worker stop is unresolved')
    proof['allRegisteredProbeContainersStopped']=all(not inspect(ident)['State']['Running'] for _,_,ident in active)
    proof['finalCleanupPerformed']=False;save()
print(json.dumps({'allChecksPassed':proof.get('allChecksPassed',False),'checks':len(proof['checks']),'modelCalls':0,'businessAcceptance':False,'stoppedContainerIds':[item[2] for item in active]}))
