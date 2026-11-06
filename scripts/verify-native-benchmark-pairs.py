"""Read-only pairing proof from original synthetic requests and durable task contracts."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    a = p.parse_args()
    root = a.root
    cases = sorted({f.name.removesuffix('-request.json') for v in ['baseline','candidate'] for f in (root / v).glob('*-request.json')})
    rows = []
    for case in cases:
        pair = {}
        for variant in ['baseline','candidate']:
            request_file = root / variant / (case + '-request.json')
            journal_file = root / (variant + '-evidence-private') / (case + '-journal.json')
            if not request_file.exists() or not journal_file.exists():
                continue
            request = json.loads(request_file.read_text())
            task = json.loads(journal_file.read_text())
            c = task['contract']
            pair[variant] = {'instructionSha256':digest(request['instruction']),'fileIdsSha256':digest(request['fileIds']),
                'model':request['modelName'],'runtimeModelSha256':digest(c.get('runtimeContext',{}).get('model')),
                'budgetLimitsSha256':digest({k:v for k,v in c['budget'].items() if k not in ['budgetId','deadlineAt']}),
                'taskId':c['taskId'],'maxModelCalls':c['budget']['maxModelCalls'],
                'deadlineAt':c['budget']['deadlineAt'],'createdAt':task['outcome']['createdAt']}
        fields = ['instructionSha256','fileIdsSha256','model','runtimeModelSha256','budgetLimitsSha256']
        comparisons = {k:len(pair)==2 and pair['baseline'][k]==pair['candidate'][k] for k in fields}
        rows.append({'case':case,'variants':pair,'same':comparisons,'status':'PASS' if all(comparisons.values()) else 'FAIL'})
    out = {'scope':'Original question, immutable file IDs, model routing metadata and configured budget limits; per-task IDs and absolute deadlines intentionally differ. No raw model configuration exported.',
        'fixturesSha256':hashlib.sha256((root / 'fixtures.json').read_bytes()).hexdigest(),
        'rows':rows,'status':'PASS' if len(rows)==12 and all(r['status']=='PASS' for r in rows) else 'FAIL'}
    (root / 'pairing-public.json').write_text(json.dumps(out,indent=2)+'\n')
    print(json.dumps({'pairs':len(rows),'status':out['status']}))


if __name__ == '__main__':
    main()
