import subprocess,json,time,urllib.request,os,sys
from pathlib import Path
if '--confirm-isolated-system-test' not in sys.argv:raise SystemExit('Requires --confirm-isolated-system-test; injects a brief outage into deployment-ops only')
os.chdir(Path(__file__).resolve().parents[1])
def run(args,**kw):return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,**kw).stdout
def healthy():
 try:return json.load(urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:19220/api/health',timeout=3))['status']=='UP'
 except Exception:return False
def wait():
 for i in range(40):
  if healthy():return
  time.sleep(2)
 raise RuntimeError('health timeout')
def baseline():
 ids=run(['docker','ps','-aq']).split()
 rows=json.loads(run(['docker','inspect']+ids))
 return {r['Id']:(r['State']['Status'],r['State']['StartedAt']) for r in rows if not r['Name'].startswith(('/deployment-ops','/ops-app-acceptance'))}
before=baseline();print('unrelated baseline containers',len(before),flush=True)
print(run(['sh','scripts/lifecycle.sh','upgrade']),flush=True);wait();print('self upgrade PASS',flush=True)
try:
 run(['docker','tag','deployment-ops-acceptance:local','deployment-ops-web:1.0.0'])
 run(['docker','compose','up','-d','--no-deps','--no-build','--pull','never','--force-recreate','web'])
 time.sleep(2)
 assert not healthy();print('self failure injected PASS',flush=True)
finally:
 print(run(['sh','scripts/lifecycle.sh','recover']),flush=True)
wait();print('self image recovery PASS',flush=True)
backup=sorted(Path('backups').glob('*/COMPLETE'))[-1].parent/'data'
assert (backup/'deployment-ops.mv.db').is_file()
for line in Path('.env').read_text().splitlines():
 if line.startswith('DB_PASSWORD='):os.environ['DB_PASSWORD']=line.split('=',1)[1]
os.environ['DB_URL']='jdbc:h2:file:/data/deployment-ops;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;FILE_LOCK=NO'
print(run(['docker','run','--rm','--network','none','--mount','type=bind,source='+str(backup.resolve())+',target=/data,readonly','-e','DB_PASSWORD','-e','DB_URL','deployment-ops-web:1.0.0','java','-cp','classes:lib/*','VerifyBackup']),flush=True)
print('offline H2 backup opened in independent engine PASS',flush=True)
for container in ['deployment-ops-web-1','deployment-ops-executor-1']:run(['docker','network','disconnect','deployment-ops_edge',container])
try:
 code="""import urllib.request,json,http.cookiejar
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
base='http://web:8080/api'
assert json.load(op.open(base+'/health'))['status']=='UP'
r=urllib.request.Request(base+'/login',data=json.dumps({'username':'admin','password':TEST_PASSWORD}).encode())
assert json.load(op.open(r))['role']=='admin'
rows=json.load(op.open(base+'/inventory'));assert 'data' in rows[0]
print('isolated internal-network health/login/Docker inventory PASS')
"""
 code=code.replace('TEST_PASSWORD',repr(os.environ['OPS_PASSWORD']))
 print(run(['docker','exec','-i','deployment-ops-executor-1','python','-'],input=code),flush=True)
finally:
 for container in ['deployment-ops-executor-1','deployment-ops-web-1']:run(['docker','network','connect','deployment-ops_edge',container])
wait();assert before==baseline();print('unrelated container state and start times unchanged PASS',flush=True)
run(['docker','stop','-t','2','ops-app-acceptance']);print('acceptance service stopped; history retained',flush=True)
print(run(['df','-h','/']),flush=True)
