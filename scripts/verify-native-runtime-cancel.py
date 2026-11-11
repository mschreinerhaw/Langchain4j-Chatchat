"""Cancel a real Cline-authored long Python command through Java authority."""
import argparse,json,time,urllib.request,urllib.error
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--origin',required=True);p.add_argument('--auth-file',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--case',required=True);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=False);token=None
def save(n,v):(a.output/n).write_text(json.dumps(v,indent=2))
def call(path,body=None):
    req=urllib.request.Request(a.origin+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
    with urllib.request.urlopen(req,timeout=60) as r:
        v=json.load(r);return v.get('data',v)
try:
    token=call('/api/v1/enterprise/auth/login',json.loads(a.auth_file.read_text()))['token']
    task=call('/api/v1/coding/tasks',{'requestId':a.case,'instruction':'This is an explicit cancellation acceptance task. Autonomously create a meaningful Python source file in the workspace. It must print READY_TO_CANCEL with flush enabled, then sleep for 300 seconds in that same process, and only after the sleep create output/late-result.json and a Markdown report. Execute that Python program now using your native shell. Do not use background execution, suppress the sleep, or produce final artifacts before it finishes.','artifactTypes':['SOURCE','REPORT','RESULT'],'modelName':'qwen3.7-plus'});save('accepted-task.json',task)
    root='/api/v1/coding/tasks/runs/'+task['runId']+'/'+task['taskId'];monitor='/api/v1/coding-monitor/tasks/'+task['runId']+'/'+task['taskId'];running=None
    for _ in range(240):
        detail=call(monitor);entries=detail.get('executions',{});entries=entries.values() if isinstance(entries,dict) else entries
        running=next((x for x in entries if x.get('status')=='RUNNING' and 'python' in x.get('command','').lower()),None)
        if running:save('running-command.json',running);break
        state=call(root)
        if state['status'] not in ['CREATED','RUNNING']:raise RuntimeError('Task terminated before cancel: '+state['status'])
        time.sleep(1)
    if not running:raise RuntimeError('No real running Python observed')
    save('cancel-request.json',call(root+'/cancel',{}))
    for _ in range(90):
        state=call(root);save('state.json',state)
        if state['status']=='CANCELLED' and state['executionControl']['executorStopped']:break
        time.sleep(1)
    time.sleep(4);after=call(root);save('after-cancel.json',after);files=call(monitor+'/artifacts');save('registered-files.json',files)
    checks={'realPythonRunningBeforeCancel':bool(running),'javaCancelled':after['status']=='CANCELLED','actualExecutorStopConfirmed':after['executionControl']['executorStopped'] is True,'noFormalArtifacts':not any(f['kind'] in ['CODE','ARTIFACT'] for f in files['files']),'noCompletionAfterCancel':after['status']!='COMPLETED'}
    result={'passed':all(checks.values()),'checks':checks,'taskId':task['taskId'],'runId':task['runId'],'realModel':True};save('verification.json',result);print(json.dumps(result),flush=True)
    if not result['passed']:raise RuntimeError('Cancel checks failed')
except Exception as e:save('failure.json',{'type':type(e).__name__,'message':str(e),'failedSampleRetained':True});raise
