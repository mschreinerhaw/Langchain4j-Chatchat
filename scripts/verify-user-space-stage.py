"""Live user-space folders, owner isolation, and exact Silo -> Python execution-copy acceptance.
No model or script execution. Temporary identities/files are removed; secrets stay root-only.
"""
import hashlib,json,os,secrets,urllib.error,urllib.request,uuid
from pathlib import Path
os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage');root=stage/'evidence/user-space-browser-20261021';root.mkdir(mode=0o700,exist_ok=True)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None,token=None,method=None,content_type='application/json',raw=False):
    data=body if isinstance(body,bytes) else None if body is None else json.dumps(body).encode()
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=data,method=method,
        headers={'Content-Type':content_type,'Accept':'*/*' if raw else 'application/json','X-Workspace-Id':'data-science',**({'Authorization':'Bearer '+token} if token else {})})
    try:
        with client.open(request,timeout=60) as response:
            data=response.read()
            if raw:return response.status,data
            result=json.loads(data)
            if result.get('code',0) not in (0,200):raise RuntimeError('Application rejection at '+path)
            return result.get('data')
    except urllib.error.HTTPError as error:
        data=error.read()
        if raw:return error.code,data
        (root/'http-error-private.txt').write_bytes(data);raise RuntimeError('HTTP '+str(error.code)+' at '+path) from None
def upload(token,name,content,route='/data-science/user-assets'):
    boundary=uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+content+(f'\r\n--{boundary}--\r\n').encode()
    return call(route,body,token,content_type='multipart/form-data; boundary='+boundary)
