"""Read-only, per-case archive validation. Never promotes zero exit or an AVAILABLE draft to delivery.

Derived Markdown is for review; original immutable JSONL hash/size remains the archive identity.
Business quality needs independent review, especially the supported subset of E05.
"""
from pathlib import Path
import datetime
import hashlib
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request


def main():
    root=Path(sys.argv[1]).resolve();token=json.loads((root/'auth-private.json').read_text())['token']
    cases=json.loads((root/'model-cases-progress-proof.json').read_text())['cases'];results=[]
    public=root/'public';public.mkdir(exist_ok=True)
    for case in cases:
        name=case['case'];record={'case':name,'taskStatus':case['status'],'readOnly':True,'businessCorrectnessCertified':False}
        results.append(record)
        if case['status'] in ['NOT_OBSERVED','RUNNING','PENDING']:
            record['archiveVerification']='NOT_CHECKED_RUNNING';continue
        try:
            task=json.loads((root/(name+'-journal-private.json')).read_text());reference=task['outcome'].get('resultDatasetRef')
            if not reference:
                record.update(archiveVerification='NOT_PASSED',reason='No accepted resultDatasetRef in terminal task');continue
            assert re.fullmatch('analysis-coding-[a-f0-9]{64}',task['contract']['taskId'])
            outputs=[]
            for m in task['sdkCheckpoint']['messages']:
                for part in m.get('content',[]) if isinstance(m.get('content'),list) else []:
                    if part.get('type')=='tool-result' and isinstance(part.get('output'),dict):outputs.append(part['output'])
            actual=next(x for x in outputs if x.get('resultDatasetRef')==reference)
            operation=actual['operationId'];assert re.fullmatch('cline-[a-f0-9]{64}',operation)
            assert actual['executorStopped'] is True and actual['exitCode']==0
            query="select row_to_json(t) from runtime_workspace_file t where task_id='"+task['contract']['taskId']+"' and state='AVAILABLE'"
            files=[json.loads(line) for line in subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',query],text=True).splitlines()]
            matches=[f for f in files if f['source_ref']=='execution:'+operation+':results:rows.jsonl'];assert len(matches)==1
            f=matches[0];assert re.fullmatch('[a-f0-9]{64}',f['id'])
            request=urllib.request.Request('http://127.0.0.1:18180/api/v1/data-science/user-assets/'+f['id']+'/content',headers={'Authorization':'Bearer '+token})
            with urllib.request.urlopen(request,timeout=60) as response:raw=response.read();record['httpStatus']=response.status
            assert len(raw)==f['size_bytes'] and hashlib.sha256(raw).hexdigest()==f['sha256']
            rows=[json.loads(line) for line in raw.splitlines() if line.strip()];assert len(rows)==1
            row=rows[0];assert row['reportMarkdown'].strip() and row['evidenceReferences'] and isinstance(row['limitations'],list)
            reviews=[v for v in outputs if v.get('reference')==reference and 'reportReferenceValidation' in v]
            reviews += [v['reviewedArtifact'] for v in outputs if isinstance(v.get('reviewedArtifact'),dict) and v['reviewedArtifact'].get('reference')==reference]
            assert reviews and reviews[-1]['reportReferenceValidation']['valid'] is True
            (root/(name+'-accepted-report-private.jsonl')).write_bytes(raw)
            (public/(name+'-report.md')).write_text(row['reportMarkdown'])
            record.update(archiveVerification='PASSED',taskId=task['contract']['taskId'],operationId=operation,
                reportFileId=f['id'],reportSha256=f['sha256'],reportBytes=len(raw),reportRows=1,
                analysisStatus=row.get('analysisStatus'),evidenceReferenceCount=len(row['evidenceReferences']),
                limitationCount=len(row['limitations']),referenceValidation=True,executorStopped=True,exitCode=0,
                expectedLimitedStatus=row.get('analysisStatus') in ['PARTIAL','INPUT_REQUIRED','UNAVAILABLE'],
                originalBusinessObservationCount=case['businessObservationCount'])
        except Exception as failure:
            record.update(archiveVerification='NOT_PASSED',errorType=type(failure).__name__,reason=str(failure)[:1000])
            if isinstance(failure,urllib.error.HTTPError):record['httpStatus']=failure.code
    proof={'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'level':'FILE_ONLY_NATIVE_REPORTING',
        'cases':results,'rawSourceBodiesOrCredentialsPublished':False,'acceptanceIsNotTheCollectorExitCode':True}
    (public/'model-report-archive-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2))
    print(json.dumps([{'case':x['case'],'archive':x['archiveVerification'],'analysisStatus':x.get('analysisStatus')} for x in results]))


if __name__=='__main__':main()
