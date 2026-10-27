"""Read selected task telemetry on the deployment host; export no prompts, SQL, rows, goals or credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def records(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from records(child)
    elif isinstance(value, list):
        for child in value:
            yield from records(child)


def measure(task, run, monitor):
    calls = {}
    stages = {}
    fingerprint = None
    for record in records(run):
        value = record.get('analysisEvidenceSnapshotFingerprint')
        if isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value):
            fingerprint = value
        if record.get('contractVersion') == 'agent_model_usage_v1':
            for call in record.get('calls', []):
                node = call.get('nodeName')
                if not isinstance(node, str) or len(node) > 512:
                    continue
                identity = ('JAVA:' + node, 'model-' + str(call.get('callId')))
                projected = {'phase': identity[0], 'operationId': identity[1],
                             'status': call.get('status'), 'durationMs': call.get('elapsedMs'),
                             'inputTokens': call.get('inputTokens'), 'outputTokens': call.get('outputTokens')}
                calls[identity] = projected
        if record.get('eventKind') == 'CODING_INPUT_READY' and isinstance(record.get('durationMs'), (int, float)):
            identity = ('DATASET_WRITE', str(record.get('taskId')))
            stages[identity] = {'phase': identity[0], 'operationId': identity[1],
                                'status': 'SUCCEEDED', 'durationMs': record['durationMs']}
    coding = [t for t in monitor.get('tasks', []) if t.get('runId') == task['runId']]
    for coding_task in coding:
        for call in coding_task.get('models', []):
            identity = ('CODING_MODEL', coding_task['taskId'] + ':model-' + str(call.get('ordinal')))
            usage = call.get('usage') or {}
            calls[identity] = {'phase': identity[0], 'operationId': identity[1],
                               'status': call.get('status'), 'durationMs': call.get('durationMs'),
                               'inputTokens': usage.get('inputTokens'), 'outputTokens': usage.get('outputTokens')}
        for event in coding_task.get('events', []):
            if event.get('type') != 'TOOL_FINISHED' or not isinstance(event.get('durationMs'), (int, float)):
                continue
            tool = event.get('toolName')
            if not isinstance(tool, str) or not re.fullmatch('[a-z_]{1,64}', tool):
                continue
            identity = ('CODING_TOOL:' + tool, coding_task['taskId'] + ':' + str(event.get('cursor')))
            stages[identity] = {'phase': identity[0], 'operationId': identity[1],
                                'status': event.get('status'), 'durationMs': event['durationMs']}
    request = run.get('request') or {}
    query = request.get('query')
    case_id = hashlib.sha256(json.dumps([request.get('agentId'), query], ensure_ascii=False).encode()).hexdigest() \
        if isinstance(query, str) else None
    started, finished = run.get('startedAt'), run.get('finishedAt')
    return {**task, 'caseId': case_id, 'snapshotSha256': fingerprint,
            'durationMs': max(0, finished - started) if isinstance(started, (int, float))
            and isinstance(finished, (int, float)) else None,
            'modelCalls': list(calls.values()), 'stages': list(stages.values()),
            'coverage': {'javaMeterAvailable': any(key[0].startswith('JAVA:') for key in calls),
                         'codingMonitorAvailable': bool(coding),
                         'allPhasesMeasured': False,
                         'note': 'Uninstrumented stages/provider-internal retries are not reconstructed or assumed zero.'}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--task-id', action='append', required=True)
    parser.add_argument('--database', default='runtime_os_acceptance_api')
    parser.add_argument('--monitor', type=Path,
                        default=Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert re.fullmatch('[A-Za-z0-9_]+', args.database)
    assert all(re.fullmatch('[A-Za-z0-9-]{1,128}', task) for task in args.task_id)

    def sql(query):
        value = subprocess.check_output(['docker', 'exec', '-u', 'postgres', 'postgres', 'psql',
                                        '-At', '-v', 'ON_ERROR_STOP=1', '-d', args.database, '-c', query], text=True)
        return json.loads(value)

    monitor = json.loads(args.monitor.read_text())
    runs = []
    for task_id in args.task_id:
        task = sql("select jsonb_build_object('taskId',task_id,'status',canonical_state,'runId',execution_attempt_id) "
                   "from agent_task_latest where task_id='" + task_id + "'")
        assert re.fullmatch('[A-Za-z0-9-]{1,128}', task['runId'])
        run = sql("select run_json from agent_runtime_run where run_id='" + task['runId'] + "'")
        runs.append(measure(task, run, monitor))
    args.output.write_text(json.dumps({'schemaVersion': 'runtime_audit.control_telemetry.v1', 'runs': runs,
                                      'businessDataExported': False}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'tasks': len(runs), 'output': str(args.output)}))


if __name__ == '__main__':
    main()
