"""Reconcile children of this acceptance's terminal parents via existing authorized APIs."""
import json
from pathlib import Path
import time
import urllib.request

root = Path('/opt/runtime-os-docker-stage')
evidence = root/'evidence/runtime-os-refactor-20261020'
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
base = 'http://127.0.0.1:18180/api/v1'
token = None
def http(path, body=None):
    request = urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
    with client.open(request,timeout=30) as response: return json.load(response)['data']
token = http('/enterprise/auth/login',json.loads((root/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))['token']
report = []
for file in sorted(evidence.glob('scenario*-before.json')):
    case = json.loads(file.read_text())
    state = http('/published-agents/'+case['agentId']+'/questions/'+case['taskId']+'/status?eventLimit=1')
    if not state['terminal']: raise RuntimeError('A live parent must not be interrupted by orphan cleanup')
    if not case.get('tasks'): continue
    path = '/agent/runtime/runs/'+case['runId']+'/coding'
    for task in http(path)['tasks']:
        endpoint = path+'/'+task['taskId']
        outcome = task['outcome']
        if not outcome.get('endedAt'):
            http(endpoint+'/cancel',{})
            for _ in range(15):
                outcome = http(endpoint+'/resume',{})
                if outcome.get('endedAt'): break
                time.sleep(1)
        report.append({'taskId':task['taskId'],'status':outcome['status'],
            'executorStopped':outcome['executionControl']['executorStopped'],
            'reconciliationRequired':outcome['executionControl']['reconciliationRequired']})
        if not outcome.get('endedAt') or outcome['executionControl']['reconciliationRequired']:
            raise RuntimeError('Child still requires execution reconciliation; retain evidence')
(evidence/'scenario-before-reconciliation.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report),flush=True)
