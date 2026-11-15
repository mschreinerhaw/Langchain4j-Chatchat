import os,json,time,subprocess,urllib.request,urllib.error,http.cookiejar
from pathlib import Path
root=Path(__file__).resolve().parents[1];work=root/'tests/installation-fixture'
def run(args):return subprocess.check_output(args,text=True).strip()
def baseline():
 ids=run(['docker','ps','-aq']).split();text=run(['docker','inspect','--format','{{.Id}} {{.Name}} {{.State.Status}} {{.State.StartedAt}}']+ids)
 return {v[0]:v[1:] for line in text.splitlines() if (v:=line.split()) and not v[1].startswith(('/deployment-ops','/ops-env-ops-acceptance'))}
import argparse
p=argparse.ArgumentParser();p.add_argument('--allow-executor-restart',action='store_true');p.add_argument('--port',type=int,default=19233);args=p.parse_args()
if not args.allow_executor_restart:raise SystemExit('This isolated acceptance test restarts deployment-ops-executor-1. Run only during a maintenance window, with --allow-executor-restart.')
prefix='ops-acceptance-'+str(int(time.time()));first_env=prefix+'-a';second_env=prefix+'-b'
before=baseline();op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def req(path,data=None,raw=False):
 body=data if raw else json.dumps(data).encode() if data is not None else None
 try:
  with op.open(urllib.request.Request('http://127.0.0.1:19220/api'+path,data=body,headers={'X-Ops-Request':'1'}),timeout=180) as response:return json.load(response)
 except urllib.error.HTTPError as error:raise RuntimeError(str(error.code)+' '+error.read().decode())
values={line.split('=',1)[0]:line.split('=',1)[1] for line in (root/'.env').read_text().splitlines() if '=' in line}
assert req('/login',{'username':'admin','password':values['ADMIN_PASSWORD']})['role']=='admin';node=req('/nodes')[0]['id'];base='/nodes/'+str(node)
def wait_job(job):
 deadline=time.time()+240
 while time.time()<deadline:
  value=req(base+'/installations/'+job['id'])
  if value['status'] not in ('queued','running','rolling_back','starting','stopping'):
   print('JOB',value['environment'],value['version'],value['status'],value['message'],flush=True);return value
  time.sleep(1)
 raise RuntimeError('Job timed out')
def install(digest,environment,port):
 spec={'package':digest,'environment':environment,'parameters':{'httpPort':port}}
 review=req(base+'/installations/preflight',spec);print('PREFLIGHT',environment,review,flush=True);assert review['ok'],review
 assert 'databasePassword' not in review['parameters']
 return req(base+'/installations',{'package':digest,'environment':environment,'reviewToken':review['reviewToken']})
packages={}
for version in ['1.0','1.1','2.0']:
 file=work/('acceptance-'+version+'.ops.zip');digest=file.with_suffix('.zip.sha256').read_text().strip();packages[version]=digest
 if any(p['digest']==digest for p in req(base+'/packages')):continue
 upload=req(base+'/packages/uploads',{'name':file.name,'size':file.stat().st_size,'sha256':digest})
 with file.open('rb') as stream:
  block=stream.read(4*1024**2);upload=req(base+'/packages/uploads/'+upload['id']+'/0',block,True)
  # Resume from durable server offset, not a client-side counter.
  assert req(base+'/packages/uploads/'+upload['id'])['offset']==len(block)
  while block:=stream.read(4*1024**2):upload=req(base+'/packages/uploads/'+upload['id']+'/'+str(upload['offset']),block,True)
 req(base+'/packages/uploads/'+upload['id']+'/commit',{})
 for _ in range(240):
  state=req(base+'/packages/uploads/'+upload['id'])
  if state['status']=='verified':break
  if state['status']=='failed':raise RuntimeError(state.get('message'))
  time.sleep(1)
 else:raise RuntimeError('Package import timeout')
 print('SIGNED PACKAGE IMPORT',version,'PASS',flush=True)
first=wait_job(install(packages['1.0'],first_env,args.port));assert first['status']=='succeeded',first
with urllib.request.urlopen('http://127.0.0.1:'+str(args.port)+'/health') as response:assert json.load(response)['value']=='persistent-data'
print('Database, transactional schema job, app and SQL verification PASS',flush=True)
upgrade=wait_job(install(packages['1.1'],first_env,args.port));assert upgrade['status']=='succeeded',upgrade
assert not any(s['id']=='schema' for s in upgrade['steps']);print('Compatible upgrade skips destructive initialization PASS',flush=True)
rolled=wait_job(req(base+'/installations/'+upgrade['id']+'/rollback',{}));assert rolled['status']=='rolled_back',rolled
with urllib.request.urlopen('http://127.0.0.1:'+str(args.port)+'/health') as response:value=json.load(response);assert value['version']=='1.0' and value['value']=='persistent-data'
print('Manual rollback verifies previous app and preserves data PASS',flush=True)
failed=wait_job(install(packages['2.0'],first_env,args.port));assert failed['status']=='rolled_back',failed
with urllib.request.urlopen('http://127.0.0.1:'+str(args.port)+'/health') as response:assert json.load(response)['version']=='1.0'
print('Failed health gate automatically restores compatible previous version PASS',flush=True)
second=install(packages['1.0'],second_env,args.port+1)
for _ in range(60):
 state=req(base+'/installations/'+second['id'])
 if any(s['id']=='database' and s['status']=='checking' for s in state['steps']):break
 time.sleep(.2)
run(['docker','restart','deployment-ops-executor-1']);time.sleep(2)
state=req(base+'/installations/'+second['id']);assert state['status']=='interrupted',state
second=wait_job(req(base+'/installations/'+second['id']+'/resume',{}));assert second['status']=='succeeded',second
print('Executor interruption and owned-resource resume PASS',flush=True)
assert before==baseline(),'Unrelated containers changed'
print('Unrelated container state/start times unchanged:',len(before),flush=True)
print(run(['df','-h','/']),flush=True)
(work/'accepted.json').write_text(json.dumps({'packages':packages,'first':first,'upgrade':upgrade,'failed':failed,'second':second},indent=2))
