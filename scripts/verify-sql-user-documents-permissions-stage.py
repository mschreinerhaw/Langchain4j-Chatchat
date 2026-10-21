"""Live SQL document owner/purpose validation before planning; no model invocation."""
import json,os,secrets,urllib.error,urllib.request,uuid
from pathlib import Path
os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage');root=stage/'evidence/sql-studio-user-documents-20261021';root.mkdir(mode=0o700,exist_ok=True)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None,token=None,method=None,content_type='application/json',raw=False):
    data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=data,method=method,
        headers={'Content-Type':content_type,'X-Workspace-Id':'data-science',**({'Authorization':'Bearer '+token} if token else {})})
    try:
        with client.open(request,timeout=30) as response:
            data=response.read()
            if raw:return response.status,data
            result=json.loads(data)
            if result.get('code',0) not in (0,200):raise RuntimeError('Application rejection at '+path)
            return result.get('data')
    except urllib.error.HTTPError as error:
        data=error.read()
        if raw:return error.code,data
        (root/'http-error-private.txt').write_bytes(data);raise RuntimeError('HTTP '+str(error.code)+' at '+path) from None
def upload(token,name,data,route='/data-science/user-assets'):
    boundary=uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\n').encode()+data+(f'\r\n--{boundary}--\r\n').encode()
    return call(route,body,token,content_type='multipart/form-data; boundary='+boundary)
auth=call('/enterprise/auth/login',json.loads((stage/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
admin=auth['token'];(root/'browser-auth-private.json').write_text(json.dumps(auth))
role=user=other=foreign=own=project=None;checks={};completed=False
try:
    permissions=call('/enterprise/permissions',token=admin)
    ids=[p['id'] for p in permissions if p['permissionCode'] in ('workspace:chat:interact','account:self:read')]
    role=call('/enterprise/roles',{'tenantId':auth['user']['tenantId'],'roleCode':'SQL_DOCS_'+uuid.uuid4().hex[:10],
        'roleName':'Temporary SQL document isolation','roleType':'business','status':'enabled'},admin)
    call('/enterprise/roles/'+role['id']+'/authorization',{'permissionIds':ids,'orgScopes':[],'userIds':[],'agentIds':[]},admin,'PUT')
    username='sql_docs_'+uuid.uuid4().hex[:10];password=secrets.token_urlsafe(24)
    user=call('/enterprise/users',{'user':{'tenantId':auth['user']['tenantId'],'username':username,'passwordHash':password,'displayName':'Temporary SQL docs isolation','status':'enabled'},'roleIds':[role['id']]},admin)
    other=call('/enterprise/auth/login',{'username':username,'password':password})['token']
    foreign=upload(other,'private-rules.md',b'Private rules','/interactions/user-assets')
    original=b'-- User SQL reference\r\nSELECT 1 AS style_reference;\r\n';own=upload(admin,'reference-conventions.sql',original)
    checks['sqlOriginalAccepted']=own['state']=='AVAILABLE'
    checks['sqlExactOriginal']=call('/data-science/user-assets/'+own['id']+'/content',token=admin,raw=True)==(200,original)
    project=call('/data-science/sql/projects',{'name':'Temporary SQL document permission acceptance','dialect':'postgresql'},admin)
    route='/data-science/sql/projects/'+project['id']+'/generations'
    def prepare(selected,token=admin):return call(route,{'prompt':'Generate SQL from selected documents','dialect':'postgresql','userDocuments':selected},token,raw=True)[0]
    checks['foreignDocumentRejected']=prepare([{'fileId':foreign['id'],'purpose':'RULES'}])==404
    checks['duplicateDocumentsRejected']=prepare([{'fileId':own['id'],'purpose':'RULES'},{'fileId':own['id'],'purpose':'EVIDENCE'}])==400
    checks['invalidPurposeRejected']=prepare([{'fileId':own['id'],'purpose':'SYSTEM'}])==400
    checks['crossOwnerProjectDenied']=prepare([{'fileId':foreign['id'],'purpose':'RULES'}],other) in (403,404)
    checks['anonymousDenied']=prepare([{'fileId':own['id'],'purpose':'RULES'}],None)==401
    checks['noGenerationOrPlanCreated']=call(route,token=admin)==[]
    if not all(checks.values()):raise RuntimeError('SQL document authorization acceptance failed')
    completed=True
finally:
    cleanup=[]
    if foreign and other:cleanup.append(call('/interactions/user-assets/'+foreign['id'],token=other,method='DELETE',raw=True)[0]==200)
    if own:cleanup.append(call('/data-science/user-assets/'+own['id'],token=admin,method='DELETE',raw=True)[0]==200)
    if project:cleanup.append(call('/data-science/sql/projects/'+project['id'],token=admin,method='DELETE',raw=True)[0]==200)
    if user:cleanup.append(call('/enterprise/users/'+user['id'],token=admin,method='DELETE',raw=True)[0]==200)
    if role:cleanup.append(call('/enterprise/roles/'+role['id'],token=admin,method='DELETE',raw=True)[0]==200)
    report={'status':'PASSED' if completed and all(cleanup) else 'FAILED','checks':checks,'modelCallsSubmitted':0,'temporaryResourcesRemoved':all(cleanup)}
    (root/'permission-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
