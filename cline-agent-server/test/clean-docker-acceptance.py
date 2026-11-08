"""Host-side verifier/driver sequencer; never repairs native-generated code.

Start live-http.py editor first, then run this with the isolated acceptance root
containing context/service, private/server.json, state, project and project-patch.
"""
import pathlib,json,subprocess,time,hashlib,urllib.request,traceback,datetime,sys
r=pathlib.Path(sys.argv[1]).resolve();service=r/'context/service';config=r/'private/server.json';container=sys.argv[2] if len(sys.argv)>2 else 'cline-agent-server-clean-poc'
def command(args,path):
 p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);path.write_bytes(p.stdout)
 if p.returncode:raise RuntimeError('Command failed exit '+str(p.returncode)+'; see '+str(path))
 return p
def wait_case(case,seconds):
 for _ in range(seconds):
  p=r/'evidence'/case/'summary.json'
  if p.exists():
   summary=json.loads(p.read_text());assert summary.get('status') in ['turn_completed','PASS'],summary;return summary
  time.sleep(1)
 raise TimeoutError(case+' real task did not finish')
def run_driver(name,args):
 print('Starting '+name,flush=True)
 command(['python3',str(service/'test'/args[0]),str(config)]+args[1:],r/('live-'+name+'.log'))
def verify(case,folder):
 out=r/'evidence'/case;project=r/folder/'coding-agent-node'
 def hashes():
  files=['src/monitor-client.ts','src/monitor-types.ts','README.md','test/monitor-client-parameters.test.js','dist/monitor-client.js']
  return [{'path':p,'sha256':hashlib.sha256((project/p).read_bytes()).hexdigest()} for p in files]
 before=hashes()
 prefix=['docker','exec','-w','/poc/'+folder+'/coding-agent-node',container]
 command(prefix+['node','scripts/build.mjs'],out/'independent-build.log')
 command(prefix+['node','--test','--test-concurrency=1','test/monitor-client-parameters.test.js','test/monitor-authority.test.js','test/node-monitor.test.js','test/monitor.test.js'],out/'independent-regression.log')
 command(prefix+['node','/poc/service/test/verify-real-project.mjs','/poc/'+folder+'/coding-agent-node'],out/'independent-verification.log')
 after=hashes();assert before==after,'Independent verification changed generated files'
 (out/'independent-results.json').write_text(json.dumps({'status':'PASS','buildExitCode':0,'regressionExitCode':0,'parameterVerificationExitCode':0,'generatedFilesUnchanged':True,'before':before,'after':after,'fixtureInputs':'Complete tracked fixture present before model task, no restoration or repair'},indent=2))
 summary=json.loads((out/'summary.json').read_text());summary['independentVerification']='PASS';(out/'summary.json').write_text(json.dumps(summary,indent=2))
 print(case+' independent validation PASS',flush=True)
try:
 editor=wait_case('editor',1200)
 assert all(editor['coreToolsObserved'][t] for t in ['read_files','editor','run_commands']),editor
 verify('editor','project')
 run_driver('search',['search-http.py',str(r/'evidence/search'),str(r/'evidence/editor/session.json')]);assert wait_case('search',2)['sameNativeSession']
 run_driver('patch',['live-http.py',str(r/'evidence/patch'),'apply_patch']);patch=wait_case('patch',2)
 assert all(patch['coreToolsObserved'][t] for t in ['read_files','apply_patch','run_commands']),patch
 verify('patch','project-patch')
 run_driver('controls',['controls-http.py',str(r/'evidence/controls'),str(r)])
 controls=json.loads((r/'evidence/controls/summary.json').read_text())
 for key in ['denial','lateApproval','cancelRunningNativeShell','sseDisconnectWhileRunning']:assert controls[key]=='PASS'
 # Observe beyond the real delayed mutation deadline, not just immediately after cancel.
 time.sleep(35)
 cfg=json.loads(config.read_text())
 def api(path):
  req=urllib.request.Request('http://127.0.0.1:'+str(cfg['port'])+path,headers={'Authorization':'Bearer '+cfg['apiToken']})
  with urllib.request.urlopen(req,timeout=10) as res:return {'status':res.status,'body':json.load(res)}
 inspect=json.loads(subprocess.check_output(['docker','inspect',container]))[0]
 markers=[r/'project/coding-agent-node'/name for name in ['poc-denied-marker.txt','poc-cancel-late.txt']]
 assert not any(p.exists() for p in markers)
 runtime=json.loads(subprocess.check_output(['docker','exec',container,'node','/poc/service/scripts/verify-runtime.mjs','/poc/runtime']))
 report={'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'PASS','live':api('/health/live'),'ready':api('/health/ready'),'dockerHealth':inspect['State']['Health']['Status'],'imageId':inspect['Image'],'nativeWorkerAndServiceAreImageFiles':not any(m['Destination'].startswith(('/poc/runtime','/poc/service','/not-mounted')) for m in inspect['Mounts']),'markersAbsentBeyond30SecondDeadline':True,'postCancelObservationSeconds':35,'runtime':runtime,'processesAfterCleanup':subprocess.check_output(['docker','top',container,'-eo','pid,ppid,comm'],text=True),'oomControl':subprocess.check_output(['docker','exec',container,'cat','/sys/fs/cgroup/memory/memory.oom_control'],text=True),'externalOperations':'none-configured; cancellation proof is local only'}
 assert report['dockerHealth']=='healthy' and report['nativeWorkerAndServiceAreImageFiles']
 (r/'evidence/final-clean-smoke.json').write_text(json.dumps(report,indent=2));print('COMPLETE: all clean-image real-task and control gates PASS',flush=True)
except BaseException:
 failure={'status':'FAIL','traceback':traceback.format_exc()};(r/'evidence/pipeline-failure.json').write_text(json.dumps(failure,indent=2));raise
