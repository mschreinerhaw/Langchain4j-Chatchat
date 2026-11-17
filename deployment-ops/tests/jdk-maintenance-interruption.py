"""Controlled worker-kill acceptance for the dedicated JDK QA unit, never a business process."""
import json,os,re,subprocess,time
from pathlib import Path
import paramiko

root=Path(__file__).resolve().parents[1]
output=root/'target'/('jdk-maintenance-interrupt-'+str(time.time_ns()));output.mkdir()
state='/mnt/disk01/deployment-ops-v1-validation/jdk-jar-state'
service='deployment-ops-jdk-runtime-qa'
release=root/'target/releases/deployment-ops-1.1.0-jdk-rc.11.zip'
digest=release.with_name(release.name+'.sha256').read_text().split()[0]
client=paramiko.SSHClient();client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('192.168.195.221',port=5221,username='root',password=os.environ['OPS_SSH_PASSWORD'])
environment=dict(os.environ,OPS_QA_URL='http://192.168.195.221:19270',OPS_TEST_ARTIFACTS=str(output))
browser=subprocess.Popen(['node',str(root/'tests/jdk-service-upgrade-browser.cjs')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env=environment,cwd=root)
browser.stdin.write(json.dumps({'adminPassword':os.environ['OPS_QA_PASSWORD'],'phase':'interrupt','file':str(release)}|{'sha256':digest}));browser.stdin.close()
try:
 for _ in range(180):
  target=output/'interrupt-target.json'
  if target.exists():break
  if browser.poll() is not None:raise RuntimeError(browser.stdout.read())
  time.sleep(1)
 else:raise RuntimeError('Maintenance submission timed out')
 ident=json.loads(target.read_text())['id'];assert re.fullmatch('[a-f0-9]{32}',ident)
 program='''import json,os,pathlib,subprocess,time
state=pathlib.Path(STATE);ident=IDENT;service=SERVICE
assert str(state)=='/mnt/disk01/deployment-ops-v1-validation/jdk-jar-state' and service=='deployment-ops-jdk-runtime-qa'
file=state/'maintenance/jobs'/(ident+'.json');unit='deployment-ops-jdk-maintenance-'+ident
for _ in range(6000):
 job=json.loads(file.read_text())
 if job.get('finishedAt'):raise RuntimeError('Worker completed before interruption; no signal sent')
 if job.get('phase')=='health_check':
  assert job.get('snapshotHashes') and job['service']==service
  command=subprocess.check_output(['/bin/systemctl','show',unit,'-p','ExecStart','--value'],text=True)
  assert str(state/'jars') in command and '--service-maintenance '+ident in command
  subprocess.run(['/bin/systemctl','kill','--kill-who=main','--signal=KILL',unit],check=True)
  break
 time.sleep(.02)
else:raise RuntimeError('Safe worker interruption point unavailable; no signal sent')
for _ in range(100):
 if subprocess.check_output(['/bin/systemctl','show',unit,'-p','MainPID','--value'],text=True).strip()=='0':break
 time.sleep(.05)
environment=dict(os.environ,OPS_DATA_DIR=str(state/'data'),OPS_PORT='19270',OPS_PLATFORM_SERVICE=service,PATH='/nonexistent')
jar='/mnt/disk01/deployment-ops-v1-validation/deployment-ops-1.1.0-jdk-rc.10.jar'
result=subprocess.run(['/opt/jdk-17.0.5/bin/java','-jar',jar,'--recover-maintenance',ident],env=environment,text=True,capture_output=True)
assert result.returncode==0,result.stdout+result.stderr
job=json.loads(file.read_text());assert job['recovery']['status']=='succeeded'
print(json.dumps({'passed':True,'job':ident,'actualWorkerKilled':True,'explicitJavaRecovery':True,'recovery':job['recovery']}))
'''.replace('state=pathlib.Path(STATE);ident=IDENT;service=SERVICE',f'state=pathlib.Path({state!r});ident={ident!r};service={service!r}')
 stdin,stdout,stderr=client.exec_command('python3 -',timeout=180);stdin.write(program);stdin.channel.shutdown_write();evidence=stdout.read().decode();errors=stderr.read().decode();status=stdout.channel.recv_exit_status()
 if status:raise RuntimeError(errors+evidence)
 (output/'interruption-proof.json').write_text(evidence);print(evidence)
 browser.wait(timeout=180);result=browser.stdout.read();print(result)
 if browser.returncode:raise RuntimeError('Browser recovery assertions failed')
 print('Evidence:',output)
finally:
 client.close()
 if browser.poll() is None:browser.terminate()
