"""Live chat-only permission and owner isolation acceptance. No model invocation."""
import json,os,secrets,urllib.error,urllib.request,uuid
from pathlib import Path
os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage');root=stage/'evidence/chat-pasted-assets-20261021';root.mkdir(mode=0o700,exist_ok=True)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None,token=None,method=None,content_type='application/json',raw=False):
    data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
    req=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=data,method=method,headers={'Content-Type':content_type,**({'Authorization':'Bearer '+token} if token else {})})
    try:
        with client.open(req,timeout=30) as response:
            data=response.read()
            if raw:return response.status,data
            result=json.loads(data)
            if result.get('code',0) not in (0,200):raise RuntimeError('Application rejection at '+path)
            return result.get('data')
    except urllib.error.HTTPError as error:
        if raw:return error.code,error.read()
        (root/'http-error-private.txt').write_bytes(error.read());raise RuntimeError('HTTP '+str(error.code)+' at '+path) from None
def upload(token,name,content):
    boundary=uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\n').encode()+content+(f'\r\n--{boundary}--\r\n').encode()
    return call('/interactions/user-assets',body,token,content_type='multipart/form-data; boundary='+boundary)
auth=call('/enterprise/auth/login',json.loads((stage/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
token=auth['token'];(root/'browser-auth-private.json').write_text(json.dumps(auth))
role=None;user=None;other=None;own=None;foreign=None;completed=False;checks={}
try:
    permissions=call('/enterprise/permissions',token=token)
    ids=[p['id'] for p in permissions if p['permissionCode'] in ('workspace:chat:interact','account:self:read')]
    if len(ids)!=2:raise RuntimeError('Existing chat policy missing')
    role=call('/enterprise/roles',{'tenantId':auth['user']['tenantId'],'roleCode':'CHAT_FILES_'+uuid.uuid4().hex[:10],
        'roleName':'Temporary chat attachment acceptance','roleType':'business','status':'enabled'},token)
    call('/enterprise/roles/'+role['id']+'/authorization',{'permissionIds':ids,'orgScopes':[],'userIds':[],'agentIds':[]},token,'PUT')
    username='chat_files_'+uuid.uuid4().hex[:10];password=secrets.token_urlsafe(24)
    user=call('/enterprise/users',{'user':{'tenantId':auth['user']['tenantId'],'username':username,'passwordHash':password,'displayName':'Temporary chat files acceptance','status':'enabled'},'roleIds':[role['id']]},token)
    other=call('/enterprise/auth/login',{'username':username,'password':password})['token']
    data=b'# Exact original\nchat-only attachment\n';own=upload(other,'chat-only.md',data);foreign=upload(token,'owner-isolation.md',b'private fixture')
    checks['chatOnlyUpload']=own['state']=='AVAILABLE'
    checks['chatOnlyExactDownload']=call('/interactions/user-assets/'+own['id']+'/content',token=other,raw=True)==(200,data)
    checks['dataScienceNotGranted']=call('/data-science/user-assets/space',token=other,raw=True)[0]==403
    checks['crossOwnerDownloadDenied']=call('/interactions/user-assets/'+foreign['id']+'/content?userId='+auth['user']['id'],token=other,raw=True)[0]==404
    checks['crossOwnerAnalysisDeniedBeforeModel']=call('/interactions/user-assets/analyses',{'requestId':'isolation-'+uuid.uuid4().hex,'fileIds':[foreign['id']],
        'instruction':'permission verification'},other,'POST',raw=True)[0]==404
    checks['crossOwnerImageAnalysisDeniedBeforeModel']=call('/interactions/user-assets/'+foreign['id']+'/image-analysis',{},other,'POST',raw=True)[0]==404
    checks['anonymousDenied']=call('/interactions/user-assets',raw=True)[0]==401
    if not all(checks.values()):raise RuntimeError('Chat permission checks failed')
    completed=True
finally:
    cleanup=[]
    for identity,file in [(other,own),(token,foreign)]:
        if file:cleanup.append(call('/interactions/user-assets/'+file['id'],token=identity,method='DELETE',raw=True)[0]==200)
    if user:cleanup.append(call('/enterprise/users/'+user['id'],token=token,method='DELETE',raw=True)[0]==200)
    if role:cleanup.append(call('/enterprise/roles/'+role['id'],token=token,method='DELETE',raw=True)[0]==200)
    report={'status':'PASSED' if completed and all(cleanup) else 'FAILED','checks':checks,'temporaryResourcesRemoved':all(cleanup),'modelCallsSubmitted':0}
    (root/'permission-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
