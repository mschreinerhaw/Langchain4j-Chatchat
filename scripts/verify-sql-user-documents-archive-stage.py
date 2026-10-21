"""Verify the actual browser task's immutable originals, proposal archives and stopped executor.
Run with a public browser-acceptance.json on the authorized stage host.
"""
import hashlib,json,os,re,subprocess,sys,urllib.request
from pathlib import Path
os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage');root=stage/'evidence/sql-studio-user-documents-20261021';root.mkdir(mode=0o700,exist_ok=True)
report=json.loads(Path(sys.argv[1]).read_text());task=report['taskId']
if not re.fullmatch(r'sql-coding-[0-9a-f-]{36}',task):raise RuntimeError('Invalid SQL task ID')
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
auth=json.loads((root/'browser-auth-private.json').read_text())
def get(path,raw=False):
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,headers={'Authorization':'Bearer '+auth['token'],'X-Workspace-Id':'data-science'})
    with client.open(request,timeout=30) as response:return response.read() if raw else json.load(response)['data']
files=get('/data-science/user-assets?taskId='+task+'&limit=100');objects=[]
for file in files:
    data=get('/data-science/user-assets/'+file['id']+'/content',True)
    if len(data)!=file['size'] or hashlib.sha256(data).hexdigest()!=file['sha256']:raise RuntimeError('Archive integrity failure')
    objects.append({'id':file['id'],'kind':file['kind'],'sourceRef':file['sourceRef'],'sha256':file['sha256'],'size':file['size']})
for original in report['uploads']:
    expected='sql-reference:'+original['id']+':'+original['sha256']
    matching=[file for file in objects if file['sourceRef']==expected]
    if len(matching)!=1 or matching[0]['sha256']!=original['sha256']:raise RuntimeError('Missing exact task-bound original')
view=get('/data-science/sql/projects/'+report['projectId']+'/generations/'+report['generationId'])
if view['task']['status']!='SUCCESS' or view['generation']['applied'] or not view['validation'].endswith('SQL_NOT_EXECUTED'):raise RuntimeError('SQL generation not ready for review')
answer=json.loads(view['rawAnswer'])
if not all(file['fileName'] in answer['notes'] for file in report['uploads']):raise RuntimeError('Proposal notes omit document filenames')
frozen=view['generation']['prepared']['taskRequest']['toolInput']['sqlUserDocuments']
if not all(any(item['fileId']==file['id'] and item['sha256']==file['sha256'] for item in frozen) for file in report['uploads']):raise RuntimeError('Task document provenance differs from originals')
def sql(query):return subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At','-v','ON_ERROR_STOP=1','-d','runtime_os_acceptance_api','-c',query],text=True).strip()
partitions=sql("select distinct partition_id from runtime_evidence_entry where entry_kind='EVIDENCE' and record_json::jsonb->>'evidenceType'='CODING_WORKSPACE_INPUT' and record_json::jsonb->'payload'->>'taskId'='"+task+"'").splitlines()
if len(partitions)!=1 or not re.fullmatch('[a-f0-9]{64}',partitions[0]):raise RuntimeError('SQL task partition is not unique')
envelope=json.loads(sql("select record_json from runtime_evidence_entry where partition_id='"+partitions[0]+"' and entry_kind='EXEC_CHECKPOINT' and entry_id='coding.tasks.v1'"))
if envelope['schemaVersion']=='runtime_execution_checkpoint.v1':value=envelope['value']
else:
    checksum=envelope['contentSha256']
    if not re.fullmatch('[a-f0-9]{64}',checksum):raise RuntimeError('Invalid checkpoint digest')
    chunks=[json.loads(line) for line in sql("select record_json from runtime_evidence_entry where partition_id='"+partitions[0]+"' and entry_kind='EXEC_PAYLOAD' and record_json::jsonb->>'contentSha256'='"+checksum+"'").splitlines()]
    if len(chunks)!=envelope['chunkCount']:raise RuntimeError('Checkpoint incomplete')
    value=''.join(chunk['value'] for chunk in sorted(chunks,key=lambda item:item['index']))
    if hashlib.sha256(value.encode()).hexdigest()!=checksum:raise RuntimeError('Checkpoint changed')
outcome=json.loads(value)['tasks'][task]['outcome']
if outcome['status']!='COMPLETED' or not outcome['executionControl']['executorStopped'] or outcome['executionControl']['reconciliationRequired']:raise RuntimeError('SQL execution not closed')
operator=(stage/'config/coding-node-monitor.token').read_text().strip()
with client.open(urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',headers={'Authorization':'Bearer '+operator}),timeout=10) as response:active=json.load(response)['sessions']['active']
python=subprocess.check_output(['docker','ps','--filter','name=chatchat-py-','--format','{{.Names}}'],text=True).splitlines()
result={'status':'PASSED','taskId':task,'archiveCount':len(objects),'archives':objects,'exactOriginalsRetained':True,'taskSnapshotHashesMatch':True,'notesCiteFilenames':True,
    'modelQuotedHashesMatch':all(file['sha256'] in answer['notes'] for file in report['uploads']),
    'outcome':{key:outcome.get(key) for key in ('status','executionControl','usage','validationStatus')},'activeNodeSessions':active,'activePythonContainers':python}
(root/'archive-acceptance.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
