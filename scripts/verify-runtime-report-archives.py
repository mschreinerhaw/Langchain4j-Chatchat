"""Read-only verification of the current real-scenario reports and referenced archives.

Run after verify-runtime-data-scenarios.py collect, on the authorized deployment host.
Private report bytes remain in the release directory. Public proof contains hashes,
counts, bindings and presentation checks; it does not certify business conclusions.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import urllib.request


def main():
    root=Path(sys.argv[1]).resolve()
    token=json.loads((root/'auth-private.json').read_text())['token']
    results=json.loads((root/'real-scenario-results.json').read_text())
    proof=[]
    def content(identity):
        assert re.fullmatch('[a-f0-9]{64}',identity)
        request=urllib.request.Request('http://127.0.0.1:18180/api/v1/data-science/user-assets/'+identity+'/content',
            headers={'Authorization':'Bearer '+token})
        with urllib.request.urlopen(request,timeout=30) as response:return response.read()
    for case in results['cases']:
        row={'case':case['case'],'status':case['status'],'resultAvailable':case['resultAvailable'],
             'businessCorrectnessCertified':False,'charts':[],'checks':{}}
        state=json.loads((root/(case['case']+'-status-private.json')).read_text())
        node=next(n for n in state['nodes'] if n['id']==case['nodeId'])
        report=node.get('receipt',{}).get('visualReport') or {}
        if not report:
            row['checks']['acceptedVisualReportPresent']=False;proof.append(row);continue
        task=report.get('execution',{}).get('taskId')
        operation=report.get('execution',{}).get('operationId')
        assert re.fullmatch('analysis-coding-[a-f0-9]{64}',task or '')
        assert re.fullmatch('cline-[a-f0-9]{64}',operation or '')
        query="select row_to_json(t) from runtime_workspace_file t where task_id='"+task+"' and state='AVAILABLE'"
        records=[json.loads(line) for line in subprocess.check_output(['docker','exec','-u','postgres','postgres',
            'psql','-At','-d','runtime_os_acceptance_api','-c',query],text=True).splitlines()]
        matches=[r for r in records if r['source_ref']=='execution:'+operation+':results:rows.jsonl']
        assert len(matches)==1,'Exactly one immutable archived report required'
        stored=matches[0];raw=content(stored['id'])
        assert hashlib.sha256(raw).hexdigest()==stored['sha256']==report['execution']['resultSha256']
        assert len(raw)==stored['size_bytes']
        parsed=[json.loads(line) for line in raw.splitlines() if line.strip()]
        assert len(parsed)==1
        (root/(case['case']+'-accepted-report-private.json')).write_bytes(raw)
        assert report['execution']['executorStopped'] is True
        row.update(taskId=task,operationId=operation,reportFileId=stored['id'],reportSha256=stored['sha256'],reportBytes=len(raw),
            requirementStatus=parsed[0].get('analysisStatus'),deliveryStatus=report.get('status'),
            artifactValidationStatus=report.get('artifactValidationStatus'),chartValidationStatus=report.get('chartValidationStatus'))
        row['checks'].update(acceptedVisualReportPresent=True,reportHashVerified=True,reportRowCountVerified=True,executorStopBound=True)
        chart_ids={c['id'] for c in report.get('charts',[])}
        for section in report.get('sections',[]):assert set(section.get('chartIds',[]))<=chart_ids
        row['checks']['sectionChartReferencesValid']=True
        for chart in report.get('charts',[]):
            ref=chart.get('artifact')
            if not ref:
                row['charts'].append({'id':chart['id'],'verified':False,'reason':'NO_ACCEPTED_ARTIFACT'});continue
            raw_chart=content(ref['fileId'])
            assert hashlib.sha256(raw_chart).hexdigest()==ref['sha256'] and len(raw_chart)==ref['size']
            summary={'id':chart['id'],'verified':True,'fileId':ref['fileId'],'sha256':ref['sha256'],'bytes':len(raw_chart),'kind':chart['kind']}
            if chart['kind']=='plotly':
                figure=json.loads(raw_chart);assert isinstance(figure['data'],list) and isinstance(figure.get('layout',{}),dict)
                series=[]
                for trace in figure['data']:
                    x,y=trace.get('x'),trace.get('y')
                    if isinstance(x,list) and isinstance(y,list):
                        assert len(x)==len(y)
                        series.append({'points':len(x),'uniqueX':len(set(map(str,x))),
                            'duplicateXRequiresBusinessReview':len(set(map(str,x)))<len(x)})
                summary['series']=series
            row['charts'].append(summary)
        row['checks']['allReferencedChartsArchived']=all(c['verified'] for c in row['charts'])
        proof.append(row)
    (root/'report-archive-proof.json').write_text(json.dumps({'readOnly':True,'cases':proof},ensure_ascii=False,indent=2))
    print(json.dumps([{'case':r['case'],'status':r['status'],'requirementStatus':r.get('requirementStatus'),
        'chartCount':len(r['charts']),'checks':r['checks']} for r in proof],ensure_ascii=False))


if __name__=='__main__':main()
