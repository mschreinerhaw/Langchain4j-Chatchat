"""Acceptance test sequencing only. All business work stays in the native SDK.

Waits for the two already admitted B samples, uses the existing guarded UI
deployment, runs C/D, then isolates the three paired performance rounds. Never
replays an unknown request, changes a budget, or chooses the Agent's tools.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import time


def main():
    os.umask(0o077)
    base=Path('/var/tmp/runtime-data-remediation-20261107-r7')
    ui=Path('/var/tmp/runtime-cline-acceptance-20261107-ui')
    state=ui/'acceptance-batch-intent.json'
    assert sys.argv[1:] in [[],['--resume-after-deploy'],['--after-contract-repair'],['--after-availability-repair']]
    availability='--after-availability-repair' in sys.argv
    repaired='--after-contract-repair' in sys.argv
    case_keys=['C3','D3'] if availability else ['C2','D2'] if repaired else ['C','D']
    if '--resume-after-deploy' in sys.argv or repaired or availability:
        deployed=Path('/var/tmp/runtime-cline-acceptance-20261107-availability/deployment.json') if availability else Path('/var/tmp/runtime-cline-acceptance-20261107-final/deployment.json') if repaired else ui/'deployment.json'
        assert state.exists() and deployed.exists(),'Resume requires a confirmed deployment'
        assert not Path('/var/tmp/runtime-cline-acceptance-20261107-performance').exists()
        assert all(not Path('/var/tmp/runtime-cline-acceptance-20261107-'+key+'/autonomous-search-intent-private.json').exists() for key in case_keys), 'Never replay an admitted task'
    else:
        assert not state.exists(),'Inspect existing batch state; no automatic replay'
        state.write_text(json.dumps({'startedAt':time.time(),'policy':'guarded deployment; unchanged native contracts'}))

    def run(script,root,*args):
        with (root/(script+'-batch.log')).open('a') as log:
            subprocess.run(['python3',str(base/script),*args,str(root)],check=True,stdout=log,stderr=subprocess.STDOUT)

    def terminal(root,kind):
        run('verify-runtime-autonomous-resources.py',root,'collect',kind)
        status=json.loads((root/('autonomous-'+kind+'-proof.json')).read_text())['status']
        return status in ['COMPLETED','PARTIAL','FAILED','CANCELLED','BUDGET_EXCEEDED','TIMED_OUT','REJECTED']

    def wait(roots):
        while True:
            finished=[terminal(root,kind) for root,kind in roots]
            if all(finished):return
            time.sleep(20)

    wait([(Path('/var/tmp/runtime-cline-acceptance-20261107-'+key),'day') for key in ['B','B2']])
    print('B samples terminal; entering existing deployment gates',flush=True)
    if not (ui/'deployment.json').exists():
        with (ui/'deployment.log').open('w') as log:
            subprocess.run(['python3',str(ui/'deploy-integrated-runtime.py'),str(ui)],check=True,stdout=log,stderr=subprocess.STDOUT)
    cases=[Path('/var/tmp/runtime-cline-acceptance-20261107-'+key) for key in case_keys]
    def execute(root):
        assert not (root/'autonomous-search-intent-private.json').exists(),'Never replay an admitted task'
        run('verify-runtime-autonomous-resources.py',root,'run','search')
    with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(execute,cases))
    wait([(root,'search') for root in cases])
    print('C/D terminal; preparing isolated six-task benchmark',flush=True)
    frozen=Path('/opt/chatchat-releases/runtime-data-remediation-20261107-r5')
    bench=Path('/var/tmp/runtime-cline-acceptance-20261107-performance')
    bench.mkdir(mode=0o700,exist_ok=False)
    for name in ['parent-run-private.json','original-input-evidence-private.json','market_get_day_snapshot-private.json']:
        shutil.copy2(frozen/name,bench/name)
    for name in ['auth-private.json','collect-coding-progress.py','verify-runtime-data-scenarios.py','verify-runtime-benchmark-comparability.py','verify-runtime-report-archives.py']:
        shutil.copy2(base/name,bench/name)
    shutil.copy2(ui/'acceptance-frozen-inputs-private.json',bench/'acceptance-frozen-inputs-private.json')
    with (bench/'prepare.log').open('w') as log:
        subprocess.run(['python3',str(base/'verify-runtime-data-scenarios.py'),'prepare',str(bench),'--files-only'],
                       env={**os.environ,'RUNTIME_ACCEPTANCE_REPETITIONS':'3'},check=True,stdout=log,stderr=subprocess.STDOUT)
    run('verify-runtime-data-scenarios.py',bench,'run')
    run('verify-runtime-data-scenarios.py',bench,'collect')
    run('verify-runtime-benchmark-comparability.py',bench)
    run('verify-runtime-report-archives.py',bench)
    (ui/'acceptance-batch-completed.json').write_text(json.dumps({'endedAt':time.time(),
        'benchmarkRoot':str(bench),'businessQuality':'REQUIRES_INDEPENDENT_REVIEW'}))
    print('Native test runs and mechanical proofs complete; independent review required',flush=True)


if __name__=='__main__':main()
