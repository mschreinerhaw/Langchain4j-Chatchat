"""Reconcile retained real analysis evidence and retrieve protected code/stdout without resubmitting work."""
import argparse
import json
from pathlib import Path
import urllib.parse
import urllib.request
import runpy
objects = runpy.run_path(str(Path(__file__).with_name('verify-unified-runtime.py')))['objects']

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--auth', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
auth = json.loads(args.auth.read_text())
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def call(path):
    req = urllib.request.Request('http://127.0.0.1:18180/api/v1' + path,
        headers={'Authorization': 'Bearer ' + auth['token']})
    with client.open(req, timeout=90) as response:
        result = json.load(response)
    assert result['code'] == 200
    return result['data']

submission = json.loads((args.output / 'submission.json').read_text())
session = submission['request']['sessionId']
observations, monitors, outputs, feedbacks = [], [], [], []
for run in call('/agent/runtime/runs?conversationId=' + urllib.parse.quote(session) + '&limit=50'):
    run_id = run.get('runId') or run.get('id')
    observations.extend(call('/agent/runtime/runs/' + run_id + '/observations?limit=1000'))
    monitor = call('/agent/runtime/runs/' + run_id + '/coding')
    monitors.append(monitor)
    seen = set()
    for row in objects(monitor):
        if row.get('schemaVersion') != 'runtime_os.execution_feedback.v1' or row.get('executionId') in seen:
            continue
        seen.add(row['executionId'])
        if row.get('status') != 'SUCCEEDED' or row.get('exitCode') != 0:
            continue
        feedbacks.append(row)
        entry = {'runId': run_id, 'codingTaskId': row['taskId'], 'executionId': row['executionId']}
        for kind in ['stdout', 'code']:
            artifact = call('/agent/runtime/runs/' + run_id + '/coding/' + row['taskId'] + '/' + row['executionId'] + '/' + kind)
            (args.output / (row['executionId'] + '-' + kind + '.json')).write_text(json.dumps(artifact, ensure_ascii=False, indent=2))
            entry[kind + 'Sha256'] = artifact.get('sha256')
            if kind == 'stdout':
                entry['stdout'] = artifact['content']
        outputs.append(entry)
receipts = [row for row in objects(observations) if row.get('codingEventType') == 'TASK_FINISHED'
            and row.get('eventState') == 'COMPLETED' and len(set(row.get('evidenceRefs', []))) >= 6]
consumed = [row for row in objects(monitors) if isinstance(row.get('consumed'), dict)
            and row['consumed'].get('codeExecutions', 0) > 0]
snapshot = call('/agent/tasks/' + submission['response']['taskId'] + '?tenantId=' + auth['user']['tenantId'])
summary = json.loads((args.output / 'acceptance.json').read_text())
summary['checks'].update(taskSucceeded=snapshot['canonicalState'] == 'SUCCEEDED',
    sandboxExecutionProven=bool(consumed), admittedCodingEvidence=bool(receipts) and bool(feedbacks),
    protectedCodeAndStdoutRetrieved=bool(outputs))
summary.update(status='PASSED' if all(summary['checks'].values()) else 'FAILED',
    evidenceParser='Durable CODING_TRACE TASK_FINISHED plus successful execution_feedback.v1',
    executions=outputs)
(args.output / 'verified-acceptance-private.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
print(json.dumps(summary, ensure_ascii=False))
assert summary['status'] == 'PASSED'
