"""Read-only stop census of the dedicated acceptance database and named Worker."""
import hashlib,importlib.util,json,os,subprocess,urllib.request
from pathlib import Path
os.umask(0o077)
r=Path('/var/tmp/runtime-native-coding-acceptance-20261107')
s=importlib.util.spec_from_file_location('projection',r/'project-evidence-projection.py');p=importlib.util.module_from_spec(s);s.loader.exec_module(p)
def inspect(name):return json.loads(subprocess.check_output(['docker','inspect',name]))[0]
db=inspect('postgres');username=dict(v.split('=',1) for v in db['Config']['Env'] if '=' in v)['POSTGRES_USER']
query="select distinct run_id from runtime_evidence_partition where run_id ~ '^native-[a-f0-9]{48}$' order by run_id"
ids=subprocess.check_output(['docker','exec','postgres','psql','-X','-U',username,'-d',p.DATABASE,'-At','-c',query],text=True).splitlines()
tasks=[];states={};pre_admission=[]
for task in ids:
 records=[]
 try:
  records=p.rows(task);state=p.journal(records,task);outcome=state['outcome'];states[task]=state
  started=[v for v in state['operations'].values() if v.get('startIntent')]
  pending=[{'operationId':k,'kind':v.get('kind')} for k,v in state['operations'].items() if v.get('startIntent') and not v.get('stopProofRef')]
  tasks.append({'taskId':task,'status':outcome['status'],'executionControl':outcome['executionControl'],
   'startedCounts':{kind:sum(v.get('kind')==kind for v in started) for kind in ['MODEL','TOOL','CODE']},'unresolvedStarted':pending})
 except Exception as error:
  # One previously documented reference startup rejection has only its input
  # seed and contract, never an accepted task. Do not call arbitrary missing
  # journals stopped or silently discard them.
  seed_only=task=='native-8d7d8791be00789fe7aeab29545ae2a264441623497e0595' and len(records)==2 and all(
   entry['kind']=='EVIDENCE' and entry['document'].get('evidenceType')=='CODING_NATIVE_PROJECT_INPUT'
   or entry['kind']=='EXEC_CHECKPOINT' and entry['document'].get('key')=='coding:native-contract:'+task for entry in records)
  if seed_only:
   pre_admission.append({'computedTaskIdentity':task,'classification':'DOCUMENTED_REFERENCE_STARTUP_REJECTION_BEFORE_TASK_ADMISSION',
    'acceptedTaskJournalPresent':False,'modelToolCodeReceipts':0,'recordKinds':[v['kind'] for v in records],
    'referenceEvidence':'docs/testing/evidence/native-coding-20261107/reference-startup-failure-public.json'})
  else:tasks.append({'taskId':task,'censusErrorType':type(error).__name__})
api=inspect('runtime-native-acceptance-api');mcp=inspect('runtime-native-acceptance-mcp');worker=inspect('runtime-native-acceptance-worker')
cfg=json.loads(dict(v.split('=',1) for v in api['Config']['Env'] if '=' in v)['SPRING_APPLICATION_JSON'])
req=urllib.request.Request('http://127.0.0.1:18291/readyz',headers={'Authorization':'Bearer '+cfg['chatchat.coding.cline.shared-secret']})
with urllib.request.urlopen(req,timeout=10) as response:ready=json.load(response)
sandboxes=subprocess.check_output(['docker','ps','-q','--filter','ancestor=sha256:a3a279fc391bda1bb5aefb62acd8ee56200215ce2905cfb83d189398c33c5828'],text=True).splitlines()
original=[]
for name in ['A','C','E-name']:
 before=json.loads((r/'phase4-failure-audit'/(name+'.json')).read_text());now=states[before['taskId']]['outcome']
 original.append({'case':name,'taskId':before['taskId'],'statusUnchanged':now['status']==before['originalStatus'],
  'usageUnchanged':now['usage']==before['usage'],'executionControlUnchanged':now['executionControl']==before['executionControl']})
terminal={'COMPLETED','PARTIAL','FAILED','CANCELLED','BUDGET_EXCEEDED'}
report={'scope':'READ_ONLY_ISOLATED_ACCEPTANCE_DATABASE_AND_FIXED_SANDBOX_IMAGE','database':p.DATABASE,
 'controllerSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'partitionCount':len(ids),'taskCount':len(tasks),
 'preAdmissionPartitions':pre_admission,
 'allTerminalAndStopped':all(v.get('status') in terminal and v.get('executionControl',{}).get('executorStopped') is True
  and v.get('executionControl',{}).get('reconciliationRequired') is False and not v.get('unresolvedStarted') for v in tasks),
 'workerReady':ready,'fixedSandboxRunning':sandboxes,'apiImage':api['Image'],'mcpImage':mcp['Image'],'workerImage':worker['Image'],
 'apiImageUnchanged':api['Image']=='sha256:21beb4fb86e09d648f868b0740b07877ebf3670cf9ef54911b3fd7ec04a967db',
 'mcpImageUnchanged':mcp['Image']=='sha256:18ad6f765878f165c13e470cdd72561987ad14cfacf60d122a5f7fa9583d3f8a',
 'originalFailedTasks':original,'tasks':tasks,'productionMutationsPerformed':False}
(r/'phase4-final-stop-census-public.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['tasks','originalFailedTasks']}))
assert report['allTerminalAndStopped'] and ready['activeSessions']==0 and not sandboxes
assert report['apiImageUnchanged'] and report['mcpImageUnchanged']
assert all(v['statusUnchanged'] and v['usageUnchanged'] and v['executionControlUnchanged'] for v in original)
