"""Observe a real published task until the parent ends, retaining unknown child states honestly."""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import urllib.error
import uuid

p = argparse.ArgumentParser()
p.add_argument('--retry-task')
p.add_argument('--task-id')
p.add_argument('--question-file')
p.add_argument('--output', required=True)
p.add_argument('--timeout', type=int, default=1500)
args = p.parse_args()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = None
def http(path, body=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer '+token
    request = urllib.request.Request('http://127.0.0.1:18180'+path, headers=headers,
        data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), method=method)
    with client.open(request, timeout=120) as response:
        value = json.load(response)
        code = value.get('code')
        if code not in (None, 0) and not 200 <= int(code) < 300:
            raise RuntimeError('Official API rejected '+path+': '+str(value.get('message') or value.get('msg')))
        return value['data']

auth = Path('/opt/runtime-os-docker-stage/evidence/cline-monitor-20261019/stage-auth.json')
session = http('/api/v1/enterprise/auth/login', json.loads(auth.read_text()))
token = session['token']
output = Path(args.output)
if args.task_id:
    task = args.task_id
elif output.exists():
    raise RuntimeError('Existing evidence must be resumed by --task-id')
elif args.retry_task:
    result = http('/api/v1/agent/tasks/'+args.retry_task+'/retry?tenantId='+session['user']['tenantId'], {})
    task = result['taskId']
else:
    question = Path(args.question_file).read_text(encoding='utf-8').strip()
    result = http('/api/v1/published-agents/db_ops_assistant/questions', {
        'question': question, 'sessionId': str(uuid.uuid4()), 'idempotencyKey': str(uuid.uuid4())})
    task = result['taskId']
report = {'taskId': task, 'originalTaskId': args.retry_task, 'startedAt': time.time()}
output.write_text(json.dumps(report, indent=2))
print(json.dumps({'stage': 'ACCEPTED', 'taskId': task}), flush=True)
deadline = time.monotonic()+args.timeout
previous = None
while time.monotonic() < deadline:
    state = http('/api/v1/published-agents/db_ops_assistant/questions/'+task+'/status?eventLimit=1')
    run = state['attemptId']
    try:
        children = http('/api/v1/agent/runtime/runs/'+run+'/coding')
    except urllib.error.HTTPError as pending:
        if pending.code != 404:
            raise
        children = {'tasks': []}  # Queue admission precedes Runtime run creation.
    report.update(runId=run, parent=state, coding=children, elapsedSeconds=round(time.time()-report['startedAt'], 1))
    summary = {'parent': state['status'], 'terminal': state.get('terminal'),
        'elapsedSeconds': report['elapsedSeconds'], 'children': [
            {'taskId': c['taskId'], 'status': c['outcome']['status'], 'reason': c['outcome'].get('reasonCode'),
             'modelReceipts': len(c.get('modelReceipts', [])), 'control': c['outcome']['executionControl']}
            for c in children['tasks']]}
    signature = json.dumps({k: v for k, v in summary.items() if k != 'elapsedSeconds'}, sort_keys=True)
    if signature != previous:
        print(json.dumps(summary), flush=True)
        previous = signature
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if state.get('terminal'):
        report['status'] = 'SUCCEEDED' if state.get('canonicalState') == 'SUCCEEDED' else 'TERMINATED_WITH_LIMITATION'
        report['answerAvailable'] = state.get('answerAvailable')
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({'result': report['status'], **summary}), flush=True)
        break
    time.sleep(3)
else:
    report['status'] = 'OBSERVATION_TIMEOUT'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    raise RuntimeError('Observation timed out; task remains available via --task-id')
