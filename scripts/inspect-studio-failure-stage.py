"""Show bounded Studio lifecycle diagnostics, excluding inputs and model messages."""
import json
from pathlib import Path
import subprocess
root=Path('/opt/runtime-os-docker-stage/evidence/runtime-os-studios-20261021')
sql="SELECT record_json FROM runtime_evidence_entry WHERE entry_kind='EVIDENCE' AND record_json::jsonb->>'evidenceType'='CODING_WORKSPACE_INPUT'"
records=subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',sql],text=True)
for line in records.splitlines():
    record=json.loads(line);payload=record['payload']
    print(json.dumps({'workspaceTask':payload['taskId'],'dialect':payload['dialect']}))
for path in root.glob('*-state-private.json'):
    data=json.loads(path.read_text())
    print(json.dumps({'case':path.name,'task':{k:v for k,v in data.get('task',data.get('outcome',{})).items()
        if k in ('status','errorMessage','failureReason','errorCode','answerSummary','reasonCode','summary','usage','executionControl')},
        'events':[{'type':e.get('type'),'status':e.get('status'),'payload':e.get('payload'),'summary':e.get('summary')} for e in data.get('events',[])]},ensure_ascii=False))
for path in root.glob('*-http-error-private.json'):
    data=json.loads(path.read_text());print(json.dumps({'case':path.name,'code':data.get('code'),'message':data.get('message')},ensure_ascii=False))
logs=subprocess.run(['docker','logs','--since','5m','runtime-os-app-stage-api-1'],text=True,capture_output=True)
lines=(logs.stdout+'\n'+logs.stderr).splitlines()
for n,line in enumerate(lines):
    if 'Unexpected error' in line or 'Unhandled' in line or 'Exception:' in line or 'MODEL_BUDGET' in line:
        if any(x in line.lower() for x in ('apikey','api-key','authorization','bearer','baseurl')):continue
        print(line[:600])
        for frame in lines[n+1:n+12]:
            if frame.lstrip().startswith('at com.chatchat.'):print(frame.strip())
