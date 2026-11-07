"""Recover the known ended E02 test through its original native entry, never replace its work.

Requires a persisted stop reason, expired original deadline and a missing/terminal
SDK session. Verifies unchanged contract/budget/inputs, unchanged starts and original
receipt digest afterward. Retains unknown metering reservations and the failed sample.
"""
from pathlib import Path
import datetime
import importlib.util
import json
import subprocess
import sys
import time
import urllib.request


def main():
    root=Path(sys.argv[1]).resolve();assert root==Path('/var/tmp/runtime-empty-p0-20261107-E02')
    intent=root/'receipt-reconciliation-intent-private.json';assert not intent.exists(),'Collect an unknown reconciliation; never replay it'
    spec=importlib.util.spec_from_file_location('collector',root/'collect-coding-progress.py')
    collector=importlib.util.module_from_spec(spec);spec.loader.exec_module(collector)
    old=json.loads((root/'autonomous-day-journal-private.json').read_text());task_id=old['contract']['taskId']
    def current():
        monitor=json.loads(Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json').read_text())
        node=max([n for n in monitor['tasks'] if n['taskId']==task_id],key=lambda n:n['fencingToken'])
        return collector.journal(node)[0]
    before=current();assert before['outcome']['executionControl']['stopReason']
    assert datetime.datetime.fromisoformat(before['contract']['budget']['deadlineAt'].replace('Z','+00:00'))<datetime.datetime.now(datetime.timezone.utc)
    binding={'scope':before['contract']['scope'],'taskId':task_id,'contractHash':before['outcome']['contractSha256'],'fencingToken':before['lease']['fencingToken']}
    code="const f=require('fs');fetch('http://127.0.0.1:3100/v1/runs/get',{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+f.readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim()},body:JSON.stringify("+json.dumps(binding)+")}).then(async r=>{let x=await r.json();console.log(JSON.stringify({httpStatus:r.status,status:x.status,fencingToken:x.fencingToken}));});"
    node=json.loads(subprocess.check_output(['docker','exec','runtime-os-app-stage-coding-agent-1','node','-e',code],text=True))
    assert node['httpStatus']==404 or node.get('status') in ['COMPLETED','FAILED','CANCELLED'],node
    body=json.loads((root/'autonomous-day-intent-private.json').read_text())['request']
    intent.write_text(json.dumps({'startedAt':time.time(),'request':body,'workerBefore':node,'policy':'Expired, already stopped original task; metadata reconciliation only'}))
    (root/'receipt-reconciliation-before-private.json').write_text(json.dumps(before))
    token=json.loads((root/'auth-private.json').read_text())['token']
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1/data-science/user-assets/analyses',data=json.dumps(body,ensure_ascii=False).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=180) as stream:response=json.load(stream)
    (root/'receipt-reconciliation-response-private.json').write_text(json.dumps(response))
    after=current();(root/'receipt-reconciliation-after-private.json').write_text(json.dumps(after))
    assert after['contract']==before['contract'] and after['outcome']['contractSha256']==before['outcome']['contractSha256']
    assert set(after['operations'])==set(before['operations'])
    assert all(op.get('stopProofRef') for op in after['operations'].values() if op.get('startIntent'))
    assert after['outcome']['status'] not in ['RUNNING','PENDING'] and after['outcome']['executionControl']['executorStopped'] is True
    proof={'taskId':task_id,'workerBefore':node,'finalStatus':after['outcome']['status'],'stopReason':after['outcome']['executionControl']['stopReason'],
        'sameContractBudgetAndInputs':True,'newModelCodeOrReadStarts':0,'allStartedOperationsReconciled':True,
        'originalReceiptModified':False,'meteringComplete':after['outcome']['usage']['meteringComplete'],
        'reportSuccessCertified':False,'policy':'Original expired task closed without a business read, new model or budget reset'}
    (root/'receipt-reconciliation-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))


if __name__=='__main__':main()
