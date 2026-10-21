"""Real published-Agent-token upload, owner isolation and Cline/Python attachment acceptance.

Run on the authorized stage host. Secrets and full HTTP failures remain in root-only evidence.
"""
import hashlib,json,os,secrets,subprocess,time,urllib.error,urllib.request,uuid
from decimal import Decimal
from pathlib import Path

os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage');root=stage/'evidence/agent-attachments-20261021';root.mkdir(mode=0o700,exist_ok=True)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}));agent='financial_data_analysis'
base='/published-agents/'+agent

def call(path,body=None,token=None,method=None,content_type='application/json',raw=False):
    data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=data,method=method,
        headers={'Content-Type':content_type,**({'Authorization':'Bearer '+token} if token else {})})
    try:
        with client.open(request,timeout=40) as response:
            data=response.read()
            if raw:return response.status,data
            result=json.loads(data)
            if result.get('code',0) not in (0,200,202):raise RuntimeError('Application rejection at '+path)
            return result.get('data')
    except urllib.error.HTTPError as error:
        data=error.read()
        if raw:return error.code,data
        (root/'http-error-private.txt').write_bytes(data)
        raise RuntimeError('HTTP '+str(error.code)+' at '+path) from None

def upload(token,name,data,path=None,raw=False):
    boundary=uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\n').encode()+data+(f'\r\n--{boundary}--\r\n').encode()
    return call(path or base+'/attachments',body,token,content_type='multipart/form-data; boundary='+boundary,raw=raw)

