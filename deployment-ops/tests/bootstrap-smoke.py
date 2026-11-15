import os,subprocess,json,time,urllib.request,http.cookiejar,shutil,stat
from pathlib import Path
root=Path(__file__).resolve().parents[1];fixture=root/'tests/bootstrap-check'
assert not fixture.exists(),'Inspect existing fixture before rerunning'
(fixture/'scripts').mkdir(parents=True)
compose=(root/'compose.yaml').read_text().replace('name: deployment-ops\n','name: deployment-ops-bootstrap-check\n',1).replace('OPS_NETWORK: deployment-ops_edge','OPS_NETWORK: deployment-ops-bootstrap-check_edge')
(fixture/'compose.yaml').write_text(compose)
for name in ['install-offline.sh','offline-images.sh']:shutil.copy2(root/'scripts'/name,fixture/'scripts'/name)
env={**os.environ,'OPS_PORT':'19224','OPS_BIND':'127.0.0.1'}
def run(args):
 r=subprocess.run(args,cwd=fixture,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 if r.returncode:raise RuntimeError(r.stdout)
 return r.stdout.strip()
try:
 print(run(['sh','scripts/install-offline.sh']),flush=True)
 values={line.split('=',1)[0]:line.split('=',1)[1] for line in (fixture/'.env').read_text().splitlines() if '=' in line}
 assert stat.S_IMODE((fixture/'.env').stat().st_mode)==0o600
 before=(fixture/'.env').read_bytes()
 op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def req(path,data=None):
  with op.open(urllib.request.Request('http://127.0.0.1:19224/api'+path,data=json.dumps(data).encode() if data is not None else None,headers={'X-Ops-Request':'1'}),timeout=30) as response:return json.load(response)
 for i in range(40):
  try:
   if req('/health')['database']=='H2 embedded':break
  except Exception:time.sleep(.5)
 else:raise RuntimeError('Fresh bootstrap health timeout')
 assert req('/login',{'username':'admin','password':values['ADMIN_PASSWORD']})['role']=='admin'
 req('/entries',{'name':'Fresh environment proof','project':'bootstrap','url':'http://localhost:19224'})
 assert set(run(['docker','compose','ps','--services','--status','running']).splitlines())=={'web','executor'}
 print('Fresh .env, local H2 database and exactly two services; no PostgreSQL service PASS',flush=True)
 print(run(['sh','scripts/install-offline.sh']),flush=True)
 assert before==(fixture/'.env').read_bytes();assert any(e['name']=='Fresh environment proof' for e in req('/entries'));print('Repeated bootstrap preserves credentials and existing management data PASS',flush=True)
finally:
 print(run(['docker','compose','down']),flush=True)
 for name in ['deployment-ops-bootstrap-check_ops-data','deployment-ops-bootstrap-check_ops-state']:
  project=run(['docker','volume','inspect','--format','{{index .Labels "com.docker.compose.project"}}',name])
  assert project=='deployment-ops-bootstrap-check'
  run(['docker','volume','rm',name])
 fixture.resolve().relative_to((root/'tests').resolve());shutil.rmtree(fixture)
 print('Removed only generated bootstrap fixture, containers and verified test volumes',flush=True)
