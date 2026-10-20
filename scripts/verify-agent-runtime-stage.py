"""Replay an authorized UI request through the official task API, without business rules."""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--auth-file', required=True)
parser.add_argument('--request-file', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--task-id')
parser.add_argument('--timeout', type=int, default=2400)
args = parser.parse_args()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = None


def http(path, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('http://127.0.0.1:18180' + path, headers=headers,
        data=None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'))
    with client.open(request, timeout=90) as response:
        value = json.load(response)
        if value.get('code') not in (None, 0, 200):
            raise RuntimeError('Official API rejected request: ' + str(value.get('message') or value.get('msg')))
        return value['data']


session = http('/api/v1/enterprise/auth/login', json.loads(Path(args.auth_file).read_text()))
token = session['token']
tenant = session['user']['tenantId']
output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
request = json.loads(Path(args.request_file).read_text(encoding='utf-8'))['request']
if args.task_id:
    task = args.task_id
elif output.exists():
    raise RuntimeError('Resume existing evidence with --task-id; do not duplicate work')
else:
    request.update(sessionId=str(uuid.uuid4()), idempotencyKey=str(uuid.uuid4()), resumeTaskId=None)
    submitted = http('/api/v1/agent/tasks', request)
    task = submitted['taskId']
report = {'taskId': task, 'agentId': request['agentId'], 'startedAt': time.time(), 'events': []}
output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({'stage': 'SUBMITTED', 'taskId': task, 'agentId': request['agentId']}), flush=True)
root = '/api/v1/agent/tasks/' + urllib.parse.quote(task)
query = '?tenantId=' + urllib.parse.quote(tenant)
deadline = time.monotonic() + args.timeout
cursor = 0
previous = None
terminal = {'SUCCEEDED', 'SUCCESS', 'PARTIAL', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'REJECTED', 'DLQ',
            'NO_PRESENTABLE_RESULT', 'TIME_BUDGET_EXHAUSTED', 'MODEL_BUDGET_EXHAUSTED', 'TIMEOUT_CANCELLED', 'KILLED'}
while time.monotonic() < deadline:
    state = http(root + query)
    events = http(root + '/events' + query + '&limit=200&afterSequence=' + str(cursor))
    report['events'].extend(events)
    for event in events:
        cursor = max(cursor, int(event.get('sequenceNumber') or event.get('sequence') or 0))
    report.update(parent=state, elapsedSeconds=round(time.time() - report['startedAt'], 1))
    status = state.get('canonicalState') or state.get('status')
    summary = {'taskId': task, 'status': status, 'events': len(report['events']), 'elapsedSeconds': report['elapsedSeconds']}
    if events:
        latest = events[-1]
        summary['eventType'] = latest.get('type')
        payload = latest.get('payload') or {}
        if isinstance(payload, str):
            payload = json.loads(payload)
        summary['message'] = str(payload.get('message', ''))[:180]
    if events or status != previous:
        print(json.dumps(summary, ensure_ascii=False), flush=True)
    previous = status
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if status in terminal:
        report['status'] = 'SUCCEEDED' if status in {'SUCCEEDED', 'SUCCESS'} else 'TERMINATED_WITH_LIMITATION'
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({'stage': report['status'], **summary}), flush=True)
        break
    time.sleep(3)
else:
    report['status'] = 'OBSERVATION_TIMEOUT'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    raise RuntimeError('Observation deadline reached; resume using the recorded task ID')
