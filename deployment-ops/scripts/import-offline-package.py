"""Import local offline media through authenticated platform verification, without LAN retransmission."""
import argparse,errno,getpass,hashlib,http.cookiejar,json,os,re,shutil,sys,time,urllib.request,uuid
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'executor'))
from platform_maintenance import environment
p=argparse.ArgumentParser();p.add_argument('--config',type=Path,required=True);p.add_argument('--file',type=Path,required=True);p.add_argument('--sha256',required=True);p.add_argument('--node',type=int,default=1);p.add_argument('--username',default='admin');a=p.parse_args()
if os.geteuid()!=0:raise SystemExit('Root is required for local media staging; the administrator must also authenticate')
if not re.fullmatch('[a-f0-9]{64}',a.sha256):raise SystemExit('A reviewed SHA-256 is required')
values=environment(a.config);packages=Path(values['OPS_PACKAGES']).resolve();source=a.file.resolve()
if not Path('/mnt/disk01').is_mount() or Path('/mnt/disk01') not in packages.parents:raise SystemExit('Required real data mount unavailable')
if not source.is_file():raise SystemExit('Offline media is not a regular file')
with source.open('rb') as stream:
    if hashlib.file_digest(stream,'sha256').hexdigest()!=a.sha256:raise SystemExit('Local offline media SHA-256 mismatch')
base='http://127.0.0.1:'+values.get('OPS_PORT','19240')+'/api';op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(path,data=None):
    with op.open(urllib.request.Request(base+path,json.dumps(data).encode() if data is not None else None,headers={'Content-Type':'application/json','X-Ops-Request':'1'}),timeout=120) as r:return json.load(r)
call('/login',{'username':a.username,'password':os.getenv('OPS_ADMIN_PASSWORD') or getpass.getpass('Platform administrator password: ')})
node=next((n for n in call('/nodes') if n['id']==a.node),None)
if not node or node['url']!=values['OPS_EXECUTOR_URL']:raise SystemExit('Local staging requires the configured local executor node')
if any(item['digest']==a.sha256 for item in call('/nodes/'+str(a.node)+'/packages')):print(json.dumps({'digest':a.sha256,'status':'already_verified'}));raise SystemExit(0)
prefix='/nodes/'+str(a.node)+'/packages/uploads';upload=call(prefix,{'name':source.name,'size':source.stat().st_size,'sha256':a.sha256});staged=packages/'uploads'/upload['id']
if not staged.is_file() or staged.is_symlink() or staged.stat().st_size!=0:raise SystemExit('Local upload staging does not match the authenticated executor')
temporary=staged.with_name(staged.name+'.local-'+uuid.uuid4().hex)
try:
    try:os.link(source,temporary)
    except OSError as error:
        if error.errno not in (errno.EXDEV,errno.EPERM,errno.EACCES,errno.EOPNOTSUPP):raise
        with temporary.open('xb') as output,source.open('rb') as stream:
            os.chmod(temporary,0o600);shutil.copyfileobj(stream,output,1024**2)
    os.replace(temporary,staged)
finally:
    if temporary.exists():temporary.unlink()
call(prefix+'/'+upload['id']+'/commit',{});print('Authenticated offline signature and image verification started',flush=True)
for _ in range(900):
    status=call(prefix+'/'+upload['id'])
    if status['status']=='verified':print(json.dumps({'digest':a.sha256,'status':'verified','upload':upload['id']}));break
    if status['status']=='failed':raise SystemExit('Offline verification failed: '+status.get('message','inspect upload record'))
    time.sleep(2)
else:raise SystemExit('Verification timeout; query the retained upload before retrying')
