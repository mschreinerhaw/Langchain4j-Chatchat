"""Cancel only the baseline task recorded by this refactor's own benchmark."""
import json
import argparse
from pathlib import Path
import time
import urllib.parse
import urllib.request

root = Path('/opt/runtime-os-docker-stage')
evidence = root/'evidence/runtime-os-refactor-20261020'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--group', choices=['baseline', 'candidate'], default='baseline')
args = parser.parse_args()
baseline = json.loads((evidence/args.group/'run-1.json').read_text())
task_id = baseline['taskId']
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = None
def http(path, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('http://127.0.0.1:18180' + path, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    with client.open(request, timeout=30) as response:
        payload = json.load(response)
    if payload.get('code') not in (None, 0, 200):
        raise RuntimeError('Official control API rejected request')
    return payload['data']

auth = http('/api/v1/enterprise/auth/login', json.loads((root/'evidence/cline-monitor-20261019/stage-auth.json').read_text()))
token = auth['token']
query = '?tenantId=' + urllib.parse.quote(auth['user']['tenantId'])
path = '/api/v1/agent/tasks/' + urllib.parse.quote(task_id)
terminal = {'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'REJECTED', 'DLQ'}
before = http(path + query)
start = time.monotonic()
requested = before.get('canonicalState') not in terminal
if requested:
    http(path + '/cancel' + query, {})
while True:
    current = http(path + query)
    if current.get('canonicalState') in terminal:
        break
    if time.monotonic() - start > 60:
        raise RuntimeError('Cancellation has not reached a terminal parent state')
    time.sleep(1)
report = {'taskId': task_id, 'before': before.get('canonicalState'),
          'after': current.get('canonicalState'), 'cancelRequested': requested,
          'cancelLatencyMs': int((time.monotonic()-start)*1000) if requested else None,
          'observationSecondsBeforeControl': baseline.get('elapsedSeconds'),
          'reason': 'Benchmark observation ended; retained as a failed/cancelled observation, not a successful performance sample.' if requested else 'Task was already terminal; no cancellation was performed.',
          'providerTerminationConfirmed': False}
(evidence/(args.group + '-cancel.json')).write_text(json.dumps(report, indent=2))
print(json.dumps(report), flush=True)
