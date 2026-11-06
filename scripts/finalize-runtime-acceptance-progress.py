"""Acceptance release only, after six native trials finish; existing deploy gates remain authoritative.

Reclaims only this invocation's context and unused duplicate prepare image. Keeps
the deployed/rollback images, exact JAR, business data, evidence and all volumes.
"""
from datetime import datetime, timezone, timedelta
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def caches():
    return [{line.split(':',1)[0].strip():line.split(':',1)[1].strip()
             for line in block.splitlines() if ':' in line}
            for block in run('docker','buildx','du','--verbose').split('\n\n')]


def created(cache):
    return datetime.fromisoformat(cache['Created at'].split(' +')[0]).replace(tzinfo=timezone.utc)


def main():
    os.umask(0o077)
    root=Path('/var/tmp/runtime-cline-acceptance-20261107-progress')
    ui=Path('/var/tmp/runtime-cline-acceptance-20261107-ui')
    if sys.argv[1:]==['--resume-wait-only']:
        stopped=json.loads((root/'wait-only-finalizer-stop.json').read_text())
        assert (root/'finalize-intent.json').exists() and not Path('/proc/'+str(stopped['pid'])).exists()
        assert not (root/'prepare.log').exists() and not (root/'deployment.json').exists(),'Never resume a release that reached mutation'
        intent=json.loads((root/'finalize-intent.json').read_text());intent['resumedWaitAt']=time.time()
    else:
        assert not sys.argv[1:] and not (root/'finalize-intent.json').exists(),'Never automatically repeat a release'
        intent={'startedAt':time.time(),'policy':'wait for completed isolated benchmark; retain existing idle gates'}
    (root/'finalize-intent.json').write_text(json.dumps(intent))
    while not (ui/'acceptance-batch-completed.json').exists():time.sleep(30)
    bench=Path('/var/tmp/runtime-cline-acceptance-20261107-performance')
    cases=json.loads((bench/'real-scenario-results.json').read_text())['cases']
    assert len(cases)==6 and all(c['status'] in ['COMPLETED','PARTIAL','FAILED','CANCELLED','BUDGET_EXCEEDED','TIMED_OUT','REJECTED'] for c in cases)
    assert hashlib.sha256((root/'bundle.tar.gz').read_bytes()).hexdigest()=='462eb5d4936c24e83c15f04ab3d64aaff75764be702a018a0447970253bc2219'
    subprocess.run(['tar','-xzf',str(root/'bundle.tar.gz'),'-C',str(root)],check=True)
    previous=Path('/var/tmp/runtime-cline-acceptance-20261107-availability')
    for name in ['catalog-readiness-private.json','restrict-coding-worker.sh']:shutil.copy2(previous/name,root/name)
    start=datetime.now(timezone.utc)
    with (root/'prepare.log').open('w') as log:
        subprocess.run(['python3',str(root/'prepare-runtime-data-remediation.py'),str(root),'--api-only'],check=True,stdout=log,stderr=subprocess.STDOUT)
    manifest=json.loads((root/'manifest.json').read_text())
    manifest.update(catalogReadinessAuthFile='catalog-readiness-private.json',catalogReadinessExpectedTools=['web_search'])
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2))
    contexts=[c for c in caches() if c.get('Type')=='source.local' and c.get('Shared')=='false' and c.get('Reclaimable')=='true'
              and 'local source for context' in c.get('Description','') and c.get('Size','').endswith('MB') and created(c)>=start-timedelta(seconds=5)]
    if len(contexts)==1:subprocess.run(['docker','builder','prune','-f','--filter','id='+contexts[0]['ID']],check=True)
    (root/'own-prepare-context-reclaim.json').write_text(json.dumps(contexts,indent=2))
    with (root/'deployment.log').open('w') as log:
        subprocess.run(['python3',str(root/'deploy-integrated-runtime.py'),str(root)],check=True,stdout=log,stderr=subprocess.STDOUT)
    prepared=json.loads((root/'artifact-proof.json').read_text())['services']['api']['preparedImageId']
    actual=json.loads(run('docker','image','inspect',manifest['image']))[0]['Id']
    assert actual!=prepared and not run('docker','ps','-aq','--filter','ancestor='+prepared)
    stamp=datetime.fromisoformat(json.loads(run('docker','image','inspect',prepared))[0]['Created'].replace('Z','+00:00'))
    duplicate=[c for c in caches() if '[3/3] COPY app.jar' in c.get('Description','') and c.get('Reclaimable')=='true' and abs((created(c)-stamp).total_seconds())<10]
    assert len(duplicate)==1
    subprocess.run(['docker','rmi',prepared],check=True)
    subprocess.run(['docker','builder','prune','-f','--filter','id='+duplicate[0]['ID']],check=True)
    (root/'unused-prepare-image-reclaim.json').write_text(json.dumps({'unusedImage':prepared,'deployedImage':actual,'cache':duplicate,'jarRetained':(root/'app.jar').exists()},indent=2))
    contexts=[c for c in caches() if c.get('Type')=='source.local' and c.get('Shared')=='false' and c.get('Reclaimable')=='true'
              and 'local source for context' in c.get('Description','') and c.get('Size','').endswith('MB') and created(c)>=start-timedelta(seconds=5)]
    if len(contexts)==1:subprocess.run(['docker','builder','prune','-f','--filter','id='+contexts[0]['ID']],check=True)
    (root/'own-deploy-context-reclaim.json').write_text(json.dumps(contexts,indent=2))
    credentials=json.loads((root/'catalog-readiness-private.json').read_text())
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1/enterprise/auth/login',data=json.dumps(credentials).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=60) as response:auth=json.load(response)
    assert auth['code']==200
    roots=list(Path('/var/tmp').glob('runtime-cline-acceptance-20261107-*'))+[Path('/var/tmp/runtime-data-remediation-20261107-r7')]+list(Path('/opt/chatchat-releases').glob('runtime-data-remediation-20261107-r[456]'))
    for path in roots:
        if path.is_dir():(path/'auth-private.json').write_text(json.dumps(auth['data']));os.chmod(path/'auth-private.json',0o600)
    (root/'finalize-completed.json').write_text(json.dumps({'completedAt':time.time(),'allSixTrialsTerminalBeforeDeployment':True,'freshAuthenticationDistributed':True,'readOnlyProgressChange':True}))
    print('Progress release deployed after six terminal native trials',flush=True)


if __name__=='__main__':main()
