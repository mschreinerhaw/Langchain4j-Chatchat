"""Export bounded synthetic report deliveries for independent manual quality review.

Only use with the synthetic fixtures produced by benchmark-coding-progress.py.
This does not treat model-declared COMPLETE or validation PASSED as business truth.
"""
import argparse
import json
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    a = p.parse_args()
    rows = []
    for variant in ['baseline','candidate']:
        for file in sorted((a.root / variant).glob('*-request.json')):
            request = json.loads(file.read_text())
            assert request['instruction'].startswith('这是合成输入的执行基准，无业务数据。')
            receipt_file = file.with_name(file.name.replace('-request.json','-receipt.json'))
            receipt = json.loads(receipt_file.read_text())['receipt'] if receipt_file.exists() else {}
            reports = []
            for item in receipt.get('results',[]):
                text = item.get('reportMarkdown','')
                if len(text) > 24000:
                    raise RuntimeError('Report exceeds bounded review export')
                reports.append({'modelDeclaredStatus':item.get('analysisStatus'),'syntheticReportMarkdown':text,
                    'fieldProvenanceStatuses':sorted({x.get('status','UNKNOWN') for x in item.get('fieldProvenance',[])})})
            visual = receipt.get('visualReport') or {}
            rows.append({'variant':variant,'case':file.name.removesuffix('-request.json'),
                'taskId':receipt.get('taskId'),'status':receipt.get('status','PENDING'),
                'reportStatus':visual.get('status'),'admittedCharts':sum(bool(c.get('artifact')) for c in visual.get('charts',[])),
                'syntheticTables':visual.get('tables',[]),'syntheticMetrics':visual.get('metrics',[]),
                'reports':reports,'independentReview':'REQUIRED'})
    out = {'scope':'SYNTHETIC_FIXTURES_ONLY; report declarations are not independent quality checks','rows':rows}
    (a.root / 'quality-review-public.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'syntheticReports':len(rows)}))


if __name__ == '__main__':
    main()
