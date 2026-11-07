"""One bounded native run per prepared case; only read status after an uncertain response.

Sequential file cases leave one Worker slot for the independent real-market E02.
No business exploration or report content is implemented by this test scheduler.
"""
from pathlib import Path
import json
import os
import subprocess
import sys
import time


def main():
    os.umask(0o077);root=Path(sys.argv[1]).resolve()
    assert root.parent==Path('/var/tmp') and root.name in ['runtime-empty-p0-20261107','runtime-empty-p0-20261107-retest']
    intent=root/'model-batch-intent.json';assert not intent.exists(),'An existing batch must be collected, never replayed'
    prepared=json.loads((root/'model-cases-prepared-private.json').read_text())
    intent.write_text(json.dumps({'startedAt':time.time(),'cases':[c['case'] for c in prepared['cases']],'maxConcurrentFileCases':1,'budgetOverride':False}))
    script=root/'verify-runtime-empty-data-models.py';finished=[]
    terminal={'COMPLETED','PARTIAL','FAILED','CANCELLED','BUDGET_EXCEEDED','TIMED_OUT','REJECTED'}
    for case in prepared['cases']:
        name=case['case']
        (root/'model-batch-current.json').write_text(json.dumps({'case':name,'startedAt':time.time(),'finished':finished}))
        with (root/(name+'-run.log')).open('w') as stream:
            result=subprocess.run(['python3',str(script),'run',str(root),name],stdout=stream,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+1800
        while True:
            with (root/'model-collect.log').open('a') as stream:
                subprocess.run(['python3',str(script),'collect',str(root)],stdout=stream,stderr=subprocess.STDOUT,check=True)
            states=json.loads((root/'model-cases-progress-proof.json').read_text())['cases']
            state=next(c for c in states if c['case']==name)
            if state['status'] in terminal:finished.append({'case':name,'status':state['status'],'httpProcessExit':result.returncode});break
            if time.monotonic()>=deadline:
                (root/'model-batch-unknown.json').write_text(json.dumps({'case':name,'status':state['status'],'policy':'No new cases or replacement work; inspect existing task only'}))
                raise RuntimeError('Native task outcome remains unknown; batch stopped without replay')
            time.sleep(15)
    (root/'model-batch-completed.json').write_text(json.dumps({'finishedAt':time.time(),'cases':finished,'allTerminal':True,'businessAcceptanceCertified':False},indent=2))
    print(json.dumps(finished),flush=True)


if __name__=='__main__':main()
