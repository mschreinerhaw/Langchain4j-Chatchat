"""Lifecycle checks use only a dedicated acceptance document and dedicated account grants."""
import argparse,base64,json,os,re,ssl,subprocess,time,urllib.request,urllib.error,uuid
from pathlib import Path
os.umask(0o077)
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--release-dir',type=Path,required=True)
parser.add_argument('--index-name',default='runtime_os_stage_documents',help='Existing stage index used for a bounded stale-record check')
args=parser.parse_args();root=args.release_dir
assert re.fullmatch(r'[a-z0-9_-]{1,128}',args.index_name)
if (root/'lifecycle-summary.json').exists():
 raise RuntimeError('Lifecycle run already completed; use a fresh fixture directory for a new run')
f=json.loads((root/'fixture.private.json').read_text());client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def sql(q):return subprocess.check_output(['docker','exec','-i','-u','postgres','postgres','psql','-v','ON_ERROR_STOP=1','-At','-d','runtime_os_acceptance_api'],input=q,text=True).strip()
def lit(v):return "'"+str(v).replace("'","''")+"'"
def http(path,body=None,method=None,token=None):
 data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
 h={'Content-Type':'application/json'}
 if token:h['Authorization']='Bearer '+token
 try:
  with client.open(urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=data,headers=h,method=method),timeout=40) as response:return response.status,json.loads(response.read())
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def api(path,body=None,method=None):
 status,v=http(path,body,method,token)
 assert status==200 and v.get('code')==200,(status,str(v)[:160]);return v.get('data')
_,auth=http('/enterprise/auth/login',{'username':f['username'],'password':f['password']});token=auth['data']['token']
state=root/'lifecycle.private.json'
if state.exists():s=json.loads(state.read_text())
else:
 s={'id':'knowledge-p0-lifecycle-'+uuid.uuid4().hex,'oldCode':uuid.uuid4().hex,'newCode':uuid.uuid4().hex,'session':str(uuid.uuid4())}
 state.write_text(json.dumps(s))
doc={'docId':s['id'],'title':'知识权限生命周期验收 '+s['id'],'content':'# 生命周期验证\n这是专用合成验收文档，不是业务恢复证明。\n当前校验码：'+s['oldCode']+'。','tenantId':f['tenant'],'userId':f['user'],'visibility':'PRIVATE','version':1,'latestVersion':True,'documentType':'acceptance','source':'knowledge-p0-lifecycle','fileName':s['id']+'.md','tags':['knowledge-p0-lifecycle']}
if 'created' not in s:
 saved=api('/search/documents',doc);s['created']=True;state.write_text(json.dumps(s));print('CREATED',s['id'],flush=True)
 sql("insert into skill_resource_scope(id,created_at,updated_at,tenant_id,skill_id,resource_type,resource_id,enabled) values ("+','.join([lit(str(uuid.uuid4())),'now()','now()',lit(f['tenant']),lit(f['agent']),lit('DOCUMENT'),lit(s['id']),'true'])+");")
 time.sleep(2)
if 'taskId' not in s:
 task=api('/agent/tasks',{'mode':'role_chat','skillId':f['agent'],'modelName':f.get('model','hy3'),'sessionId':s['session'],'requestSource':'knowledge-lifecycle-acceptance','idempotencyKey':str(uuid.uuid4()),'query':'请检索专用验收文档 '+s['id']+'，读取当前校验码。仅简短回答该码并附上原文引用，不要扩展其他主题。'})
 s['taskId']=task['taskId'];state.write_text(json.dumps(s))
query='?tenantId='+f['tenant'];taskpath='/agent/tasks/'+s['taskId'];start=time.monotonic();events=[];cursor=0
while True:
 task=api(taskpath+query);page=api(taskpath+'/events'+query+'&limit=200&afterSequence='+str(cursor)) or []
 if page:cursor=max(int(e.get('sequence',0)) for e in page);events.extend(page)
 if task.get('canonicalState') in {'SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED'} and len(page)<200:break
 if time.monotonic()-start>420:raise RuntimeError('Lifecycle task deadline exceeded')
 time.sleep(1)
(root/'lifecycle-task.private.json').write_text(json.dumps({'task':task,'events':events},ensure_ascii=False))
text=json.dumps(events,ensure_ascii=False);refs=sorted(set(re.findall(r'runtime-evidence:knowledge:[0-9a-f]{64}',text)))
assert s['oldCode'] in text and refs,'Expected original code and actual source citation in completed answer'
ref=next(ref for ref in refs if api('/agent/knowledge/citations/'+ref.split(':')[-1])['document_id']==s['id'])
sourcepath='/agent/knowledge/citations/'+ref.split(':')[-1]
report={'documentId':s['id'],'syntheticSource':True,'taskId':s['taskId'],'initialStatus':task['canonicalState'],'hasActualSourceCitation':True,'checks':[]}
def check(name,path,denied):
 status,value=http(path,token=token)
 good=status==403 or value.get('code')==403
 assert good if denied else status==200 and value.get('code')==200,(name,status,str(value)[:100])
 report['checks'].append({'name':name,'httpStatus':status,'apiCode':value.get('code'),'passed':True});print(name,status,value.get('code'),flush=True)
