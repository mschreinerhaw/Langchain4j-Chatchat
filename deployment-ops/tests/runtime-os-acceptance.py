import json,time,subprocess,urllib.request,urllib.error,http.cookiejar
from pathlib import Path
import argparse
p=argparse.ArgumentParser();p.add_argument('--archive',required=True,type=Path);p.add_argument('--credentials',required=True,type=Path);p.add_argument('--environment',required=True);p.add_argument('--address',required=True);p.add_argument('--port',type=int,default=19300);p.add_argument('--mcp-port',type=int,default=19301);args=p.parse_args()
root=Path(__file__).resolve().parents[1];work=args.archive.resolve().parent
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def req(path,data=None,raw=False):
 body=data if raw else json.dumps(data).encode() if data is not None else None
 try:
  with op.open(urllib.request.Request('http://127.0.0.1:19220/api'+path,data=body,headers={'X-Ops-Request':'1'}),timeout=240) as r:return json.load(r)
 except urllib.error.HTTPError as e:raise RuntimeError(str(e.code)+' '+e.read().decode())
values=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line)
req('/login',{'username':'admin','password':values['ADMIN_PASSWORD']});base='/nodes/'+str(req('/nodes')[0]['id'])
def wait_job(job,limit=1800):
 end=time.time()+limit;previous=None
 while time.time()<end:
  j=req(base+'/installations/'+job['id']);summary=(j['status'],[(s['id'],s['status']) for s in j['steps']])
  if summary!=previous:print('PROGRESS',j['environment'],summary,j['message'],flush=True);previous=summary
  if j['status'] not in ('queued','running','rolling_back','starting','stopping'):return j
  time.sleep(3)
 raise RuntimeError('Job timeout; executor retains durable progress')
archive=args.archive.resolve();digest=archive.with_suffix('.zip.sha256').read_text().split()[0]
if not any(p['digest']==digest for p in req(base+'/packages')):
 saved=work/(args.environment+'-upload.json')
 u=req(base+'/packages/uploads/'+json.loads(saved.read_text())['id']) if saved.exists() else req(base+'/packages/uploads',{'name':archive.name,'size':archive.stat().st_size,'sha256':digest})
 saved.write_text(json.dumps({'id':u['id']}));last=-1
 with archive.open('rb') as f:
  f.seek(u['offset'])
  while chunk:=f.read(4*1024**2):
   u=req(base+'/packages/uploads/'+u['id']+'/'+str(u['offset']),chunk,True);pct=int(u['offset']/archive.stat().st_size*100)
   if pct//10!=last:print('UPLOAD',pct,'percent',flush=True);last=pct//10
 if u['status'] not in ('verified','verifying'):req(base+'/packages/uploads/'+u['id']+'/commit',{})
 for _ in range(1800):
  u=req(base+'/packages/uploads/'+u['id'])
  if u['status']=='verified':break
  if u['status']=='failed':raise RuntimeError(u.get('message'))
  time.sleep(2)
 else:raise RuntimeError('Verification timeout')
print('Real Runtime OS signed package imported',flush=True)
spec={'package':digest,'environment':args.environment,'parameters':dict(json.loads(args.credentials.read_text()),serverAddress=args.address,httpPort=args.port,mcpPort=args.mcp_port)}
review=req(base+'/installations/preflight',spec);print('PREFLIGHT',json.dumps(review),flush=True);assert review['ok']
job=wait_job(req(base+'/installations',{'package':digest,'environment':spec['environment'],'reviewToken':review['reviewToken']}))
(work/(args.environment+'-result.json')).write_text(json.dumps(job,indent=2));assert job['status']=='succeeded',job['message']
print('COMPLETE RUNTIME OS INITIAL INSTALL AND AUTHENTICATED ADMIN VERIFICATION PASS',flush=True)
