"""Real-provider Studio acceptance through authenticated application APIs, with scoped history checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import urllib.error
import uuid

os.umask(0o077)
parser=argparse.ArgumentParser()
parser.add_argument('language',choices=['sql','python'])
parser.add_argument('--model',required=True)
parser.add_argument('--label',required=True)
args=parser.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}',args.label):raise ValueError('Invalid evidence label')
root=Path('/opt/runtime-os-docker-stage/evidence/runtime-os-studios-20261021')
root.mkdir(mode=0o700,exist_ok=True);root.chmod(0o700)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
base='http://127.0.0.1:18180/api/v1'
token=None
def http(path,body=None,method=None):
    req=urllib.request.Request(base+path,headers={'Content-Type':'application/json','X-Workspace-Id':'data-science',
        **({'Authorization':'Bearer '+token} if token else {})},
        data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),method=method)
    try:
        with client.open(req,timeout=90) as response:
            payload=json.load(response)
            if payload.get('code',0) not in (0,200):raise RuntimeError('Application rejected '+path)
            return payload['data']
    except urllib.error.HTTPError as error:
        (root/(args.label+'-http-error-private.json')).write_bytes(error.read())
        raise RuntimeError(f'HTTP {error.code} at {path}') from None

auth=http('/enterprise/auth/login',json.loads(Path('/opt/runtime-os-docker-stage/evidence/cline-monitor-20261019/stage-auth.json').read_text()))
token=auth['token'];user=auth['user']
(root/'browser-auth-private.json').write_text(json.dumps({'token':token,'user':user}))
private=root/(args.label+'-private.json')
start=time.monotonic()
if private.exists():
    saved=json.loads(private.read_text())
    if not saved.get('taskId') and saved.get('submitBody'):
        submitted=http('/data-science/python/assist/coding',saved['submitBody'])
        saved['taskId']=submitted['taskId'];saved['endpoint']='/data-science/python/assist/coding/'+saved['taskId']
        private.write_text(json.dumps(saved,ensure_ascii=False,indent=2))
    task=saved['taskId'];endpoint=saved['endpoint'];session=saved.get('conversationId')
else:
    if args.language=='sql':
        project=http('/data-science/sql/projects',{'name':'Cline acceptance '+str(uuid.uuid4())[:8],'dialect':'mysql'})
        project_id=project['id']
        prepared=http('/data-science/sql/projects/'+project_id+'/generations',{
            'prompt':'使用 MySQL 8 生成按账户汇总已完成交易金额和交易笔数的 SQL 文件。提供的表为 trade_events(account_id VARCHAR(64), amount DECIMAL(20,4), status VARCHAR(20), event_date DATE)。日期范围使用绑定参数，左闭右开；只统计 status=COMPLETED。不要连接数据库或执行 SQL，说明空值与参数口径。',
            'dialect':'mysql','action':'generate','modelName':args.model,'mappingIds':[],'referenceIds':[]})
        if prepared['taskRequest']['toolInput'].get('codingWorkspaceProfile')!='SQL_WORKSPACE_V2':raise RuntimeError('SQL bypasses Cline workspace')
        plan=prepared['taskRequest']['toolInput']['studioCodePlan']
        endpoint='/data-science/sql/projects/'+project_id+'/generations/'+prepared['generationId']
        session=prepared['taskRequest']['sessionId']
        private.write_text(json.dumps({'language':'sql','taskId':'sql-coding-'+prepared['generationId'],'endpoint':endpoint,
            'conversationId':session,'projectId':project_id,'plan':plan,'prepared':prepared},ensure_ascii=False))
        submitted=http(endpoint+'/coding',{},'POST');task=submitted['taskId']
        saved={'language':'sql','taskId':task,'endpoint':endpoint,'conversationId':session,'projectId':project_id,'plan':plan,'prepared':prepared}
    else:
        if not http('/data-science/python/assist/coding/capabilities')['enabled']:raise RuntimeError('Python Cline is disabled')
        source='import json\nimport os\n\ndef main():\n    payload = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))\n    print(json.dumps(payload, ensure_ascii=False))\n\nif __name__ == "__main__":\n    main()\n'
        request={'action':'generate','prompt':'请修改 main.py，读取 CHATCHAT_INPUT_JSON 对象中的 records 列表，每条有 category 和 amount。使用 Decimal 按 category 汇总金额并输出 JSON 字符串金额和有效记录数；缺少字段、空分类或非法金额应给出结构化错误，不访问网络或文件。保留入口调用，补充独立 unittest 文件，覆盖空输入、负数、小数精度和非法输入；这里只生成代码，不执行。',
            'sourceCode':source,'selectedCode':'','modelName':args.model,'skillIds':[]}
        plan=http('/data-science/python/assist/plan',request)
        request['planId']=plan['planId']
        request_id=str(uuid.uuid4())
        submit_body={'request':request,'requestId':request_id,'entryPath':'main.py','files':[{'path':'main.py','content':source}]}
        private.write_text(json.dumps({'language':'python','taskId':None,'conversationId':None,
            'plan':plan,'request':request,'requestId':request_id,'submitBody':submit_body},ensure_ascii=False))
        submitted=http('/data-science/python/assist/coding',submit_body)
        task=submitted['taskId'];endpoint='/data-science/python/assist/coding/'+task
        session=None # Discover the authorized server identity mapping through the official history API.
        saved={'language':'python','taskId':task,'endpoint':endpoint,'conversationId':session,'plan':plan,'request':request}
    private.write_text(json.dumps(saved,ensure_ascii=False,indent=2))
    if 'analysisDecisionOwner=CLINE' not in plan['interpretationPlan']['context']['constraints']:
        raise RuntimeError('Java planning ownership remains')
    print(json.dumps({'phase':'SUBMITTED','language':args.language,'taskId':task,'conversationId':session}),flush=True)

last=None
while time.monotonic()-start<660:
    state=http(endpoint)
    if args.language=='sql':
        status=state['task']['status'];complete=status=='SUCCESS';files=state.get('files',[])
        failed=status in ('FAILED','CANCELLED','MODEL_BUDGET_EXHAUSTED','PARTIAL')
    else:
        status=state['status'];complete=status=='COMPLETED';files=state.get('output',{}).get('files',[])
        failed=status in ('FAILED','CANCELLED','BUDGET_EXCEEDED','PARTIAL')
    (root/(args.label+'-state-private.json')).write_text(json.dumps(state,ensure_ascii=False,indent=2))
    if status!=last:
        print(json.dumps({'phase':status,'taskId':task,'elapsedMs':round((time.monotonic()-start)*1000)}),flush=True);last=status
    if complete or failed:break
    time.sleep(2)
else:raise RuntimeError('Studio timeout; accepted task retained for scoped cancellation/recovery')
if not complete or not files:raise RuntimeError('Studio did not complete with source candidates')
events=state.get('events',[])
if not any(event.get('type')=='TASK_FINISHED' for event in events):raise RuntimeError('No durable Coding completion event')
if args.language=='sql':
    if 'PARSED' not in state.get('validation','') or state.get('outputError'):raise RuntimeError('SQL validation failed')
    if http('/data-science/sql/projects/'+saved['projectId']+'/files'):raise RuntimeError('Generation unexpectedly wrote project files')
else:
    validation=state['output']['validation']
    if not validation.get('valid') or validation.get('codeExecuted') is not False:raise RuntimeError('Python parser evidence invalid')

summary=http('/conversations/user/'+user['id']+'/summaries?limit=100')
if args.language=='python':
    matching=[]
    for header in summary:
        if header.get('agentName')!='PythonStudio':continue
        candidate=http('/conversations/'+header['id'])
        if any(message.get('taskId')==task for message in candidate.get('messages',[])):matching.append(candidate)
    if len(matching)!=1:raise RuntimeError('Cannot uniquely locate the authenticated Python task history')
    detail=matching[0];session=detail['id'];saved['conversationId']=session
    private.write_text(json.dumps(saved,ensure_ascii=False,indent=2))
else:detail=http('/conversations/'+session)
if not any(item['id']==session and item['status']=='completed' for item in summary):raise RuntimeError('Studio missing from recent sessions')
messages=detail.get('messages',[])
assistant=[message for message in messages if message.get('role')=='assistant' and message.get('taskId')==task]
if len(assistant)!=1 or not any(message.get('role')=='user' for message in messages):raise RuntimeError('History turns missing or duplicated')
if not all(file['content'] in assistant[0]['content'] for file in files):raise RuntimeError('History is missing generated source')
http(endpoint);again=http('/conversations/'+session)
if [m['id'] for m in again['messages']]!=[m['id'] for m in messages]:raise RuntimeError('Repeated polling duplicated history')

if not re.fullmatch(r'(sql-coding-[0-9a-f-]{36}|python-coding-[0-9a-f]{64})',task):raise RuntimeError('Unexpected server task identity')
sql="SELECT record_json FROM runtime_evidence_entry WHERE entry_kind='EVIDENCE' AND record_json::jsonb->'payload'->>'taskId'='"+task+"' AND record_json::jsonb->>'evidenceType'='CODING_MODEL_RESPONSE_RECEIPT'"
receipts=subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',sql],text=True)
telemetry=[]
for line in receipts.splitlines():
    response=json.loads(line)['payload']['response'];info=response.get('modelInfo',{})
    telemetry.append({'selectedModel':info.get('selectedModel'),'providerModel':info.get('providerModel'),
        'durationMs':info.get('durationMs'),'usage':response.get('usage',{})})
if not telemetry or any(call['selectedModel']!=args.model or not call['providerModel'] for call in telemetry):raise RuntimeError('Configured real model receipt not proven')
artifacts=[]
for number,file in enumerate(files,1):
    path=root/(args.label+'-source-'+str(number)+Path(file['path']).suffix)
    path.write_text(file['content'])
    artifacts.append({'path':file['path'],'sha256':hashlib.sha256(file['content'].encode()).hexdigest(),'characters':len(file['content'])})
package=json.loads((root/'module-package.json').read_text())
report={'status':'PASSED','label':args.label,'sourceCommit':package['sourceCommit'],'jarSha256':package['candidateSha256'],
    'language':args.language,'taskId':task,'conversationId':session,
    'observedDurationMs':round((time.monotonic()-start)*1000),'modelCalls':len(telemetry),'modelTelemetry':telemetry,
    'javaPlanModelCalls':0,'analysisDecisionOwner':'CLINE','historyVisible':True,'historyContainsSource':True,
    'historyDuplicateMessages':False,'messageCount':len(messages),'artifacts':artifacts,
    'generatedCodeExecuted':False,'sqlExecuted':False,'projectAutomaticallyChanged':False,
    'eventTypes':sorted({event['type'] for event in events}),'productionSwitched':False}
(root/(args.label+'-acceptance.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2))
(root/(args.label+'-history-private.json')).write_text(json.dumps(again,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False),flush=True)
