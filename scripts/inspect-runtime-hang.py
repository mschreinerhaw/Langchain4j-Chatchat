"""Inspect, cancel, or reconcile the authorized stage task; credentials remain on the host."""
import json
import pathlib
import urllib.request
import urllib.error
import sys

base = 'http://127.0.0.1:18180'
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
auth = json.loads(pathlib.Path('/opt/runtime-os-docker-stage/evidence/cline-monitor-20261019/stage-auth.json').read_text())
token = None
def http(path, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + path, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    with client.open(request, timeout=120) as response:
        return json.load(response)['data']
session = http('/api/v1/enterprise/auth/login', auth)
token = session['token']
parent = sys.argv[sys.argv.index('--task-id')+1] if '--task-id' in sys.argv else 'd826575b-9bdb-4e65-a9d5-b918f045359c'
run = http('/api/v1/published-agents/db_ops_assistant/questions/'+parent+'/status?eventLimit=1')['attemptId']
try:
    data = http('/api/v1/agent/runtime/runs/' + run + '/coding')
except urllib.error.HTTPError as pending:
    if pending.code != 404:
        raise
    data = {'tasks': []}
snapshot = pathlib.Path('/opt/runtime-os-docker-stage/evidence/runtime-hang-before.json')
if not snapshot.exists():
    snapshot.write_text(json.dumps(data, ensure_ascii=False, indent=2))
print(json.dumps([{'taskId': t['taskId'], 'status': t['outcome']['status'],
    'reasonCode': t['outcome'].get('reasonCode'), 'control': t['outcome']['executionControl'],
    'modelReceipts': len(t.get('modelReceipts', []))} for t in data['tasks']]))
if '--code' in sys.argv and data['tasks']:
    task = data['tasks'][0]['taskId']
    events = http('/api/v1/agent/runtime/runs/'+run+'/coding/'+task+'/events?afterSequence=0&limit=256')['events']
    ready = [e['payload']['proposedOperationId'] for e in events if e.get('payload', {}).get('type') == 'CODE_READY']
    if ready:
        print(http('/api/v1/agent/runtime/runs/'+run+'/coding/'+task+'/'+ready[-1]+'/code')['content'])
if '--timeline' in sys.argv:
    try:
        timeline = http('/api/v1/agent/runtime/runs/'+run+'/timeline?observationOffset=0&observationLimit=200&stepLimit=200&eventLimit=1')
    except urllib.error.HTTPError as pending:
        if pending.code != 404:
            raise
        timeline = {}
    for item in timeline.get('observations', []):
        if any(word in json.dumps(item).upper() for word in ('FAILED', 'REJECTED', 'MISSING', 'INCOMPLETE')):
            print(json.dumps(item, ensure_ascii=False)[:2500])
    for item in timeline.get('steps', []):
        print(json.dumps(item, ensure_ascii=False)[:1200])
if '--cancel' in sys.argv:
    result = http('/api/v1/agent/tasks/' + parent + '/cancel?tenantId=' + session['user']['tenantId'], {})
    print(json.dumps({'action': 'CANCEL_PARENT', 'taskId': parent, 'status': result.get('status')}))
    for task in data['tasks']:
        if task.get('canCancel'):
            result = http('/api/v1/agent/runtime/runs/' + run + '/coding/' + task['taskId'] + '/cancel', {})
            print(json.dumps({'action': 'CANCEL_CHILD', 'taskId': task['taskId'], 'status': result.get('status')}))
if '--reconcile' in sys.argv:
    for task in data['tasks']:
        if not task['outcome'].get('endedAt') and task['outcome']['executionControl'].get('stopReason'):
            result = http('/api/v1/agent/runtime/runs/' + run + '/coding/' + task['taskId'] + '/resume', {})
            print(json.dumps({'action': 'RECONCILE_STOPPED_CHILD', 'taskId': task['taskId'],
                'status': result.get('status'), 'control': result.get('executionControl')}))