try:
 sql("update resource_grant set enabled=false where resource_type='KNOWLEDGE' and principal_type='USER' and principal_id="+lit(f['user'])+' and resource_id='+lit(s['id'])+';')
 check('revoked-citation',sourcepath,True);check('revoked-task-summary',taskpath+query,True);check('revoked-events',taskpath+'/events'+query+'&limit=200',True);check('revoked-result',taskpath+'/result'+query,True)
finally:
 sql("update resource_grant set enabled=true where resource_type='KNOWLEDGE' and principal_type='USER' and principal_id="+lit(f['user'])+' and resource_id='+lit(s['id'])+';')
check('grant-restored-current-source',sourcepath,False)
doc['content']=doc['content'].replace(s['oldCode'],s['newCode']);api('/search/documents',doc)
check('same-version-update-old-citation',sourcepath,True);check('same-version-update-old-summary',taskpath+query,True)
def followup(name,expect_new):
 started=time.monotonic()
 follow=api('/agent/tasks',{'mode':'role_chat','skillId':f['agent'],'modelName':f.get('model','hy3'),'sessionId':s['session'],
  'requestSource':'knowledge-lifecycle-acceptance','idempotencyKey':str(uuid.uuid4()),
  'query':'重新检索文档 '+s['id']+' 的当前校验码，只回答当前原文中的码并引用；如果没有当前证据请说明，禁止沿用旧会话的码。'})
 fp='/agent/tasks/'+follow['taskId'];all_events=[];after=0
 while True:
  follow=api(fp+query);page=api(fp+'/events'+query+'&limit=200&afterSequence='+str(after)) or []
  if page:after=max(int(e.get('sequence',0)) for e in page);all_events.extend(page)
  if follow.get('canonicalState') in {'SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED'} and len(page)<200:break
  if time.monotonic()-started>420:
   api(fp+'/cancel'+query,{});raise RuntimeError('Follow-up deadline exceeded')
  time.sleep(1)
 value=json.dumps(all_events,ensure_ascii=False)
 assert follow['canonicalState'] in {'SUCCEEDED','PARTIAL'},follow['canonicalState']
 assert s['oldCode'] not in value,'Old session knowledge was re-disclosed'
 if expect_new:assert s['newCode'] in value,'Updated source not reacquired'
 else:assert s['newCode'] not in value,'Deleted source was re-disclosed'
 (root/(name+'.private.json')).write_text(json.dumps({'task':follow,'events':all_events},ensure_ascii=False))
 report['checks'].append({'name':name,'passed':True,'taskId':follow['taskId'],'status':follow['canonicalState'],
  'sameSession':True,'oldCodeDisclosed':False,'durationMs':round((time.monotonic()-started)*1000)})
 print(name,follow['canonicalState'],flush=True)
followup('same-session-update-reacquires-current-source',True)
# Retain only this synthetic record to simulate index lag after the original is deleted.
info=json.loads(subprocess.check_output(['docker','inspect','opensearch-node'],text=True))[0]
environment=dict(item.split('=',1) for item in info['Config']['Env'] if '=' in item)
search_client=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
search_client.addheaders=[('Authorization','Basic '+base64.b64encode(('admin:'+environment['OPENSEARCH_INITIAL_ADMIN_PASSWORD']).encode()).decode())]
def index_request(path,body=None,method=None):
 data=json.dumps(body).encode() if body is not None else None
 with search_client.open(urllib.request.Request('https://127.0.0.1:9200/'+args.index_name+path,data=data,
  headers={'Content-Type':'application/json'},method=method),timeout=30) as response:return json.load(response)
snapshot=index_request('/_search',{'size':10,'query':{'term':{'fileId':s['id']}}})['hits']['hits']
assert snapshot and s['id'].startswith('knowledge-p0-lifecycle-')
stale=snapshot[0]['_source'];assert stale['fileId']==s['id']
stale_id=s['id']+'_stale_acceptance'
# Obtain only the maintained document-delete permission for this dedicated fixture role.
role='knowledge-p0-acceptance-role';perm=sql("select id from sys_permission where permission_code='workspace:search:delete'")
assert perm
added=sql('select count(*) from sys_role_permission where role_id='+lit(role)+' and permission_id='+lit(perm))=='0'
rp=str(uuid.uuid4())
if added:sql("insert into sys_role_permission(id,created_at,updated_at,tenant_id,role_id,permission_id) values ("+','.join([lit(rp),'now()','now()',lit(f['tenant']),lit(role),lit(perm)])+");")
try:
 # Login refreshes the dedicated account's permission view.
 _,a=http('/enterprise/auth/login',{'username':f['username'],'password':f['password']});token=a['data']['token']
 api('/search/documents/'+s['id'],method='DELETE');check('deleted-source-old-citation',sourcepath,True)
 index_request('/_doc/'+stale_id+'?refresh=true',stale,'PUT')
 try:
  followup('same-session-deleted-source-not-disclosed',False)
  report['checks'].append({'name':'stale-index-record-cannot-regrant-deleted-original','passed':True,'retainedSyntheticIndexRecord':True})
 finally:index_request('/_doc/'+stale_id+'?refresh=true',method='DELETE')
finally:
 if added:sql('delete from sys_role_permission where id='+lit(rp)+';')
 sql("delete from skill_resource_scope where skill_id="+lit(f['agent'])+" and resource_type='DOCUMENT' and resource_id="+lit(s['id'])+';')
report['completed']=True;(root/'lifecycle-summary.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
