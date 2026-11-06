"""Independent, read-only archive check for the existing native file-analysis API.

Supports actual Python reports and governed text-only limited reports. No report
is generated or rewritten; business quality needs a separate review.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import urllib.request


def main():
    root=Path(sys.argv[1]).resolve();kind=sys.argv[2]
    assert kind in ['day','search']
    prefix='autonomous-'+kind
    task=json.loads((root/(prefix+'-journal-private.json')).read_text())
    proof={'taskId':task['contract']['taskId'],'status':task['outcome']['status'],
           'readOnly':True,'businessCorrectnessCertified':False}
    response=root/(prefix+'-response-private.json')
    if not response.exists() or task['outcome']['status'] not in ['COMPLETED','PARTIAL']:
        proof['archiveVerification']='NOT_PASSED';proof['reason']='No final successful native receipt; inspect preserved failure and partial artifacts'
    else:
        receipt=json.loads(response.read_text())['receipt'];reference=task['outcome']['resultDatasetRef']
        results=[]
        for message in task['sdkCheckpoint']['messages']:
            if not isinstance(message.get('content'),list):continue
            for part in message['content']:
                value=part.get('output')
                if part.get('type')=='tool-result' and isinstance(value,dict):results.append(value)
        actual=next(value for value in results if value.get('resultDatasetRef')==reference)
        operation=actual.get('operationId')
        assert re.fullmatch('(cline|workspace)-[a-f0-9]{64}',operation)
        python=operation.startswith('cline-')
        if python:assert actual['executorStopped'] is True and actual['exitCode']==0
        identity=task['contract']['taskId'];assert re.fullmatch('analysis-coding-[a-f0-9]{64}',identity)
        query="select row_to_json(t) from runtime_workspace_file t where task_id='"+identity+"' and state='AVAILABLE'"
        rows=[json.loads(line) for line in subprocess.check_output(['docker','exec','-u','postgres','postgres',
            'psql','-At','-d','runtime_os_acceptance_api','-c',query],text=True).splitlines()]
        source=('execution:'+operation+':results:' if python else 'text-result:'+operation+':')+'rows.jsonl'
        matches=[row for row in rows if row['source_ref']==source];assert len(matches)==1
        archived=matches[0];assert re.fullmatch('[a-f0-9]{64}',archived['id'])
        token=json.loads((root/'auth-private.json').read_text())['token']
        request=urllib.request.Request('http://127.0.0.1:18180/api/v1/data-science/user-assets/'+archived['id']+'/content',
            headers={'Authorization':'Bearer '+token})
        with urllib.request.urlopen(request,timeout=30) as stream:raw=stream.read()
        assert hashlib.sha256(raw).hexdigest()==archived['sha256'] and len(raw)==archived['size_bytes']
        parsed=[json.loads(line) for line in raw.splitlines() if line.strip()]
        assert len(parsed)==1 and parsed==receipt['results']
        row=parsed[0];assert row['reportMarkdown'].strip() and row['evidenceReferences'] and isinstance(row['limitations'],list)
        if python:
            reviews=[value for value in results if value.get('reference')==reference and 'reportReferenceValidation' in value]
            reviews+=[value['reviewedArtifact'] for value in results if isinstance(value.get('reviewedArtifact'),dict)
                      and value['reviewedArtifact'].get('reference')==reference]
            assert reviews and reviews[-1]['reportReferenceValidation']['valid'] is True
        (root/(prefix+'-accepted-report-private.json')).write_bytes(raw)
        proof.update(archiveVerification='PASSED',operationId=operation,reportSha256=archived['sha256'],reportBytes=len(raw),
                     reportRows=1,apiResultsMatchArchive=True,executionMode='PYTHON' if python else 'TEXT_ONLY',
                     analysisStatus=row.get('analysisStatus'),evidenceReferenceCount=len(row['evidenceReferences']),
                     limitationCount=len(row['limitations']),markdownImageReferences=re.findall(r'!\[[^\]]*\]\(([^)]+)\)',row['reportMarkdown']))
    (root/'native-report-archive-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2))
    print(json.dumps(proof,ensure_ascii=False))


if __name__=='__main__':main()
