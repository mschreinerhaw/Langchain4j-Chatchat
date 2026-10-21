"""Project durable task/SDK acceptance state; never export prompts, code, tokens or storage keys."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

os.umask(0o077)
STAGE=Path('/opt/runtime-os-docker-stage');ROOT=STAGE/'evidence/user-assets-silo-20261021'
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
headers={'Authorization':'Bearer '+(STAGE/'config/coding-node-monitor.token').read_text().strip()}
def get(path):
    with client.open(urllib.request.Request('http://127.0.0.1:18100/v1/monitor'+path,headers=headers),timeout=10) as response:return json.load(response)
def sql(query):return subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At','-v','ON_ERROR_STOP=1','-d','runtime_os_acceptance_api','-c',query],text=True).strip()
def journal(task):
    if not re.fullmatch('analysis-coding-[0-9a-f]{64}',task):raise ValueError('Invalid task ID')
    partitions=sql("SELECT DISTINCT partition_id FROM runtime_evidence_entry WHERE entry_kind='EVIDENCE' AND record_json::jsonb->>'evidenceType'='CODING_INPUT_SNAPSHOT' AND record_json::jsonb->'payload'->>'taskId'='"+task+"'").splitlines()
    if len(partitions)!=1 or not re.fullmatch('[0-9a-f]{64}',partitions[0]):raise RuntimeError('Task partition not unique')
    partition=partitions[0]
    envelope=json.loads(sql("SELECT record_json FROM runtime_evidence_entry WHERE partition_id='"+partition+"' AND entry_kind='EXEC_CHECKPOINT' AND entry_id='coding.tasks.v1'"))
    if envelope['schemaVersion']=='runtime_execution_checkpoint.v1':value=envelope['value']
    else:
        checksum=envelope['contentSha256']
        if not re.fullmatch('[0-9a-f]{64}',checksum):raise RuntimeError('Invalid checkpoint checksum')
        chunks=[json.loads(line) for line in sql("SELECT record_json FROM runtime_evidence_entry WHERE partition_id='"+partition+"' AND entry_kind='EXEC_PAYLOAD' AND record_json::jsonb->>'contentSha256'='"+checksum+"'").splitlines()]
        if len(chunks)!=envelope['chunkCount']:raise RuntimeError('Incomplete task journal')
        value=''.join(chunk['value'] for chunk in sorted(chunks,key=lambda item:item['index']))
        if hashlib.sha256(value.encode()).hexdigest()!=checksum:raise RuntimeError('Task journal hash mismatch')
    return json.loads(value)['tasks'][task]

tasks=[]
for item in get('/tasks?q=asset-analysis-&limit=100')['items']:
    data=journal(item['taskId']);outcome=data['outcome'];detail=get('/tasks/'+item['id'])['task']
    tasks.append({'taskId':item['taskId'],'sdkStatus':item['status'],'sdkError':item.get('error'),
        'outcome':{key:outcome.get(key) for key in ('status','reasonCode','summary','cancellationRequested','executionControl','usage','validationStatus')},
        'models':detail.get('models',[]),'codeCount':item['codeCount']})
node=get('/node')
python=subprocess.check_output(['docker','ps','--filter','name=chatchat-py-','--format','{{.Names}}'],text=True).splitlines()
report={'activeNodeSessions':node['sessions']['active'],'activePythonContainers':python,'tasks':tasks,
    'unsettledTaskIds':[task['taskId'] for task in tasks if task['outcome']['status'] in ('CREATED','RUNNING') or task['outcome']['executionControl']['reconciliationRequired']]}
(ROOT/'task-control-summary.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
