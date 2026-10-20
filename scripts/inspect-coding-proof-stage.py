"""Inspect integrity metadata for one acceptance task, without printing model content."""
import hashlib
import json
import pathlib
import subprocess

root = pathlib.Path('/opt/runtime-os-docker-stage/evidence/runtime-os-refactor-20261020')
case = json.loads((root/'scenario2-before.json').read_text())
task = case['tasks'][0]['taskId']
sql = ("SELECT record_json FROM runtime_evidence_entry WHERE entry_kind='EVIDENCE' "
       "AND record_json::jsonb->'payload'->>'taskId'='"+task+"' "
       "AND record_json::jsonb->>'evidenceType'='CODING_MODEL_RESPONSE_RECEIPT'")
output = subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At',
    '-d','runtime_os_acceptance_api','-c',sql], text=True)
for line in output.splitlines():
    record = json.loads(line)
    payload = record['payload']
    body = json.dumps(payload,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
    print(json.dumps({'operation':payload.get('operationId'), 'handle':payload.get('executionHandle'),
        'evidenceType':record.get('evidenceType'),'canonicalPythonHashMatches':hashlib.sha256(body).hexdigest()==record['contentSha256'],
        'storedHash':record['contentSha256'],'scopeRun':record['scope']['runId']},ensure_ascii=False))
    (root/('proof-'+payload['operationId']+'.json')).write_text(json.dumps(record,ensure_ascii=False))
logs = subprocess.run(['docker','logs','--since','45m','runtime-os-app-stage-api-1'],capture_output=True,text=True)
lines = (logs.stdout+'\n'+logs.stderr).splitlines()
for index,line in enumerate(lines):
    if 'No verified execution-node stop confirmation' in line:
        print('STOP_PROOF_REJECTED')
        for frame in lines[index+1:index+24]:
            if frame.lstrip().startswith('at com.chatchat.'):
                print(frame.strip())
