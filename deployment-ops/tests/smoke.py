"""Run against an isolated test deployment. Creates only ops-app-acceptance if --deploy-test."""
import urllib.request,urllib.error,http.cookiejar,json,os,sys,time
BASE=os.getenv('OPS_URL','http://127.0.0.1:19220')
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
results=[]
def req(path,data=None,headers=None):
 r=op.open(urllib.request.Request(BASE+'/api'+path,data=json.dumps(data).encode() if data is not None else None,headers={'X-Ops-Request':'1'} if headers is None else headers),timeout=260)
 return json.load(r)
def check(name,condition):
 results.append({'name':name,'passed':bool(condition)});print(name,condition,flush=True)
 if not condition:raise AssertionError(name)
for attempt in range(40):
 try:
  if req('/health')['status']=='UP':break
 except Exception:time.sleep(2)
check('health',req('/health')['status']=='UP')
try:req('/inventory');raise AssertionError('unauthorized request accepted')
except urllib.error.HTTPError as e:check('anonymous denied',e.code==401)
check('login',req('/login',{'username':'admin','password':os.environ['OPS_PASSWORD']})['role']=='admin')
nodes=req('/nodes');inventory=req('/inventory');check('real inventory',len(inventory)>0 and 'data' in inventory[0]);node=nodes[0]['id']
containers=inventory[0]['data']['containers'];check('system discovered',any('deployment-ops-web-1' in str(c['Names']) for c in containers))
web=next(c for c in containers if 'deployment-ops-web-1' in str(c['Names']))
try:req('/nodes/'+str(node)+'/containers/'+web['Id']+'/stop',{});raise AssertionError('system stop accepted')
except urllib.error.HTTPError as e:check('self stop denied',e.code==400)
try:req('/entries',{'name':'csrf','url':'http://localhost'},headers={});raise AssertionError('CSRF accepted')
except urllib.error.HTTPError as e:check('CSRF denied',e.code==403)
req('/entries',{'name':'Deployment Ops','project':'operations','url':BASE});check('entry stored',len(req('/entries'))>0)
if '--deploy-test' in sys.argv:
 manifest={'application':'acceptance','image':'deployment-ops-acceptance:local','ports':{'8080':'19221'},'healthPort':8080,'healthPath':'/'}
 r=req('/nodes/'+str(node)+'/releases',manifest);check('deploy healthy',r['status']=='healthy')
 r=req('/nodes/'+str(node)+'/releases',manifest);check('upgrade healthy',r['status']=='healthy')
 rollback=req('/nodes/'+str(node)+'/rollback',{'release':r['id']});check('manual rollback',rollback['status']=='manually-rolled-back')
 manifest['healthPath']='/does-not-exist'
 try:req('/nodes/'+str(node)+'/releases',manifest);raise AssertionError('unhealthy release accepted')
 except urllib.error.HTTPError as e:check('unhealthy release rejected',e.code==400)
 history=req('/nodes/'+str(node)+'/releases');check('failed deployment automatically rolled back',history[0]['status']=='rolled-back')
import secrets
viewer_password=secrets.token_hex(16)
req('/users',{'username':'acceptance_viewer','password':viewer_password,'role':'viewer'})
admin_op=op
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
check('viewer login',req('/login',{'username':'acceptance_viewer','password':viewer_password})['role']=='viewer')
check('viewer read',len(req('/nodes'))>0)
try:req('/entries',{'name':'denied','url':'http://localhost'});raise AssertionError('viewer mutation accepted')
except urllib.error.HTTPError as e:check('viewer mutation denied',e.code==403)
op=admin_op
if '--import-test' in sys.argv:
 import hashlib
 archive=open('tests/fixture/image.tar','rb').read()
 for digest,expected in [('0'*64,400),(hashlib.sha256(archive).hexdigest(),200)]:
  request=urllib.request.Request(BASE+'/api/nodes/'+str(node)+'/images/import',data=archive,headers={'X-Ops-Request':'1','X-SHA256':digest})
  try:
   with op.open(request,timeout=90) as response:check('TAR checksum import',response.status==expected)
  except urllib.error.HTTPError as e:check('TAR invalid checksum denied',e.code==expected)
check('audited',any(a['outcome']=='success' for a in req('/audit')))
print(json.dumps(results,indent=2))
