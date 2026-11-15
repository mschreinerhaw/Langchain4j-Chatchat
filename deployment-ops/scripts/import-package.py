"""Host administrator shortcut for packages already on this server; same signature verification as Web import.
Runs only from the deployment-ops directory. This is never exposed as a Web shell endpoint.
"""
import argparse,getpass,http.cookiejar,json,os,re,subprocess,time,urllib.request,urllib.error
from pathlib import Path
from urllib.parse import urlsplit
p=argparse.ArgumentParser(description=__doc__);p.add_argument('archive',type=Path);p.add_argument('--url',default='http://127.0.0.1:19220');p.add_argument('--node',type=int,default=1);p.add_argument('--username',default='admin');p.add_argument('--password-env',default='OPS_PASSWORD');args=p.parse_args()
if urlsplit(args.url).hostname not in ('127.0.0.1','localhost','::1'):raise SystemExit('Host-copy import requires the local manager URL')
archive=args.archive.resolve();digest=archive.with_suffix(archive.suffix+'.sha256').read_text().split()[0]
if not re.fullmatch('[a-f0-9]{64}',digest):raise SystemExit('Invalid SHA-256 file')
password=os.environ.get(args.password_env) or getpass.getpass('Management password: ')
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def req(path,data=None):
 try:
  with op.open(urllib.request.Request(args.url.rstrip('/')+'/api'+path,data=json.dumps(data).encode() if data is not None else None,headers={'Content-Type':'application/json','X-Ops-Request':'1'}),timeout=240) as response:return json.load(response)
 except urllib.error.HTTPError as error:raise SystemExit(str(error.code)+' '+error.read().decode())
req('/login',{'username':args.username,'password':password});base='/nodes/'+str(args.node)
# This shortcut is local-only. A remote executor must use chunk upload on the Web page.
nodes=req('/nodes');node=next((n for n in nodes if n['id']==args.node),None)
if not node or node['url'].rstrip('/')!='http://executor:8091':raise SystemExit('Host-copy import is restricted to the local Compose executor')
if any(item['digest']==digest for item in req(base+'/packages')):print('Package is already verified');raise SystemExit(0)
u=req(base+'/packages/uploads',{'name':archive.name,'size':archive.stat().st_size,'sha256':digest})
if not re.fullmatch('[a-f0-9]{32}',u['id']):raise SystemExit('Unexpected upload identifier')
print('Copying offline media into persistent staging...',flush=True)
subprocess.run(['docker','compose','cp',str(archive),'executor:/packages/uploads/'+u['id']],cwd=Path(__file__).resolve().parents[1],check=True)
req(base+'/packages/uploads/'+u['id']+'/commit',{})
print('Verifying signed payloads and image IDs...',flush=True)
for _ in range(1800):
 state=req(base+'/packages/uploads/'+u['id'])
 if state['status']=='verified':print('Verified package: '+digest);break
 if state['status']=='failed':raise SystemExit(state.get('message','Verification failed'))
 time.sleep(2)
else:raise SystemExit('Timed out; inspect the saved upload status before retrying')
