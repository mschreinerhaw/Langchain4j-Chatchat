"""Temporary SQL Studio identities verify publication/grant isolation without invoking a model.
Run setup, browser verification, then cleanup. Private sessions remain root-only.
"""
import json,os,secrets,sys,urllib.error,urllib.request,uuid
from pathlib import Path
os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage');root=stage/'evidence/sql-assistant-simple-20261021';root.mkdir(mode=0o700,exist_ok=True)
private=root/'browser-state-private.json';client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None,token=None,method=None,raw=False):
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=None if body is None else json.dumps(body).encode(),method=method,
        headers={'Content-Type':'application/json','Accept':'application/json','X-Workspace-Id':'data-science',**({'Authorization':'Bearer '+token} if token else {})})
    try:
        with client.open(request,timeout=30) as response:
            value=response.read()
            if raw:return response.status
            result=json.loads(value);assert result.get('code',0) in (0,200);return result.get('data')
    except urllib.error.HTTPError as error:
        error.read()
        if raw:return error.code
        raise RuntimeError('HTTP '+str(error.code)+' at '+path) from None
def cleanup(state):
    token=state['admin']['token'];checks=[]
    for project in state['projects']:checks.append(call('/data-science/sql/projects/'+project['id'],token=project['cleanupToken'],method='DELETE',raw=True)==200)
    for user in state['users']:checks.append(call('/enterprise/users/'+user['id'],token=token,method='DELETE',raw=True)==200)
    for role in state['roles']:checks.append(call('/enterprise/roles/'+role['id'],token=token,method='DELETE',raw=True)==200)
    assert all(checks);return True
if len(sys.argv)>1 and sys.argv[1]=='cleanup':
    state=json.loads(private.read_text());cleanup(state);report=json.loads((root/'api-acceptance.json').read_text());report['temporaryResourcesRemoved']=True
    (root/'api-acceptance.json').write_text(json.dumps(report,indent=2));private.unlink();print(json.dumps({'temporaryResourcesRemoved':True}));sys.exit(0)
auth=call('/enterprise/auth/login',json.loads((stage/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
admin=auth['token'];state={'admin':auth,'roles':[],'users':[],'projects':[]};checks={};success=False
try:
    catalogue=call('/enterprise/agent-options',token=admin);published=[a for a in catalogue if a['marketStatus'].lower()=='published'];assert published,'An existing published Agent is required'
    chosen=published[0]['id'];draft=next((a['id'] for a in catalogue if a['marketStatus'].lower()!='published'),None)
    permissions=call('/enterprise/permissions',token=admin);ids=[p['id'] for p in permissions if p['permissionCode'] in ('capability:data-science','account:self:read','account:menus:read','workspace:api:access')]
    def authorize(role,agents):return call('/enterprise/roles/'+role['id']+'/authorization',{'permissionIds':ids,'orgScopes':[],'agentIds':agents},admin,'PUT')
    def identity(label):
        role=call('/enterprise/roles',{'tenantId':auth['user']['tenantId'],'roleCode':'SQL_SIMPLE_'+uuid.uuid4().hex[:10],'roleName':'Temporary SQL assistant '+label,'roleType':'business','status':'enabled'},admin);state['roles'].append(role);authorize(role,[])
        username='sql_simple_'+uuid.uuid4().hex[:10];password=secrets.token_urlsafe(24)
        user=call('/enterprise/users',{'user':{'tenantId':auth['user']['tenantId'],'username':username,'passwordHash':password,'displayName':'Temporary SQL assistant '+label,'status':'enabled'},'roleIds':[role['id']]},admin);state['users'].append(user)
        session=call('/enterprise/auth/login',{'username':username,'password':password})
        project=call('/data-science/sql/projects',{'name':'Temporary SQL assistant '+label,'dialect':'postgresql'},session['token']);project['cleanupToken']=session['token'];state['projects'].append(project)
        call('/data-science/sql/projects/'+project['id']+'/folders',{'path':'sql'},session['token'])
        call('/data-science/sql/projects/'+project['id']+'/files',{'path':'sql/reference.sql','kind':'SQL','content':'SELECT 1 AS reference_value;','expectedVersion':0},session['token'],'PUT')
        return session,role,project
    alice,role_a,project_a=identity('with Agent');bob,role_b,project_b=identity('without Agent')
    options=lambda session:call('/data-science/sql/agent-options',token=session['token'])
    checks['noGrantReturnsNoOptions']=options(alice)==options(bob)==[]
    authorize(role_a,[chosen]+([draft] if draft else []))
    checks['onlyGrantedPublishedAgentReturned']=[a['id'] for a in options(alice)]==[chosen]
    checks['otherUserRemainsIsolated']=options(bob)==[]
    checks['adminOptionsPublishedOnly']=all(a['marketStatus'].lower()=='published' for a in options(auth))
    def denied(session,project,agent):return call('/data-science/sql/projects/'+project['id']+'/generations',{'prompt':'Check SQL','dialect':'postgresql','agentId':agent},session['token'],raw=True)==403
    checks['forgedUngrantAgentRejected']=denied(bob,project_b,chosen)
    checks['unknownAgentRejected']=denied(alice,project_a,'nonexistent-'+uuid.uuid4().hex)
    if draft:checks['draftRejectedEvenWithRoleBinding']=denied(alice,project_a,draft)
    authorize(role_a,[]);checks['revokedGrantImmediatelyHidden']=options(alice)==[];checks['revokedAgentCannotPrepare']=denied(alice,project_a,chosen)
    checks['rejectionsCreateNoGeneration']=call('/data-science/sql/projects/'+project_a['id']+'/generations',token=alice['token'])==call('/data-science/sql/projects/'+project_b['id']+'/generations',token=bob['token'])==[]
    checks['anonymousOptionsDenied']=call('/data-science/sql/agent-options',raw=True)==401
    authorize(role_a,[chosen]);state.update(withAgent=alice,withoutAgent=bob,withAgentProject=project_a['id'],withoutAgentProject=project_b['id'],publishedAgentId=chosen)
    assert all(checks.values());success=True;private.write_text(json.dumps(state))
finally:
    removed=False
    if not success:removed=cleanup(state)
    report={'status':'PASSED' if success else 'FAILED','checks':checks,'modelCallsSubmitted':0,'scriptsExecuted':0,'temporaryResourcesRemoved':removed}
    (root/'api-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
