"""Collect task-control evidence for this acceptance only; never export prompts, code, or credentials."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import urllib.request

os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage')
root=stage/'evidence/runtime-os-studios-20261021';root.chmod(0o700)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
auth=json.loads((root/'browser-auth-private.json').read_text())
def http(path):
    req=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,headers={'Authorization':'Bearer '+auth['token']})
    with client.open(req,timeout=30) as response:return json.load(response)['data']
def query(sql):
    return subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',sql],text=True)
def journal(task):
    if not re.fullmatch(r'(sql-coding-[0-9a-f-]{36}|python-coding-[0-9a-f]{64})',task):raise ValueError('Invalid task ID')
    partition="(SELECT partition_id FROM runtime_evidence_entry WHERE entry_kind='EVIDENCE' AND record_json::jsonb->>'evidenceType'='CODING_WORKSPACE_INPUT' AND record_json::jsonb->'payload'->>'taskId'='"+task+"')"
    body=query("SELECT record_json FROM runtime_evidence_entry WHERE partition_id="+partition+" AND entry_kind='EXEC_CHECKPOINT' AND entry_id='coding.tasks.v1'")
    envelope=json.loads(body)
    if envelope['schemaVersion']=='runtime_execution_checkpoint.v1':value=envelope['value']
    else:
        checksum=envelope['contentSha256']
        if not re.fullmatch('[0-9a-f]{64}',checksum):raise ValueError('Invalid checkpoint checksum')
        chunks=[json.loads(line) for line in query("SELECT record_json FROM runtime_evidence_entry WHERE partition_id="+partition+" AND entry_kind='EXEC_PAYLOAD' AND record_json::jsonb->>'contentSha256'='"+checksum+"'").splitlines()]
        if len(chunks)!=envelope['chunkCount']:raise RuntimeError('Incomplete journal checkpoint')
        value=''.join(chunk['value'] for chunk in sorted(chunks,key=lambda item:item['index']))
        if hashlib.sha256(value.encode()).hexdigest()!=checksum:raise RuntimeError('Journal hash mismatch')
    return json.loads(value)['tasks'][task]

tasks=[]
final_ids=set()
for label in ('sql-verified','python-verified'):
    report=json.loads((root/(label+'-acceptance.json')).read_text())
    if report['status']!='PASSED' or not report['historyContainsSource']:raise RuntimeError('Final acceptance not passed')
    final_ids.add(report['taskId'])
for label in ('sql-final','sql-fixed','python-fixed','sql-verified','python-verified'):
    file=root/(label+'-private.json')
    if not file.exists():continue
    case=json.loads(file.read_text())
    http(case['endpoint']) # Repair the owner-scoped history projection from its immutable task, without re-generation.
    data=journal(case['taskId']);outcome=data['outcome'];contract=data['contract']
    tasks.append({'label':label,'taskId':case['taskId'],'status':outcome['status'],
        'executionControl':outcome['executionControl'],'usage':outcome['usage'],
        'budget':{'maxModelTokens':contract['budget']['maxModelTokens'],'maxModelCalls':contract['budget']['maxModelCalls'],
            'maxCodeExecutions':contract['budget']['maxCodeExecutions']},
        'networkPolicy':contract['authorization']['networkPolicy'],'allowedTools':contract['authorization']['allowedTools']})

# The first Python POST failed after durable acceptance, before the test driver received its ID.
# Recover that one observation from the matching workspace input in the invoking owner's partition.
records=[json.loads(line) for line in query("SELECT record_json FROM runtime_evidence_entry WHERE entry_kind='EVIDENCE' AND record_json::jsonb->>'evidenceType'='CODING_WORKSPACE_INPUT'").splitlines()]
known={task['taskId'] for task in tasks}
legacy=[record for record in records if record['payload']['taskId'].startswith('python-coding-')
    and record['payload']['taskId'] not in known and record['scope']['userId'] in (auth['user']['id'],auth['user']['username'])]
if len(legacy)!=1:raise RuntimeError('Cannot uniquely identify the interrupted first Python acceptance')
task=legacy[0]['payload']['taskId']
observed=http('/data-science/python/assist/coding/'+task);outcome=observed['outcome']
tasks.append({'label':'python-initial-http-500','taskId':task,'status':outcome['status'],
    'executionControl':outcome['executionControl'],'usage':outcome['usage']})
for task in tasks:
    control=task['executionControl']
    if task['status'] in ('CREATED','RUNNING') or not control['executorStopped'] or control['reconciliationRequired']:
        raise RuntimeError('An acceptance task remains unsettled')
for task in tasks:
    if task['taskId'] in final_ids and task['status']!='COMPLETED':raise RuntimeError('Final task is not completed')
request=urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',headers={
    'Authorization':'Bearer '+(stage/'config/coding-node-monitor.token').read_text().strip()})
with client.open(request,timeout=10) as response:node=json.load(response)
if node['sessions']['active']!=0:raise RuntimeError('SDK sessions remain active')
report={'status':'PASSED','activeNodeSessions':0,'finalTasksCompleted':len(final_ids),
    'allAcceptedTestTasksStopped':True,'reconciliationRequired':False,'generatedSourceExecuted':False,
    'oldInterruptedPythonTaskRecovered':True,'productionSwitched':False,'tasks':tasks}
current=json.loads((root/'module-package.json').read_text())
generated=json.loads((root/'before-literal-render-module-package.json').read_text())
if current['modules']!=generated['modules'] or current['apiClassOverlaySha256']!=generated['apiClassOverlaySha256']:
    raise RuntimeError('Execution components changed after the real-model run; rerun generation before acceptance')
report.update({'generationSourceCommit':generated['sourceCommit'],'observedDeploymentCommit':current['sourceCommit'],
    'executionComponentsUnchangedAfterGeneration':True})
(root/'post-validation.json').write_text(json.dumps(report,indent=2))
names=['sql-verified-acceptance.json','python-verified-acceptance.json','post-validation.json',
    'module-manifest.json','module-package.json','deployment.json','parser-image.json','web-files.json',
    'first-candidate-module-package.json','first-candidate-deployment.json',
    'before-source-history-module-package.json','before-source-history-deployment.json','boundary.json',
    'before-literal-render-module-package.json','before-literal-render-deployment.json','studio-history-browser.json']
browser=json.loads((root/'studio-history-browser.json').read_text())
if browser['status']!='PASSED' or browser['httpFixtures'] or browser['historyTriggeredGenerationRequests']:
    raise RuntimeError('Real browser history validation has not passed')
for name in names:
    json.loads((root/name).read_text())
with tarfile.open(root/'sanitized-studio-evidence.tar.gz','w:gz') as archive:
    for name in names:archive.add(root/name,arcname=name,recursive=False)
print(json.dumps({'status':report['status'],'activeNodeSessions':0,'acceptedTasks':len(tasks),'finalTasks':2,
    'archive':str(root/'sanitized-studio-evidence.tar.gz')}),flush=True)
