"""Provision/clean synthetic permission-console fixtures through the existing control-plane APIs."""
import argparse,json,os,secrets,urllib.request,urllib.parse,uuid
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--cleanup',action='store_true');args=parser.parse_args();os.umask(0o077);args.output.mkdir(parents=True,exist_ok=True,mode=0o700)
client=urllib.request.build_opener(urllib.request.ProxyHandler({}));token=None
def call(path,body=None,method=None):
 request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=None if body is None else json.dumps(body).encode(),method=method,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
 with client.open(request,timeout=90) as response:value=json.load(response)
 if value.get('code') not in (None,0,200,202):raise RuntimeError('API rejected '+path)
 return value.get('data')
credentials=json.loads(Path('/opt/runtime-os-docker-stage/evidence/cline-monitor-20261019/stage-auth.json').read_text());admin=call('/enterprise/auth/login',credentials);token=admin['token'];state_path=args.output/'fixture-private.json'
state=json.loads(state_path.read_text()) if state_path.exists() else {'users':[],'roles':[]}
def save():state_path.write_text(json.dumps(state,ensure_ascii=False,indent=2))
if args.cleanup:
 if state.get('toolName'):
  for grant in call('/permission-center')['grants']:
   if grant['localToolName']==state['toolName']:call('/permission-center/tool-grants/'+grant['id'],method='DELETE')
 if state.get('logicalId'):
  for release in call('/tool-publications'):
   if release['logicalId']==state['logicalId'] and release['state']=='RUNNING':call('/tool-publications/'+release['id']+'/offline',{},'POST')
 for kind in ['users','roles']:
  for item in state[kind]:call('/enterprise/'+kind+'/'+item,method='DELETE')
 state.update(cleaned=True);save();print(json.dumps({'status':'CLEANED_UP','identitiesDeleted':True,'syntheticToolOffline':True}));raise SystemExit()
if state.get('logicalId'):raise RuntimeError('Existing fixture must be cleaned up or resumed; refusing duplicate setup')
(args.output/'admin-session-private.json').write_text(json.dumps(admin))
permissions=call('/enterprise/permissions');codes=admin['user']['permissionCodes'];ids={p['permissionCode']:p['id'] for p in permissions}
role=call('/enterprise/roles',{'tenantId':admin['user']['tenantId'],'roleName':'权限工作空间验收管理员','roleCode':'UX_ADMIN_'+uuid.uuid4().hex[:10],'roleType':'business','status':'enabled'});state['roles'].append(role['id']);save()
excluded={'system:api:all','platform:agents:all','platform:tasks:all','system:workspaces:manage'}
call('/enterprise/roles/'+role['id']+'/authorization',{'permissionIds':[ids[code] for code in codes if code in ids and code not in excluded],'orgScopes':[],'userIds':[],'agentIds':[]},'PUT')
username='ux_permission_'+uuid.uuid4().hex[:10];password=secrets.token_urlsafe(24)
user=call('/enterprise/users',{'user':{'tenantId':admin['user']['tenantId'],'username':username,'displayName':'权限管理员（验收）','passwordHash':password,'status':'enabled'},'roleIds':[role['id']]});state['users'].append(user['id']);save()
ordinary=call('/enterprise/auth/login',{'username':username,'password':password});(args.output/'ordinary-session-private.json').write_text(json.dumps(ordinary))
roles=call('/tool-publications/catalog/roles');source_role=next(r for r in roles if r['roleCode']=='SUPER_ADMIN')
chosen=None
for parent in call('/tool-publications/catalog/parents'):
 templates=call('/tool-publications/catalog/templates?'+urllib.parse.urlencode({'roleId':source_role['id'],'parentToolName':parent['toolName']}))
 if templates:chosen=(parent,templates[0]);break
if not chosen:raise RuntimeError('No authorized controlled template available')
parent,template=chosen;domain='unified_acceptance_'+uuid.uuid4().hex[:12]
draft={'title':'权限控制台验收工具','description':'仅用于合成权限验收，完成后下线。','kind':'CONTROLLED_TEMPLATE','definition':{'parentToolName':parent['toolName'],'roleId':source_role['id'],'subjectType':'ROLE','userId':None,'domainCode':domain,'templateKeys':[template['key']],'enabled':False},'inputSchema':{'type':'object','properties':{'limit':{'type':'integer','minimum':1,'maximum':20}}},'outputSchema':{'type':'object','properties':{'success':{'const':True}},'required':['success']}}
release=call('/tool-publications',draft);state.update(logicalId=release['logicalId'],releaseId=release['id'],publicationDefinition=draft['definition'],templateTitle=template['title']);save()
call('/tool-publications/'+release['id']+'/validate',{},'POST');call('/tool-publications/'+release['id']+'/test',{'input':{'limit':1}},'POST');call('/tool-publications/'+release['id']+'/publish',{},'POST')
descriptor=next(t for t in call('/mcp/runtime/tools?toolNames='+domain+'_template_query') if t['remoteToolName']==domain+'_template_query')
state.update(toolName=descriptor['localToolName'],invocation={'serviceId':descriptor['serviceId'],'toolName':descriptor['localToolName'],'arguments':{'limit':1},'context':{}},workspacePermissionId=ids['workspace:permissions:access'],workspacePermissionName=next(p['permissionName'] for p in permissions if p['permissionCode']=='workspace:permissions:access'))
save();(args.output/'browser-private.json').write_text(json.dumps({'admin':admin,'ordinary':ordinary,'fixture':state},ensure_ascii=False));print(json.dumps({'status':'PREPARED','ordinaryAdminHasNoApiAll':True,'syntheticToolPublished':True}))
