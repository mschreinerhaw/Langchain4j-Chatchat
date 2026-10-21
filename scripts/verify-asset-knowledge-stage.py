"""Live PG/Silo/OpenSearch acceptance, using the stage's existing private login file.

No model calls or changes to shared OpenSearch service availability. Test principals are removed on completion.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

os.umask(0o077)
STAGE=Path('/opt/runtime-os-docker-stage')
ROOT=STAGE/'evidence/runtime-storage-layering-20261021';ROOT.mkdir(mode=0o700,exist_ok=True)
BASE='http://127.0.0.1:18180/api/v1'
ASSETS='/data-science/user-assets'
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))

def request(path,body=None,token=None,method=None,content_type='application/json',raw=False):
    data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(BASE+path,data=data,method=method,headers={'Content-Type':content_type,
        'X-Workspace-Id':'data-science',**({'Authorization':'Bearer '+token} if token else {})})
    try:
        with client.open(req,timeout=60) as response:
            result=response.read()
            if raw:return response.status,result
            result=json.loads(result)
            if result.get('code',0) not in (0,200):raise RuntimeError('Application request failed: '+path)
            return result.get('data')
    except urllib.error.HTTPError as error:
        if raw:return error.code,error.read()
        (ROOT/'http-error-private.txt').write_bytes(error.read())
        raise RuntimeError('HTTP '+str(error.code)+' at '+path) from None

def upload(token,name,data):
    boundary='asset-'+uuid.uuid4().hex
    body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode()
    return request(ASSETS,body,token,content_type='multipart/form-data; boundary='+boundary)

def status(token,file):return request(ASSETS+'/knowledge/'+file['id']+'/status',token=token)
def wait_state(token,file,expected):
    for _ in range(40):
        current=status(token,file)
        if current['state']==expected:return current
        if current['state']=='FAILED' and expected!='FAILED':raise RuntimeError('Index failed: '+str(current.get('errorCode')))
        time.sleep(1)
    raise RuntimeError('Index did not reach '+expected)
def search(token,text,history=False):
    return request(ASSETS+'/knowledge/search?q='+urllib.parse.quote(text)+'&historyOnly='+str(history).lower(),token=token)

admin=request('/enterprise/auth/login',json.loads((STAGE/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
token=admin['token'];(ROOT/'browser-auth-private.json').write_text(json.dumps(admin))
role=None;user=None;files=[];checks={};other=None;completed=False
try:
    permissions=request('/enterprise/permissions',token=token)
    ids=[p['id'] for p in permissions if p['permissionCode'] in ('capability:data-science','account:self:read')]
    if len(ids)!=2:raise RuntimeError('Required existing permissions missing')
    role=request('/enterprise/roles',{'tenantId':admin['user']['tenantId'],'roleCode':'KNOWLEDGE_ACCEPT_'+uuid.uuid4().hex[:10],
        'roleName':'Temporary knowledge isolation verification','roleType':'business','status':'enabled'},token)
    request('/enterprise/roles/'+role['id']+'/authorization',{'permissionIds':ids,'orgScopes':[],'userIds':[],'agentIds':[]},token,method='PUT')
    name='knowledge_'+uuid.uuid4().hex[:12];password=secrets.token_urlsafe(24)
    user=request('/enterprise/users',{'user':{'tenantId':admin['user']['tenantId'],'username':name,'displayName':'Temporary knowledge verification',
        'passwordHash':password,'status':'enabled'},'roleIds':[role['id']]},token)
    other=request('/enterprise/auth/login',{'username':name,'password':password})['token']
    marker='assetknowledge'+uuid.uuid4().hex
    data=('private source '+marker+'\namount 12.50 CNY\n').encode()
    file=upload(token,'knowledge-acceptance-'+uuid.uuid4().hex[:10]+'.txt',data);files.append((token,file))
    checks['durableIndexState']=wait_state(token,file,'INDEXED')['indexedSha256']==hashlib.sha256(data).hexdigest()
    result=search(token,marker)
    checks['exactSourceSearch']=any(hit['fileId']==file['id'] and marker in hit['excerpt'] and hit['sha256']==file['sha256'] for hit in result)
    checks['emptyOwnerScopeReturnsNothing']=search(other,marker)==[]
    checks['crossOwnerStatusDenied']=request(ASSETS+'/knowledge/'+file['id']+'/status',token=other,raw=True)[0]==404
    checks['crossOwnerRetryDenied']=request(ASSETS+'/knowledge/'+file['id']+'/retry',{},other,method='POST',raw=True)[0]==404
    checks['anonymousSearchDenied']=request(ASSETS+'/knowledge/search?q='+marker,raw=True)[0]==401
    request(ASSETS+'/knowledge/'+file['id']+'/retry',{},token,method='POST');wait_state(token,file,'INDEXED')
    checks['rebuildFromSilo']=any(hit['fileId']==file['id'] for hit in search(token,marker))
    foreign=upload(other,'foreign-private.txt',('private source '+marker).encode());files.append((other,foreign));wait_state(other,foreign,'INDEXED')
    checks['sameTenantOwnerIsolation']=all(hit['fileId']!=foreign['id'] for hit in search(token,marker)) and all(hit['fileId']!=file['id'] for hit in search(other,marker))
    broken=upload(token,'invalid-source.pdf',b'%PDF-not-a-valid-document');files.append((token,broken))
    checks['parseFailureVisible']=wait_state(token,broken,'FAILED')['errorCode']=='PARSE_FAILED'
    request(ASSETS+'/knowledge/'+broken['id']+'/retry',{},token,method='POST')
    checks['parseFailureRetryDurable']=wait_state(token,broken,'FAILED')['attempts']==1
    history=search(token,'CNY',True)
    checks['historySearch']=any(hit['kind']=='CONTEXT' and hit['taskId'] and hit['sha256'] and hit['evidenceStatus']=='HISTORICAL_MODEL_OUTPUT_REQUIRES_SOURCE_VALIDATION' for hit in history)
    request(ASSETS+'/'+file['id'],token=token,method='DELETE')
    checks['deletedSourceCannotBeRetrieved']=all(hit['fileId']!=file['id'] for hit in search(token,marker))
    if not all(checks.values()):raise RuntimeError('Acceptance checks failed: '+','.join(key for key,value in checks.items() if not value))
    completed=True
finally:
    cleanup=[]
    for identity,file in files:
        code,_=request(ASSETS+'/'+file['id'],token=identity,method='DELETE',raw=True)
        cleanup.append(code==200)
    if user:cleanup.append(request('/enterprise/users/'+user['id'],token=token,method='DELETE',raw=True)[0]==200)
    if role:cleanup.append(request('/enterprise/roles/'+role['id'],token=token,method='DELETE',raw=True)[0]==200)
    report={'status':'PASSED' if completed and checks and all(checks.values()) and all(cleanup) else 'FAILED',
        'checks':checks,'temporaryResourcesRemoved':all(cleanup),'modelCallsSubmitted':0}
    (ROOT/'knowledge-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
