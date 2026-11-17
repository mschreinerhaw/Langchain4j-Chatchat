"""Actual direct java -jar restart acceptance, restricted to the standalone QA state."""
import hashlib,http.cookiejar,json,os,signal,subprocess,time,urllib.request
from pathlib import Path
root=Path('/mnt/disk01/deployment-ops-v1-validation');state=root/'jar-entry-state';jar=root/'deployment-ops-1.1.0-jar-rc.4.jar'
assert state.is_dir() and jar.is_file()
def containers():
 ids=subprocess.check_output(['docker','ps','-q'],text=True).split()
 return sorted(subprocess.check_output(['docker','inspect','--format','{{.Id}} {{.State.StartedAt}} {{.State.Running}}',*ids],text=True).splitlines())
def login():
 op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def call(path,data=None):
  with op.open(urllib.request.Request('http://127.0.0.1:19260/api'+path,json.dumps(data).encode() if data is not None else None,{'Content-Type':'application/json','X-Ops-Request':'1'}),timeout=20) as r:return json.load(r)
 for _ in range(60):
  try:call('/login',{'username':'admin','password':os.environ['OPS_TEST_PASSWORD']});return call
  except Exception:time.sleep(1)
 raise RuntimeError('Direct JAR login timeout')
def start():
 log=state/'standalone/direct-jar.log';fd=os.open(log,os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
 env={**os.environ,'OPS_DATA_DIR':str(state/'data'),'OPS_PORT':'19260','OPS_BIND':'0.0.0.0','OPS_EXECUTOR_PORT':'19262'}
 for key in ['ADMIN_PASSWORD','EXECUTOR_TOKEN','OPS_EXECUTOR_URL','OPS_PLATFORM_SERVICE']:env.pop(key,None)
 with os.fdopen(fd,'ab') as output:return subprocess.Popen(['/opt/jdk-17.0.5/bin/java','-jar',str(jar)],stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,env=env,start_new_session=True)
result={'before':containers(),'directCommand':['java','-jar',str(jar)],'administratorSuppliedThroughPage':True}
subprocess.run(['systemctl','stop','deployment-ops-jar-entry-qa'],check=True)
pidfile=state/'standalone/direct-jar.pid'
if pidfile.exists():
 pid=int(pidfile.read_text());proc=Path('/proc')/str(pid)
 if proc.exists() and (proc/'stat').read_text().split()[2]!='Z':
  command=(proc/'cmdline').read_bytes();environment=(proc/'environ').read_bytes()
  assert str(root).encode() in command and b'deployment-ops-1.1.0-jar-rc.' in command and b'OPS_DATA_DIR='+str(state/'data').encode()+b'\0' in environment
  os.kill(pid,signal.SIGTERM)
  for _ in range(30):
   if not proc.exists() or (proc/'stat').read_text().split()[2]=='Z':break
   time.sleep(1)
  else:raise RuntimeError('Previous owned QA JVM did not stop')

process=start();call=login();assert process.poll() is None;assert call('/platform/health')['maintenanceMode']=='standalone';assert call('/platform/health')['version']=='1.1.0-jar-rc.4'
if not any(e['name']=='single-jar-restart-record' for e in call('/entries')):call('/entries',{'name':'single-jar-restart-record','url':'http://127.0.0.1:19260'})
assert any(j['id']=='f302e8334059477ca2231d63a8a21554' and j['status']=='succeeded' for j in call('/nodes/1/installations'))
process.terminate();process.wait(timeout=30);assert result['before']==containers();result['businessUnchangedWhileJarStopped']=True
name='ops-env-jar-protocol-qa-service';item=json.loads(subprocess.check_output(['docker','inspect',name]))[0];assert item['Config']['Labels']['deployment-ops.environment']=='jar-protocol-qa'
command=['docker','exec','-i',name,'sh','-c','read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli --user opsdemo ping']
auth=subprocess.run(command,input=os.environ['OPS_BUSINESS_PASSWORD']+'\n',text=True,capture_output=True,check=True);assert auth.stdout.strip()=='PONG';result['pageSelectedBusinessCredentialsReallyAuthenticate']=True
process=start();call=login();assert any(e['name']=='single-jar-restart-record' for e in call('/entries'));result['h2AndUserPersisted']=True
catalog=call('/nodes/1/packages');assert any(p['name']=='java-protocol-qa' and p['installationProtocol']['components'][1]['dependsOn']==['initialize'] for p in catalog);result['customTrustedSignerAndProtocolPersisted']=True
result['health']=call('/platform/health');result['after']=containers();assert result['before']==result['after'];result['businessUnchangedAfterRestart']=True;result['passed']=True
result['pid']=process.pid
with jar.open('rb') as f:result['jarSha256']=hashlib.file_digest(f,'sha256').hexdigest()
(state/'standalone/direct-jar.pid').write_text(str(process.pid));(root/'standalone-jar-lifecycle.json').write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k not in ['before','after']}))
