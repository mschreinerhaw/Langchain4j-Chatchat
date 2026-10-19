"""Recover an observed test operation through the official Runtime cancel/resume API only.
No database mutations, direct executor dispatch or fabricated stop evidence.
"""
import argparse
import json
import pathlib
import time
import urllib.parse
import urllib.request


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--auth-file',required=True)
    p.add_argument('--failed-report',required=True)
    p.add_argument('--output',required=True)
    args=p.parse_args()
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token=None
    def http(path,body=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request('http://127.0.0.1:18180'+path,
            data=None if body is None else json.dumps(body).encode(),headers=headers)
        with client.open(request,timeout=60) as r:
            value=json.load(r)
            if value.get('code') not in (None,0,200):raise RuntimeError('Runtime API rejected recovery')
            return value['data']
    token=http('/api/v1/enterprise/auth/login',json.loads(pathlib.Path(args.auth_file).read_text()))['token']
    failed=json.loads(pathlib.Path(args.failed_report).read_text())
    root='/api/v1/agent/runtime/runs/'+urllib.parse.quote(failed['runId'])+'/coding'
    initial=http(root)['tasks']
    if len(initial)!=1:raise RuntimeError('Expected the exact single failed acceptance operation')
    task=initial[0]['taskId']
    path=root+'/'+urllib.parse.quote(task)
    report={'scope':'Real Runtime cancel/resume with durable MCP no-start proof; no executor dispatch',
        'parentTaskId':failed['taskId'],'runId':failed['runId'],'taskId':task,'initial':initial[0]['outcome'],
        'productionSwitched':False,'states':[]}
    http(path+'/cancel',{})
    for _ in range(60):
        outcome=http(path+'/resume',{})
        report['states'].append({'status':outcome['status'],'executionControl':outcome['executionControl']})
        if outcome['status'] in ('CANCELLED','TIMED_OUT'):
            report['outcome']=outcome
            break
        time.sleep(2)
    outcome=report.get('outcome',{})
    control=outcome.get('executionControl',{})
    passed=outcome.get('status') in ('CANCELLED','TIMED_OUT') and control.get('executorStopped') and not control.get('reconciliationRequired')
    passed=passed and outcome.get('usage',{}).get('consumed',{}).get('codeExecutions')==0
    report['status']='PASSED' if passed else 'FAILED'
    report['events']=http(path+'/events?limit=128&afterSequence=0')['events']
    pathlib.Path(args.output).write_text(json.dumps(report,indent=2))
    print(json.dumps({'status':report['status'],'taskId':task,'terminal':outcome.get('status'),
        'executorStopped':control.get('executorStopped'),'codeExecutions':outcome.get('usage',{}).get('consumed',{}).get('codeExecutions')}))
    if not passed:raise SystemExit(1)


if __name__=='__main__':main()
