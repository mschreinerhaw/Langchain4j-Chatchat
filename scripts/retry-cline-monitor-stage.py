"""Retry an eligible published task through the existing task API, then observe its new attempt.
Run once on the isolated test host. Resume the verifier by task ID after observation interruption.
"""
import argparse
import json
import pathlib
import subprocess
import urllib.parse
import urllib.request


def main():
    p=argparse.ArgumentParser();p.add_argument('--auth-file',required=True);p.add_argument('--previous-report',required=True)
    p.add_argument('--output-prefix',required=True)
    p.add_argument('--mode',choices=['complete','restart-node'],default='restart-node')
    p.add_argument('--restart-phase',choices=['executing','validated'],default='executing');args=p.parse_args()
    prefix=pathlib.Path(args.output_prefix)
    submission=prefix.with_suffix('.submission.json')
    if submission.exists():raise RuntimeError('Retry already recorded; continue its verifier by task ID instead of resubmitting')
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}));token=None
    def http(path,body=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        q=urllib.request.Request('http://127.0.0.1:18180'+path,headers=headers,data=None if body is None else json.dumps(body).encode())
        with client.open(q,timeout=60) as response:
            value=json.load(response)
            if value.get('code') not in (None,0,200):raise RuntimeError('Official retry API rejected the request')
            return value['data']
    session=http('/api/v1/enterprise/auth/login',json.loads(pathlib.Path(args.auth_file).read_text()))
    token=session['token']
    old=json.loads(pathlib.Path(args.previous_report).read_text())
    status=http('/api/v1/published-agents/db_ops_assistant/questions/'+old['taskId']+'/status?eventLimit=1')
    if status['status'] not in ('FAILED','CANCELLED','DLQ','TIME_BUDGET_EXHAUSTED','MODEL_BUDGET_EXHAUSTED','TIMEOUT_CANCELLED','KILLED'):
        raise RuntimeError('Task is not eligible for retry under the existing API policy')
    tenant=session['user']['tenantId']
    if old.get('tasks') and old['tasks'][0]['outcome']['scope']['tenantId']!=tenant:
        raise RuntimeError('Retry report belongs to a different authenticated tenant')
    new=http('/api/v1/agent/tasks/'+old['taskId']+'/retry?tenantId='+urllib.parse.quote(tenant),{})
    if new['attemptNumber']<=status['attemptNumber']:raise RuntimeError('Retry did not advance the attempt')
    report={'scope':'Real existing task retry API; original task authorization preserved','previousTaskId':old['taskId'],
        'previousAttemptId':status['attemptId'],'previousAttemptNumber':status['attemptNumber'],
        **{k:new.get(k) for k in ('taskId','executionId','attemptId','attemptNumber','status')},'productionSwitched':False}
    submission.write_text(json.dumps(report,indent=2))
    with prefix.with_suffix('.log').open('w') as log:
        process=subprocess.Popen(['python3','-u',str(pathlib.Path(__file__).with_name('verify-cline-monitor-stage.py')),
            '--auth-file',args.auth_file,'--mode',args.mode,'--restart-phase',args.restart_phase,
            '--task-id',new['taskId'],'--expected-input-format','PARQUET',
            '--output',str(prefix.with_suffix('.json'))],stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    print(json.dumps({**report,'verifierPid':process.pid}))


if __name__=='__main__':main()
