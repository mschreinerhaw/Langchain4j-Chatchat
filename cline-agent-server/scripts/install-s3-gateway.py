"""Install an opt-in S3 gateway on an existing verified Executor daemon.

Run on Linux with a private settings file and an existing owned daemon policy.
Never starts/reconfigures Docker daemons, changes shared models, or replaces the
production Coding Agent. All generated authority files stay outside the image.
"""
import argparse, copy, hashlib, json, os, shutil, subprocess, time, urllib.request
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--root',type=Path,required=True)
p.add_argument('--context',type=Path,required=True)
p.add_argument('--executor-template',type=Path,required=True)
p.add_argument('--model-settings',type=Path,required=True)
p.add_argument('--node',type=Path,required=True)
p.add_argument('--port',type=int,default=18338)
p.add_argument('--model-proxy-port',type=int,default=18539)
p.add_argument('--s3-origin',required=True)
p.add_argument('--tag',default='runtime-os-coding-node:cline-4.1.26-python3.11')
p.add_argument('--python-base',help='Immutable Python environment image; may be a previously verified unified image')
p.add_argument('--node-base',help='Immutable image containing /usr/local/bin/node')
p.add_argument('--docker-host',help='Explicit existing build daemon socket; never creates a daemon')
a=p.parse_args();os.umask(0o077)
if a.docker_host:os.environ['DOCKER_HOST']=a.docker_host
root=a.root.resolve();context=a.context.resolve()
if root.exists() or not context.is_dir() or len(root.parts)<3:
    raise RuntimeError('Fresh canonical deployment root and existing context required')
root.mkdir(mode=0o700)
(root/'private').mkdir(mode=0o700);(root/'evidence').mkdir();(root/'state').mkdir()
record={'schemaVersion':'coding_node_s3_deployment.v1','productionDefaultChanged':False,'root':str(root),'port':a.port,'processes':[]}
def save():
    (root/'deployment.json').write_text(json.dumps(record,indent=2))
save()
build=['python3',str(context/'cline-agent-server/scripts/build-python-node.py'),'--context',str(context),'--tag',a.tag]
if a.python_base:build+=['--python-base',a.python_base]
if a.node_base:build+=['--node-base',a.node_base]
with (root/'evidence/build.log').open('x') as log:
    result=subprocess.run(build,stdout=log,stderr=subprocess.STDOUT)
record['buildExitCode']=result.returncode;save()
if result.returncode:raise RuntimeError('Image build failed; inspect retained build evidence')
image=json.loads(subprocess.check_output(['docker','image','inspect',a.tag]))[0]
record['imageId']=image['Id'];record['repoDigests']=image.get('RepoDigests',[])
record['officialSourceCommit']='a459f1d04dea030833b558be645011ab739c463b';save()
base=json.loads(a.executor_template.read_text());settings=json.loads(a.model_settings.read_text())
import secrets
base.update(instanceId='generic-s3-'+secrets.token_hex(8),workerImage=image['Id'],
    apiToken=secrets.token_hex(32),signingKey=secrets.token_hex(32),ownershipKey=secrets.token_hex(32),
    workersRoot=str(root/'workers'),hostWorkersRoot=str(root/'workers'),
    controlRoot=str(root/'executor-control'),socketPath=str(root/'executor.sock'))
base['maxWorkers']=2;base['maxPerTenant']=2
# Only an existing pinned, isolated daemon is reused; no Docker API in gateway.
executor_file=root/'private/executor.json';executor_file.write_text(json.dumps(base))
audit_settings={**settings,'proxyToken':secrets.token_hex(32)}
audit_file=root/'private/model-audit.json';audit_file.write_text(json.dumps(audit_settings))
gateway={'host':'127.0.0.1','port':a.port,'stateRoot':str(root/'state'),
    'workerRoot':base['workersRoot'],'workerRuntime':'cline-python311','workerImage':image['Id'],
    'signingKey':base['signingKey'],'executor':{'socketPath':base['socketPath'],'token':base['apiToken']},
    'tenants':base['tenants'],'maxWorkers':2,'maxPerTenant':2,
    's3':{'allowedOrigins':[a.s3_origin],'maxInputBytes':1073741824,'maxOutputBytes':1073741824,'autoApproveAuthorizedTools':True},
    'modelProfiles':{k:{'model':v,'baseUrl':'http://127.0.0.1:'+str(a.model_proxy_port)+'/{sessionId}/v1',
        'apiKey':audit_settings['proxyToken']} for k,v in base['modelNames'].items()}}
gateway_file=root/'private/gateway.json';gateway_file.write_text(json.dumps(gateway))
source=context/'cline-agent-server'
def launch(role,command,env=None):
    with (root/'evidence'/(role+'.log')).open('xb') as log:
        process=subprocess.Popen(command,env={**os.environ,**(env or {})},stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    ticks=Path('/proc/'+str(process.pid)+'/stat').read_text().split()[21]
    record['processes'].append({'role':role,'pid':process.pid,'processStartTicks':ticks});save();return process
proxy=launch('model-audit',['python3',str(source/'scripts/s3-model-audit-proxy.py'),
    '--settings',str(audit_file),'--root',str(root),'--audit-module',str(source/'test/live_model_audit.py'),
    '--port',str(a.model_proxy_port)])
executor=launch('executor',[str(a.node),str(source/'src/worker-executor.mjs')],{'CLINE_EXECUTOR_CONFIG':str(executor_file)})
for _ in range(60):
    if executor.poll() is not None:raise RuntimeError('Owned Executor failed; inspect its retained log')
    if Path(base['socketPath']).exists():break
    time.sleep(.5)
else:raise RuntimeError('Owned Executor startup timeout')
gateway_process=launch('gateway',[str(a.node),str(source/'src/governance-server.mjs')],{'CLINE_GOVERNANCE_CONFIG':str(gateway_file)})
for _ in range(60):
    if gateway_process.poll() is not None or proxy.poll() is not None:raise RuntimeError('Gateway/audit startup failed')
    try:
        with urllib.request.urlopen('http://127.0.0.1:'+str(a.port)+'/health/live',timeout=2) as response:
            record['health']=json.load(response);break
    except OSError:time.sleep(.5)
else:raise RuntimeError('Gateway health timeout')
record['state']='DEPLOYED_PENDING_BUSINESS_ACCEPTANCE';save()
print(json.dumps(record,indent=2))
