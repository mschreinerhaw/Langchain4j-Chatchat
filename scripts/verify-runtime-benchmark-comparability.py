"""Verify fixed originals, actual mounted bytes, permissions and equal budget ceilings.

No requests are executed; model failures remain failures. Run after scenario collection.
"""
from pathlib import Path
import hashlib
import json
import sys


def main():
    root=Path(sys.argv[1]).resolve()
    prepared=json.loads((root/'scenario-prepared-private.json').read_text())
    cases=json.loads((root/'real-scenario-results.json').read_text())['cases']
    repetitions=len(cases)//2
    assert repetitions in [2,3] and len(cases)==2*repetitions
    assert {c['case'] for c in cases}=={model+'-'+str(repeat) for model in ['flash','plus'] for repeat in range(1,repetitions+1)}
    assert len({c['taskId'] for c in cases})==len(cases),'Each repeat must be a distinct admitted task'
    assert len({c['nodeId'] for c in cases})==len(cases),'Each repeat must have its own persisted node'
    budgets=[];permissions=[];logical=[];mounts=[]
    shared=Path('/opt/runtime-os-docker-stage/mcp/data/python-user-data').resolve()
    for case in cases:
        assert case['status'] in ['COMPLETED','FAILED','CANCELLED','BUDGET_EXCEEDED','TIMED_OUT']
        assert case['actualModel']==case['model']
        budgets.append({k:v for k,v in case['budget'].items() if k not in ['budgetId','deadlineAt']})
        permissions.append({k:case['permissions'][k] for k in ['allowedTools','allowedSideEffects','networkPolicy']})
        logical.append(case['logicalInputs'])
        state=json.loads((root/(case['case']+'-status-private.json')).read_text())
        node=next(n for n in state['nodes'] if n['id']==case['nodeId'])
        assert node['fileIds']==prepared['inputAssetIds']
        task=json.loads((root/(case['case']+'-journal-private.json')).read_text())
        files={}
        for key,snapshots in task['privateState'].items():
            if not key.startswith('inputs:'):continue
            for snapshot in snapshots:
                for file in [snapshot]+snapshot.get('chunks',[]):
                    binding=(file['fileId'],file['fileName'])
                    assert binding not in files or files[binding]==file['sha256']
                    files[binding]=file['sha256']
        verified=[]
        for (identity,name),expected in files.items():
            assert len(identity)==64 and '/' not in name and '\\' not in name
            paths=list(shared.rglob(identity+'/'+name))
            assert len(paths)==1 and paths[0].resolve().is_relative_to(shared) and not paths[0].is_symlink()
            with paths[0].open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
            assert actual==expected
            verified.append({'fileName':name,'sha256':actual,'bytes':paths[0].stat().st_size})
        assert {f['sha256'] for f in prepared['inputFiles']}<={f['sha256'] for f in verified}
        mounts.append({'case':case['case'],'verifiedFileCount':len(verified),'files':verified})
    assert all(v==budgets[0] for v in budgets)
    assert all(v==permissions[0] for v in permissions)
    assert all(v==logical[0] for v in logical)
    proof={'schemaVersion':'runtime-benchmark-comparability.v1','readOnly':True,
        'caseCount':len(cases),'repetitionsPerModel':repetitions,'sameOriginalFiles':True,'sameLogicalContent':True,'sameBudgetCeilings':True,
        'samePermissionKinds':True,'allMountedFileHashesVerified':True,
        'inputFiles':prepared['inputFiles'],'logicalInputs':logical[0],
        'commonBudget':budgets[0],'commonPermissions':permissions[0],'mounts':mounts,
        'qualityPolicy':'Technical completion/archive presence does not certify business correctness. Failures and unknown token usage remain explicit.'}
    (root/'benchmark-comparability-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2))
    print(json.dumps({k:proof[k] for k in ['caseCount','sameOriginalFiles','sameLogicalContent','sameBudgetCeilings','samePermissionKinds','allMountedFileHashesVerified']}))


if __name__=='__main__':main()
