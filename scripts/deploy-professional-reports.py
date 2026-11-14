"""Run on the authorized source-build server. Build and test before canary activation.

Usage: python3 deploy-professional-reports.py RELEASE_DIRECTORY [--activate]
Source files must already be staged in RELEASE_DIRECTORY/source. No credentials.
"""
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

def run(*args, **kwargs):
    return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT,**kwargs).strip()

def main():
    release=Path(sys.argv[1]).resolve();source=release/'source';tag='runtime-node:professional-reports-20261114'
    expected=json.loads((release/'source-hashes.json').read_text())
    revision=(release/'source-commit.txt').read_text().strip() if (release/'source-commit.txt').is_file() else 'uncommitted'
    if revision!='uncommitted' and (len(revision)!=40 or any(c not in '0123456789abcdef' for c in revision)):raise RuntimeError('Invalid source revision')
    for name,digest in expected.items():
        file=(source/name).resolve()
        if not file.is_relative_to(source) or hashlib.sha256(file.read_bytes()).hexdigest()!=digest:raise RuntimeError('Source digest mismatch')
    dockerfile='''FROM runtime-node:task-cancel-source-20261114
USER root
COPY coding-agent-node/skills/ /opt/runtime-os/coding-node/coding-agent-node/skills/
COPY coding-agent-node/src/skill-distribution.js /opt/runtime-os/coding-node/coding-agent-node/src/
COPY cline-agent-server/src/delivery-manifest.mjs cline-agent-server/src/professional-report-review.mjs cline-agent-server/src/worker-executor-policy.mjs cline-agent-server/src/frontend-proxy.mjs /opt/runtime-os/coding-node/service/src/
RUN node --check /opt/runtime-os/coding-node/service/src/professional-report-review.mjs && python -m py_compile /opt/runtime-os/coding-node/coding-agent-node/skills/report-designer/scripts/reportkit.py
USER 10001:10001
'''
    dockerfile=dockerfile.replace('USER root\n','LABEL org.opencontainers.image.revision="'+revision+'" runtime-os.professional-reports="1.0.2"\nUSER root\n',1)
    (source/'Dockerfile.professional').write_text(dockerfile)
    with (release/'build.log').open('w') as log:subprocess.run(['docker','build','-f','Dockerfile.professional','-t',tag,'.'],cwd=source,stdout=log,stderr=subprocess.STDOUT,check=True)
    image=json.loads(run('docker','inspect',tag))[0]['Id']
    policy_path=Path('/opt/runtime-os/native-temporal/rc-20261112/private/executor2.json')
    policy=json.loads(policy_path.read_text());daemon='unix://'+policy['dockerSocket']
    if run('docker','--host',daemon,'info','--format','{{.ID}}')!=policy['expectedDaemonId']:raise RuntimeError('Executor daemon identity differs')
    with (release/'executor-image-load.log').open('w') as log:
        producer=subprocess.Popen(['docker','save',image],stdout=subprocess.PIPE)
        try:subprocess.run(['docker','--host',daemon,'load'],stdin=producer.stdout,stdout=log,stderr=subprocess.STDOUT,check=True)
        finally:producer.stdout.close()
        if producer.wait()!=0:raise RuntimeError('Executor image export failed')
    if run('docker','--host',daemon,'image','inspect',image,'--format','{{.Id}}')!=image:raise RuntimeError('Executor image load digest differs')
    test=run('docker','run','--rm','-v',str(source)+':/source:ro','-w','/source',tag,'sh','-c','node --test coding-agent-node/test/builtin-skills.test.js coding-agent-node/test/professional-reports.test.js cline-agent-server/test/professional-report-review.test.mjs cline-agent-server/test/delivery-selection.test.mjs && python -m unittest scripts.tests.test_professional_reports')
    (release/'server-tests.log').write_text(test)
    evidence={'buildLocation':'192.168.195.221','buildMode':'server-source','sourceCommit':revision,'image':image,'tag':tag,'sourceFiles':len(expected),'tests':'PASSED','activation':'NOT_ACTIVATED'}
    (release/'deployment.json').write_text(json.dumps(evidence,indent=2))
    if '--activate' not in sys.argv:return
    if '--import-only' in sys.argv:return
    running=run('docker','ps','--format','{{.Names}}').splitlines()
    if any(n.startswith('cline-p331-') for n in running):raise RuntimeError('Active native tasks prevent canary replacement')
    gateway=json.loads(Path('/opt/runtime-os/native-temporal/rc-20261112/private/gateway2.json').read_text())
    for registry in (Path(gateway['stateRoot'])/'workers').glob('*/registry.json'):
        worker=json.loads(registry.read_text())
        if not worker.get('revoked') and worker.get('workerState') not in ['STOPPED','START_FAILED']:raise RuntimeError('Active canary task prevents replacement')
    base=Path('/opt/runtime-os/native-temporal/rc-20261112');compose=base/'compose.private.json';executor=base/'private/executor2.json';backup=release/('rollback-'+image.split(':')[1][:12]);backup.mkdir(mode=0o700,exist_ok=False)
    for p in [compose,executor]:shutil.copy2(p,backup/p.name);(backup/p.name).chmod(0o600)
    command=['docker','compose','--project-name','runtime-native-temporal-candidate','-f',str(compose)]
    run(*command,'stop','worker2','node2','executor2')
    try:
        config=json.loads(compose.read_text())
        for name in ['node2','executor2']:
            config['services'][name]['image']=image
            config['services'][name].setdefault('environment',{})['CLINE_PROFESSIONAL_REPORTS']='true'
        policy=json.loads(executor.read_text());policy['workerImage']=image;executor.write_text(json.dumps(policy,indent=2));executor.chmod(0o600)
        compose.write_text(json.dumps(config,indent=2));compose.chmod(0o600)
        run(*command,'up','-d','--no-deps','--force-recreate','executor2','node2','worker2')
        for name in ['runtime-native-temporal-node2','runtime-native-temporal-executor2','runtime-native-temporal-worker2']:
            if run('docker','inspect',name,'--format','{{.State.Running}}')!='true':raise RuntimeError('Canary container not running')
        discovered=run('docker','exec','runtime-native-temporal-node2','node','--input-type=module','-e',"import {taskSkillPackages} from '/opt/runtime-os/coding-node/coding-agent-node/src/skill-distribution.js'; console.log(taskSkillPackages().filter(p=>p.card.professionalReport).length)")
        if discovered!='4':raise RuntimeError('Canary report skills not discoverable')
        evidence.update(activation='CANARY_NODE2',skills=4,rollback=str(backup),remainingAcceptance=['live autonomous Cline report generation','Silo archive and authenticated readback'])
    except Exception:
        for p in [compose,executor]:shutil.copy2(backup/p.name,p)
        run(*command,'up','-d','--no-deps','--force-recreate','executor2','node2','worker2')
        raise
    finally:(release/'deployment.json').write_text(json.dumps(evidence,indent=2))
    print(json.dumps(evidence))

if __name__=='__main__':main()
