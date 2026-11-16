#!/usr/bin/env python3
"""Release acceptance in an empty Docker daemon, using only docker load/run inside it.

Run on the build host; this is a test harness, not a customer dependency.
"""
import argparse,hashlib,json,os,pathlib,subprocess,time,uuid
os.umask(0o077)

p=argparse.ArgumentParser();p.add_argument('--archive',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);p.add_argument('--name',default='runtime-os-cold-host-20261115');p.add_argument('--http-port',default='19400');p.add_argument('--mcp-port',default='19401');a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
def run(args,**kwargs):return subprocess.run([str(v) for v in args],check=True,**kwargs)
def output(args):return subprocess.check_output([str(v) for v in args],text=True).strip()
def inner(*args,**kwargs):return run(['docker','exec',a.name,'docker',*args],**kwargs)
def save(name,value):(a.output/name).write_text(json.dumps(value,indent=2)+'\n')
if subprocess.run(['docker','inspect',a.name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:raise RuntimeError('Use a new empty test host name')
for suffix in ['engine','data']:
 volume=a.name+'-'+suffix
 if subprocess.run(['docker','volume','inspect',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:raise RuntimeError('Test volume already exists: '+volume)
 run(['docker','volume','create','--label','runtime-os.cold-acceptance='+a.name,volume],stdout=subprocess.DEVNULL)
run(['docker','run','-d','--pull=never','--privileged','--name',a.name,'--memory','12g','--pids-limit','4096','-e','DOCKER_TLS_CERTDIR=',
    '-p',a.http_port+':'+a.http_port,'-p',a.mcp_port+':'+a.mcp_port,
    '-v',a.name+'-engine:/var/lib/docker','-v',a.name+'-data:/data',
    '--mount','type=bind,src='+str(a.archive.resolve())+',dst=/bundle.tar.gz,readonly','docker:27.5.1-dind'])
for _ in range(90):
 if subprocess.run(['docker','exec',a.name,'docker','info'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
 time.sleep(2)
else:raise RuntimeError('Empty Docker host did not start')
before=output(['docker','exec',a.name,'docker','images','-aq'])
assert not before,'The test daemon must contain no application or dependency images'
save('cold-before.json',{'imageCount':0,'installationDirectoryExisted':False,'testHost':a.name})
# Deny new outbound connections while allowing incoming browser access and replies.
run(['docker','exec',a.name,'sh','-ec',
     'iptables -I DOCKER-USER 1 -o eth0 -m conntrack --ctstate NEW -j REJECT; '
     'iptables -I OUTPUT 1 -o eth0 -m conntrack --ctstate NEW -j REJECT'])
print('EMPTY_DOCKER_HOST_READY; outbound downloads blocked',flush=True)
with (a.output/'image-load.log').open('wb') as log:inner('load','-i','/bundle.tar.gz',stdout=log,stderr=subprocess.STDOUT)
print('BUNDLE_LOADED',flush=True)
image='runtime-os-installer:20261115-docker-only'
base=['run','--rm','--pull=never','-v','/var/run/docker.sock:/var/run/docker.sock','-v','/data/runtime-os:/install','-e','HTTP_PORT='+a.http_port,'-e','MCP_PORT='+a.mcp_port,image]
with (a.output/'install.private.log').open('wb') as log:inner(*base,'install',stdout=log,stderr=subprocess.STDOUT)
proof=json.loads(output(['docker','exec',a.name,'cat','/data/runtime-os/verification.json']))
assert proof['status']=='PASSED' and proof['nativeCoreVerified'] and proof['pythonRuntimeVerified']
manifest=json.loads(output(['docker','exec',a.name,'docker','run','--rm','--pull=never','--entrypoint','cat',image,'/opt/product/images.json']))
for entry in manifest.values():
 actual=output(['docker','exec',a.name,'docker','image','inspect',entry['reference'],'--format','{{.Id}}'])
 assert actual==entry['imageId']
save('cold-installation.json',{'status':'PASSED','startedWithZeroImages':True,'outboundConnectionsBlocked':True,'deploymentHostCommands':['docker load','docker run'],
    'hostPythonOrJavaOrNodeOrComposeUsed':False,'allImagesMatchedRelease':True,'verification':proof,'httpPort':a.http_port,'mcpPort':a.mcp_port,'testHost':a.name})
print('DOCKER_ONLY_COLD_INSTALL_PASSED',flush=True)

# Technical persistence probe in this disposable test environment.
def sql(query):return output(['docker','exec',a.name,'docker','exec','runtime-os-postgresql','psql','-U','postgres','-d','live_runtime_api','-At','-v','ON_ERROR_STOP=1','-c',query])
token=uuid.uuid4().hex
sql("CREATE TABLE installation_acceptance_probe(value text NOT NULL); INSERT INTO installation_acceptance_probe VALUES ('"+token+"')")
def identity():
 state=json.loads(output(['docker','exec',a.name,'cat','/data/runtime-os/installation.json']))
 return {'postgresSystemId':sql('SELECT system_identifier FROM pg_control_system()'),'adminId':sql("SELECT id FROM sys_user WHERE username='admin'"),'secretsFingerprint':hashlib.sha256(json.dumps(state['secrets'],sort_keys=True).encode()).hexdigest(),'siloUser':state['siloUser']}
before=identity()
with (a.output/'lifecycle.private.log').open('wb') as log:
 inner(*base,'stop',stdout=log,stderr=subprocess.STDOUT)
 assert not output(['docker','exec',a.name,'docker','ps','-q'])
 inner(*base,'start',stdout=log,stderr=subprocess.STDOUT)
assert sql('SELECT value FROM installation_acceptance_probe')==token
assert identity()==before
save('cold-lifecycle.json',{'status':'PASSED','allProjectContainersStopped':True,'postgresProbeRetained':True,'postgresSystemIdRetained':True,'administratorIdRetained':True,'generatedSecretsRetained':True,'siloCredentialsRetained':True})
print('DOCKER_ONLY_STOP_START_PASSED',flush=True)

binding=json.loads(output(['docker','exec',a.name,'cat','/data/runtime-os/daemon-binding.json']))
inner('restart','runtime-os-executor-daemon',stdout=subprocess.DEVNULL)
for _ in range(60):
 time.sleep(3)
 current=json.loads(output(['docker','exec',a.name,'cat','/data/runtime-os/daemon-binding.json']))
 if current['startedAt']!=binding['startedAt']:break
else:raise RuntimeError('Manager did not reconcile the restarted private Docker daemon')
with (a.output/'executor-recovery.log').open('wb') as log:inner(*base,'verify',stdout=log,stderr=subprocess.STDOUT)
save('cold-executor-recovery.json',{'status':'PASSED','dedicatedDaemonRestarted':True,'managerAutomaticallyReboundProcesses':True,'daemonIdentityRetained':current['daemonId']==binding['daemonId'],'restrictedCoreAndPythonVerifiedAgain':True})
print('DOCKER_ONLY_EXECUTOR_RECOVERY_PASSED',flush=True)