auth=call('/enterprise/auth/login',json.loads((stage/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
admin=auth['token'];(root/'browser-auth-private.json').write_text(json.dumps(auth))
roles=[];users=[];uploads=[];folders=[];checks={};completed=False
try:
    permissions=call('/enterprise/permissions',token=admin)
    def identity(codes,label):
        ids=[permission['id'] for permission in permissions if permission['permissionCode'] in codes]
        role=call('/enterprise/roles',{'tenantId':auth['user']['tenantId'],'roleCode':'SPACE_'+uuid.uuid4().hex[:10],
            'roleName':'Temporary user-space '+label,'roleType':'business','status':'enabled'},admin);roles.append(role)
        call('/enterprise/roles/'+role['id']+'/authorization',{'permissionIds':ids,'orgScopes':[],'userIds':[],'agentIds':[]},admin,'PUT')
        username='space_'+uuid.uuid4().hex[:10];password=secrets.token_urlsafe(24)
        user=call('/enterprise/users',{'user':{'tenantId':auth['user']['tenantId'],'username':username,'passwordHash':password,'displayName':'Temporary user-space '+label,'status':'enabled'},'roleIds':[role['id']]},admin);users.append(user)
        return call('/enterprise/auth/login',{'username':username,'password':password})['token']
    alice=identity(['capability:data-science','account:self:read'],'owner A')
    bob=identity(['capability:data-science','account:self:read'],'owner B')
    chat=identity(['workspace:chat:interact','account:self:read'],'chat only')
    original=b'category,amount\nfirst,12.25\nsecond,4.50\n'
    own=upload(alice,'space-input.csv',original);uploads.append((alice,own))
    foreign=upload(bob,'private.py',b'print("owner B")\n');uploads.append((bob,foreign))
    chat_file=upload(chat,'chat-input.csv',b'value\n1\n','/interactions/user-assets');uploads.append((chat,chat_file))
    folder=call('/data-science/user-assets/browser/folders',{'parentId':'data','name':'Private analysis'},alice);folders.append((alice,folder))
    location={'folderId':folder['id'],'entries':[{'source':'asset','sourceId':own['id']}]}
    call('/data-science/user-assets/browser/locations',location,alice,'PUT')
    tree=call('/data-science/user-assets/browser',token=alice)
    checks['logicalFolderPersisted']=any(item['id']==folder['id'] for item in tree['folders']) and any(item['sourceId']==own['id'] and item['folderId']==folder['id'] for item in tree['locations'])
    checks['otherOwnerFolderInvisible']=not call('/data-science/user-assets/browser',token=bob)['folders']
    checks['foreignFolderRejected']=call('/data-science/user-assets/browser/locations',{'folderId':folder['id'],'entries':[{'source':'asset','sourceId':foreign['id']}]},bob,'PUT',raw=True)[0]==404
    checks['foreignFileMoveRejected']=call('/data-science/user-assets/browser/locations',{'folderId':folder['id'],'entries':[{'source':'asset','sourceId':foreign['id']}]},alice,'PUT',raw=True)[0]==404
    checks['foreignOriginalRejected']=call('/data-science/user-assets/'+own['id']+'/content?userId='+users[0]['id'],token=bob,raw=True)[0]==404
    checks['nonemptyFolderProtected']=call('/data-science/user-assets/browser/folders/'+folder['id'],token=alice,method='DELETE',raw=True)[0]==409
    checks['pathTraversalRejected']=call('/data-science/user-assets/browser/folders',{'parentId':'root','name':'../unsafe'},alice,raw=True)[0]==400
    checks['chatCannotManageFolders']=call('/data-science/user-assets/browser',token=chat,raw=True)[0]==403
    checks['chatCannotPreparePython']=call('/data-science/user-assets/'+chat_file['id']+'/python-data',{},chat,raw=True)[0]==403
    checks['chatAliasHasNoPythonBridge']=call('/interactions/user-assets/'+chat_file['id']+'/python-data',{},chat,raw=True)[0] in (404,405)
    checks['anonymousFolderAccessRejected']=call('/data-science/user-assets/browser',raw=True)[0]==401
    checks['foreignPythonPreparationRejected']=call('/data-science/user-assets/'+own['id']+'/python-data',{},bob,raw=True)[0]==404
    prepared=call('/data-science/user-assets/'+own['id']+'/python-data',{},alice)
    repeated=call('/data-science/user-assets/'+own['id']+'/python-data',{},alice)
    checks['executionCopyDeduplicated']=prepared['id']==repeated['id']
    checks['executionCopyScopedToOriginal']=prepared.get('sourceAssetId')==own['id'] and prepared['fileHash']==own['sha256'] and prepared['status']=='AVAILABLE'
    checks['executionCopyExactBytes']=call('/data-science/python/data-files/'+prepared['id']+'/download',token=alice,raw=True)==(200,original)
    legacy_location={'folderId':folder['id'],'entries':[{'source':'python-data','sourceId':prepared['id']}]}
    checks['legacyUsernameFolderMove']=call('/data-science/user-assets/browser/locations',legacy_location,alice,'PUT',raw=True)[0]==200
    checks['foreignLegacyFileMoveRejected']=call('/data-science/user-assets/browser/locations',{'folderId':'data','entries':legacy_location['entries']},bob,'PUT',raw=True)[0]==404
    checks['siloOriginalUnchanged']=call('/data-science/user-assets/'+own['id']+'/content',token=alice,raw=True)==(200,original)
    call('/data-science/user-assets/'+own['id'],token=alice,method='DELETE');uploads.remove((alice,own))
    checks['deletingOriginalRevokesExecutionCopy']=call('/data-science/python/data-files/'+prepared['id']+'/download',token=alice,raw=True)[0] in (400,404)
    call('/data-science/user-assets/browser/folders/'+folder['id'],token=alice,method='DELETE');folders.remove((alice,folder))
    checks['emptyFolderRemovable']=not call('/data-science/user-assets/browser',token=alice)['folders']
    completed=all(checks.values())
    if not completed:raise RuntimeError('User space acceptance failed')
finally:
    cleanup=[]
    for identity,file in uploads:
        route='/interactions/user-assets/' if identity==locals().get('chat') else '/data-science/user-assets/'
        cleanup.append(call(route+file['id'],token=identity,method='DELETE',raw=True)[0]==200)
    for identity,folder in reversed(folders):cleanup.append(call('/data-science/user-assets/browser/folders/'+folder['id'],token=identity,method='DELETE',raw=True)[0]==200)
    for user in users:cleanup.append(call('/enterprise/users/'+user['id'],token=admin,method='DELETE',raw=True)[0]==200)
    for role in roles:cleanup.append(call('/enterprise/roles/'+role['id'],token=admin,method='DELETE',raw=True)[0]==200)
    report={'status':'PASSED' if completed and all(cleanup) else 'FAILED','checks':checks,'temporaryResourcesRemoved':all(cleanup),'modelCallsSubmitted':0,'scriptsExecuted':0}
    (root/'api-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