auth=call('/enterprise/auth/login',json.loads((stage/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
admin=auth['token'];(root/'browser-auth-private.json').write_text(json.dumps(auth))
role=user=other_session=own=other_file=task=None;issued=[];checks={};completed=False
try:
    issued_admin=call('/enterprise/agent-api-tokens',{'userId':auth['user']['id'],'tokenName':'Attachment stage acceptance','permanent':False,'expiresInSeconds':3600},admin)
    issued.append(issued_admin['token']['id']);api=issued_admin['secret']
    example=call(base+'/curl-example',token=admin)
    subprocess.run(['bash','-n'],input=example['completeExample'],text=True,check=True)
    checks['generatedCurlShellSyntax']=True
    checks['generatedCurlAttachmentExample']='attachmentIds' in example['completeExample'] and '/attachments' in example['attachmentUploadCurl']
    permissions=call('/enterprise/permissions',token=admin)
    ids=[p['id'] for p in permissions if p['permissionCode'] in ('platform:agents:published','workspace:chat:interact','account:self:read')]
    if len(ids)!=3:raise RuntimeError('Required existing permission policy missing')
    role=call('/enterprise/roles',{'tenantId':auth['user']['tenantId'],'roleCode':'AGENT_FILES_'+uuid.uuid4().hex[:10],
        'roleName':'Temporary Agent file acceptance','roleType':'business','status':'enabled'},admin)
    call('/enterprise/roles/'+role['id']+'/authorization',{'permissionIds':ids,'orgScopes':[],'userIds':[],'agentIds':[agent]},admin,'PUT')
    username='agent_files_'+uuid.uuid4().hex[:10];password=secrets.token_urlsafe(24)
    user=call('/enterprise/users',{'user':{'tenantId':auth['user']['tenantId'],'username':username,'passwordHash':password,
        'displayName':'Temporary Agent attachment acceptance','status':'enabled'},'roleIds':[role['id']]},admin)
    other_session=call('/enterprise/auth/login',{'username':username,'password':password})['token']
    issued_other=call('/enterprise/agent-api-tokens',{'userId':user['id'],'tokenName':'Attachment isolation acceptance','permanent':False,'expiresInSeconds':3600},admin)
    issued.append(issued_other['token']['id']);other=issued_other['secret']
    first=Decimal(secrets.randbelow(9000)+100)/100;second=Decimal(secrets.randbelow(9000)+100)/100
    expected=str(first+second)
    original=f'category,amount,currency\nalpha,{first:.2f},CNY\nbeta,{second:.2f},CNY\n'.encode()
    own=upload(api,'agent-attachment-'+uuid.uuid4().hex[:10]+'.csv',original)
    other_file=upload(other,'isolation.md',b'# Another owner\n')
    checks['tokenUpload']=own['state']=='AVAILABLE' and own['sha256']==hashlib.sha256(original).hexdigest()
    checks['otherTokenUpload']=other_file['state']=='AVAILABLE'
    checks['exactOriginal']=call('/interactions/user-assets/'+own['id']+'/content',token=admin,raw=True)==(200,original)
    checks['tokenCannotUseGenericUserSpace']=call('/interactions/user-assets',token=api,raw=True)[0]==401
    checks['tokenCannotManageTokens']=call('/enterprise/agent-api-tokens',token=api,raw=True)[0]==401
    checks['anonymousUploadDenied']=call(base+'/attachments',body=b'',method='POST',raw=True)[0]==401
    checks['foreignAttachmentRejected']=call(base+'/questions',{'question':'Analyze','attachmentIds':[own['id']]},other,raw=True)[0]==404
    checks['duplicateAttachmentsRejected']=call(base+'/questions',{'question':'Analyze','attachmentIds':[own['id'],own['id']]},api,raw=True)[0]==400
    checks['unpublishedAgentRejected']=upload(api,'unknown.md',b'test','/published-agents/missing_agent/attachments',raw=True)[0]==404
    checks['unsupportedFileRejected']=upload(api,'blocked.exe',b'test',raw=True)[0]==400
    if not all(checks.values()):raise RuntimeError('Attachment permission check failed')
    question={'question':'请在 Python 沙箱中读取附件 CSV，用 Decimal 按 currency 精确汇总 amount，报告实际行数和合计。核验原件哈希，不要把传输记录当业务数据。',
        'sessionId':str(uuid.uuid4()),'idempotencyKey':'attachment-'+uuid.uuid4().hex,'attachmentIds':[own['id']]}
    task=call(base+'/questions',question,api)
    (root/'submission.json').write_text(json.dumps(task,indent=2));print(json.dumps({'stage':'submitted','taskId':task['taskId']}),flush=True)
    replay=call(base+'/questions',question,api);checks['idempotentQuestion']=replay['taskId']==task['taskId']
    deadline=time.monotonic()+900;last=None;cursor=0
    while time.monotonic()<deadline:
        state=call(base+'/questions/'+task['taskId']+'/status?afterSequence='+str(cursor)+'&eventLimit=100',token=api)
        cursor=state.get('eventCursor',cursor)
        if state['canonicalState']!=last:
            print(json.dumps({'stage':'status','state':state['canonicalState']}),flush=True);last=state['canonicalState']
        if state['terminal']:break
        time.sleep(3)
    else:raise RuntimeError('Published Agent attachment timeout; task retained for inspection')
    answer=call(base+'/questions/'+task['taskId']+'/answer',token=api)
    (root/'answer-private.json').write_text(json.dumps(answer,ensure_ascii=False,indent=2))
    checks['realAnswer']=answer['ready'] and expected in answer['answer'] and 'CNY' in answer['answer']
    checks['agentTerminal']=state['canonicalState']=='SUCCEEDED'
    checks['terminalCancelIdempotent']=call(base+'/questions/'+task['taskId'],token=api,method='DELETE')['canonicalState']=='SUCCEEDED'
    attempt=task['attemptId']
    if not all(c.isalnum() or c in '-_' for c in attempt):raise RuntimeError('Invalid attempt identity')
    native=subprocess.check_output(['docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',
        "select distinct task_id from runtime_workspace_file where run_id='"+attempt+"' and task_id<>''"],text=True).strip().splitlines()
    checks['taskArchivesPresent']=len(native)==1
    objects=[];code=[]
    if native:
        files=call('/interactions/user-assets?taskId='+native[0]+'&limit=100',token=admin)
        for file in files:
            status,body=call('/interactions/user-assets/'+file['id']+'/content',token=admin,raw=True)
            if status!=200 or len(body)!=file['size'] or hashlib.sha256(body).hexdigest()!=file['sha256']:raise RuntimeError('Task archive integrity failure')
            objects.append({'id':file['id'],'kind':file['kind'],'sha256':file['sha256']})
            if file['kind']=='CODE':code.append(body.decode())
        checks['taskArchiveIntegrity']=len(objects)>5
        checks['dynamicCsvPythonCode']=bool(code) and any('Decimal' in text and 'csv' in text for text in code)
    completed=all(checks.values())
    if not completed:raise RuntimeError('Real attachment analysis failed')
finally:
    cleanup=[]
    # Fact archives of the real task are retained; only unused test inputs/temporary identities are removed.
    if other_file and other_session:cleanup.append(call('/interactions/user-assets/'+other_file['id'],token=other_session,method='DELETE',raw=True)[0]==200)
    for token_id in issued:cleanup.append(call('/enterprise/agent-api-tokens/'+token_id+'/revoke',{},admin,method='POST',raw=True)[0]==200)
    if user:cleanup.append(call('/enterprise/users/'+user['id'],token=admin,method='DELETE',raw=True)[0]==200)
    if role:cleanup.append(call('/enterprise/roles/'+role['id'],token=admin,method='DELETE',raw=True)[0]==200)
    report={'status':'PASSED' if completed and all(cleanup) else 'FAILED','checks':checks,'temporaryCredentialsRevoked':all(cleanup),
        'taskId':None if task is None else task['taskId'],'nativeTasks':locals().get('native',[]),'expectedTotal':locals().get('expected'),
        'archives':locals().get('objects',[])}
    (root/'acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
