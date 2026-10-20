"""Read official task telemetry and project failed/successful observations separately."""
import importlib.util
import json
from pathlib import Path
import urllib.parse
import urllib.request

ROOT = Path('/opt/runtime-os-docker-stage/evidence/runtime-os-refactor-20261020')
spec = importlib.util.spec_from_file_location('benchmark', Path('/tmp/runtime-refactor-benchmark.py'))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
token = None

def http(path, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('http://127.0.0.1:18180' + path, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    with client.open(request, timeout=30) as response:
        value = json.load(response)
    if value.get('code') not in (None, 0, 200):
        raise RuntimeError('Telemetry API rejected request')
    return value['data']

def main():
    global token
    auth = http('/api/v1/enterprise/auth/login', json.loads((ROOT.parent/'cline-monitor-20261019/stage-auth.json').read_text()))
    token = auth['token']
    tenant = urllib.parse.quote(auth['user']['tenantId'])
    cases = {'baseline': ROOT/'baseline/run-1.json', 'candidate': ROOT/'candidate/run-1.json',
             'simple-data': ROOT/'simple-data-live.json', 'simple-data-fixed': ROOT/'simple-data-fixed-live.json'}
    metrics = {}
    for label, path in cases.items():
        if not path.is_file():
            continue
        observed = json.loads(path.read_text())
        task = observed['taskId']
        parent = http('/api/v1/agent/tasks/' + urllib.parse.quote(task) + '?tenantId=' + tenant)
        events, cursor = [], 0
        for _ in range(64):
            page = http('/api/v1/agent/tasks/' + urllib.parse.quote(task) + '/events?tenantId=' + tenant
                        + '&limit=200&afterSequence=' + str(cursor))
            events.extend(page)
            if len(page) < 200:
                break
            next_cursor = max(int(event.get('sequence') or event.get('sequenceNumber') or 0) for event in page)
            if next_cursor <= cursor:
                raise RuntimeError('Telemetry pagination did not advance')
            cursor = next_cursor
        run = next((event.get('runId') for event in events if event.get('runId')), None)
        if not run:
            metrics[label] = {'taskId': task, 'status': parent.get('canonicalState'), 'measurementIncomplete': True}
            continue
        coding = http('/api/v1/agent/runtime/runs/' + run + '/coding')
        for child in coding.get('tasks', []):
            child_events, after = [], 0
            for _ in range(64):
                page = http('/api/v1/agent/runtime/runs/' + run + '/coding/' + child['taskId']
                            + '/events?limit=128&afterSequence=' + str(after))
                child_events.extend(page.get('events', []))
                if not page.get('hasMore'):
                    break
                after = page['nextSequence']
            child['events'] = child_events
        runtime = http('/api/v1/agent/runtime/runs/' + run)
        model_usage = [value for value in benchmark.decoded_records(runtime)
                       if value.get('contractVersion') == 'agent_model_usage_v1']
        diagnostics = benchmark.python_diagnostics(run)
        result = benchmark.project({'parent': parent, 'events': events, 'taskId': task,
                                    'elapsedSeconds': observed.get('elapsedSeconds', 0)}, coding, model_usage, diagnostics)
        if not parent.get('finishedAt') and 'elapsedSeconds' not in observed:
            result['endToEndMs'] = None
        if result.get('javaTokenUsageObserved', 0) < result.get('javaModelCalls', 0):
            result['javaInputTokens'] = result['javaOutputTokens'] = None
        result['javaModelTelemetryObserved'] = bool(model_usage)
        if not model_usage:
            for field in ('javaModelCalls', 'javaModelDurationMs', 'javaPlanningModelDurationMs',
                          'javaInputTokens', 'javaOutputTokens'):
                result[field] = None
        result['businessCorrectness'] = 'NOT_CERTIFIED_BY_EXECUTION'
        result['comparison'] = 'Failed/cancelled and successful tasks are separate; these workloads are not pooled.'
        metrics[label] = result
    (ROOT/'performance-attempts.json').write_text(json.dumps(metrics, indent=2))
    print(json.dumps({key: {field: value.get(field) for field in ('status', 'endToEndMs', 'javaModelCalls',
        'codingModelCalls', 'pythonExecutions', 'runtimeSemanticReviews')} for key, value in metrics.items()}), flush=True)

if __name__ == '__main__':
    main()
