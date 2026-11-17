"""Verify configuration replacement, rollback and optional stop/start for an explicitly selected acceptance environment."""
import argparse,getpass,http.cookiejar,json,os,time,urllib.request,urllib.error
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--result',required=True,type=Path);p.add_argument('--credentials',required=True,type=Path);p.add_argument('--node',type=int,default=1);p.add_argument('--url',default='http://127.0.0.1:19220');p.add_argument('--new-port',required=True,type=int);p.add_argument('--new-mcp-port',required=True,type=int);p.add_argument('--allow-stop',action='store_true');args=p.parse_args()
initial=json.loads(args.result.read_text());secret=json.loads(args.credentials.read_text())['osAdminPassword'];password=os.environ.get('OPS_TEST_PASSWORD') or getpass.getpass('Management administrator password: ')
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def req(path,data=None):
 try:
  with op.open(urllib.request.Request(args.url.rstrip('/')+'/api'+path,data=json.dumps(data).encode() if data is not None else None,headers={'X-Ops-Request':'1','Content-Type':'application/json'}),timeout=240) as response:return json.load(response)
 except urllib.error.HTTPError as error:raise RuntimeError(str(error.code)+' '+error.read().decode())
req('/login',{'username':'admin','password':password});base='/nodes/'+str(args.node)
def wait(job):
 last=None
 for _ in range(600):
  job=req(base+'/installations/'+job['id'])
  if job['status']!=last:print(job['environment'],job['status'],flush=True);last=job['status']
  if job['status'] not in ('queued','running','rolling_back','starting','stopping'):return job
  time.sleep(3)
 raise RuntimeError('Timed out; inspect the retained node journal')
def identity(port,path):
 direct=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 with direct.open(urllib.request.Request('http://'+initial['parameters'].get('serverAddress','127.0.0.1')+':'+str(port)+path+'/login',data=json.dumps({'username':'admin','password':secret}).encode(),headers={'Content-Type':'application/json'}),timeout=30) as response:token=json.load(response)['data']['token']
 with direct.open(urllib.request.Request('http://'+initial['parameters'].get('serverAddress','127.0.0.1')+':'+str(port)+path+'/me',headers={'Authorization':'Bearer '+token}),timeout=30) as response:assert json.load(response)['data']['username']=='admin'
def containers():
 data=next(n['data']['containers'] for n in req('/inventory') if n['id']==args.node)
 return {c['Labels']['deployment-ops.component']:c['Id'] for c in data if c.get('Labels',{}).get('deployment-ops.environment')==initial['environment'] and c['Labels'].get('deployment-ops.kind')!='job' and c['Names'][0]=='/ops-env-'+initial['environment']+'-'+c['Labels']['deployment-ops.component']}
current=req(base+'/installations/'+initial['id']);assert current['active'] and current['status']=='succeeded','Choose an active successful acceptance installation'
initial=current
before=containers();spec={'package':initial['package'],'environment':initial['environment'],'parameters':{'httpPort':args.new_port,'mcpPort':args.new_mcp_port}}
review=req(base+'/installations/preflight',spec);assert review['ok'],review
upgraded=wait(req(base+'/installations',dict(package=spec['package'],environment=spec['environment'],reviewToken=review['reviewToken'])));assert upgraded['status']=='succeeded',upgraded['message']
assert not any(s['id'] in ['prepare','initialize-databases','search-security'] for s in upgraded['steps'])
after=containers();assert after['gateway']!=before['gateway'];assert all(after[k]==v for k,v in before.items() if k!='gateway')
identity(args.new_port,'/api/v1/enterprise/auth');identity(args.new_mcp_port,'/api/v1/admin/auth');print('PASS configuration replacement preserves services and both administrator accounts',flush=True)
rolled=wait(req(base+'/installations/'+upgraded['id']+'/rollback',{}));assert rolled['status']=='rolled_back',rolled['message'];assert containers()==before
old_port=int(initial['parameters']['httpPort']);old_mcp=int(initial['parameters']['mcpPort']);identity(old_port,'/api/v1/enterprise/auth');identity(old_mcp,'/api/v1/admin/auth');print('PASS rollback restores original container IDs, ingress and administrator accounts',flush=True)
if args.allow_stop:
 assert wait(req(base+'/installations/'+initial['id']+'/stop',{}))['status']=='stopped'
 started=wait(req(base+'/installations/'+initial['id']+'/start',{}));assert started['status']=='succeeded',started['message'];assert containers()==before
 identity(old_port,'/api/v1/enterprise/auth');identity(old_mcp,'/api/v1/admin/auth');print('PASS stop/start preserves services and database accounts',flush=True)
