"""Migrate an idle, previously verified Native S3 deployment into Compose.

Run on the deployment host. Authority files remain private bind mounts; the
restricted Worker daemon is reused without changing its policy or networking.
"""
import argparse, json, os, signal, subprocess
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--stage',type=Path,required=True)
p.add_argument('--previous',type=Path,required=True)
p.add_argument('--image',required=True)
a=p.parse_args();os.umask(0o077)
stage=a.stage.resolve();old=a.previous.resolve();private=stage/'config/native-node';private.mkdir(exist_ok=True)
compose=json.loads((stage/'compose.yaml').read_text())
image=json.loads(subprocess.check_output(['docker','image','inspect',a.image]))[0]['Id']
cfgs={name:json.loads((old/('private/'+name+'.json')).read_text()) for name in ['executor','gateway','model-audit']}
executor=cfgs['executor'];gateway=cfgs['gateway']
for cfg in [executor,gateway]:cfg['workerImage']=image
for name,cfg in cfgs.items():
    file=private/(name+'.json');file.write_text(json.dumps(cfg));file.chmod(0o600)
# The actual Core image must exist in the pinned, separate execution daemon.
daemon=['docker','--host','unix://'+executor['dockerSocket']]
if subprocess.run(daemon+['image','inspect',image],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
    save=subprocess.Popen(['docker','image','save',image],stdout=subprocess.PIPE)
    loaded=subprocess.run(daemon+['image','load'],stdin=save.stdout)
    save.stdout.close()
    if save.wait() or loaded.returncode:raise RuntimeError('Worker image import failed')
base={'image':image,'user':'0:0','restart':'unless-stopped','network_mode':'host',
      'volumes':[str(old)+':'+str(old),str(private)+':'+str(private)+':ro'],
      'logging':{'driver':'json-file','options':{'max-size':'20m','max-file':'5'}}}
for template in sorted({v for t in executor['tenants'].values() for v in t['workspaces'].values()}):
    base['volumes'].append(template+':'+template+':ro')
def service(name,command,environment=None):
    result={**base,'volumes':list(base['volumes']),'container_name':name,'entrypoint':command}
    if environment:result['environment']=environment
    return result
ex=service('runtime-native-executor',['node','/poc/service/src/worker-executor.mjs'],{'CLINE_EXECUTOR_CONFIG':str(private/'executor.json')})
ex.update(pid='host',cap_add=['SYS_PTRACE'])
anchor=executor['dedicatedDaemonRoot'];ex['volumes'].append(anchor+':'+anchor+':ro')
compose['services']['coding-executor']=ex
compose['services']['coding-node']=service('runtime-coding-node',['node','/poc/service/src/governance-server.mjs'],{'CLINE_GOVERNANCE_CONFIG':str(private/'gateway.json')})
compose['services']['coding-node']['depends_on']={'coding-executor':{'condition':'service_started'},'coding-model-audit':{'condition':'service_started'},'silo':{'condition':'service_started'}}
compose['services']['coding-model-audit']=service('runtime-coding-model-audit',['python','/poc/service/scripts/s3-model-audit-proxy.py','--settings',str(private/'model-audit.json'),'--root',str(old),'--audit-module','/poc/service/test/live_model_audit.py','--port','18559'])
# Reconcile state before stopping exact, owned host process identities.
for f in (old/'state/workers').glob('*/record.json'):
    state=json.loads(f.read_text())
    if state.get('workerState') in ['RUNNING','READY','STARTING']:
        raise RuntimeError('Active worker requires task drain before migration')
record={'imageId':image,'previousRoot':str(old),'stoppedHostProcesses':[]}
for prior in sorted(old.parent.glob('cs3-v*-20261111/deployment.json')):
    deployed=json.loads(prior.read_text())
    for item in deployed.get('processes',[]):
        pid=item['pid'];proc=Path('/proc')/str(pid)
        if not proc.exists():continue
        stat=(proc/'stat').read_text();ticks=stat[stat.rfind(')')+2:].split()[19]
        if ticks!=item['processStartTicks']:raise RuntimeError('Host process identity changed')
        if '/srv/cs3-build-20261111/' not in (proc/'cmdline').read_text():raise RuntimeError('Host process command differs')
        os.kill(pid,signal.SIGTERM);record['stoppedHostProcesses'].append({'pid':pid,'startTicks':ticks,'role':item['role']})
(stage/'compose.yaml').write_text(json.dumps(compose,indent=2))
(stage/'maintenance/native-container-migration.json').write_text(json.dumps(record,indent=2))
print('Native Compose roles prepared; stopped owned host processes:',len(record['stoppedHostProcesses']))
