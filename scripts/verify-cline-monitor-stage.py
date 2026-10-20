"""Real published-Agent acceptance on the isolated server. Credentials stay in a root-only auth file.

python3 verify-cline-monitor-stage.py --auth-file stage-auth.json --mode complete --output real-model.json
Modes: complete, qa, cancel, restart-node. No direct business database or model access is used.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

def coding_owned_publication(metadata):
    return (metadata.get('type') == 'final_summary'
            and metadata.get('workflow') == 'coding_owned_analysis'
            and metadata.get('modelCalls') == 0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--auth-file', required=True)
    parser.add_argument('--mode', choices=['complete', 'qa', 'cancel', 'restart-node'], default='complete')
    parser.add_argument('--output', required=True)
    parser.add_argument('--base-url', default='http://127.0.0.1:18180')
    parser.add_argument('--timeout', type=int, default=1200)
    parser.add_argument('--task-id', help='Continue observing an already accepted official task without resubmitting')
    parser.add_argument('--expected-input-format', choices=['PARQUET', 'CSV'], help='Assert the actual immutable input binding for every submitted operation')
    parser.add_argument('--restart-phase', choices=['executing', 'validated'], default='executing',
        help='Kill the worker during physical execution or after Java has validated the Python result')
    parser.add_argument('--question-file', help='UTF-8 acceptance request, submitted through the same published API')
    parser.add_argument('--recovery-driver', choices=['manual', 'automatic'], default='manual',
        help='Observe Runtime reconciliation or invoke the existing explicit resume control')
    args = parser.parse_args()
    if args.base_url not in ('http://127.0.0.1:18180', 'http://127.0.0.1:18100'):
        raise RuntimeError('Acceptance must target the loopback test service')
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = None

    def http(path, body=None, method=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(args.base_url + path, headers=headers,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), method=method)
        with client.open(request, timeout=90) as response:
            value = json.load(response)
            code = value.get('code')
            if code not in (None, 0) and not 200 <= int(code) < 300:
                raise RuntimeError('API rejected request: ' + path)
            return value['data']

    credentials = json.loads(pathlib.Path(args.auth_file).read_text())
    token = http('/api/v1/enterprise/auth/login', credentials)['token']
    question = ('这是知识问答：解释 InnoDB Buffer Pool 命中率的含义和局限。'
        '不要查询当前数据库、不要计算实例数据、不要调用 Python。') if args.mode == 'qa' else (
        '数据分析：使用授权模板查询 LiveData测试库_223 当前连接状态，委派 Coding Agent 用 Python '
        '统计 Sleep 和总连接数；仅输出一行聚合计数和限制说明，禁止复制原始记录。'
        '仅分析本次快照，不判断健康。')
    if args.mode in ('cancel', 'restart-node'):
        # Keep the acceptance request concise: the existing search DSL has a clause budget.
        question = ('数据分析：使用授权模板查询 LiveData测试库_223 当前连接状态，委派 Coding Agent 用 Python '
            '统计 Sleep 和总连接数；仅输出聚合计数，禁止复制原始记录。Python 读取本次快照后先 '
            'time.sleep(25)，再统计；仅分析本次快照，不判断健康。')
    if args.question_file:
        question = pathlib.Path(args.question_file).read_text(encoding='utf-8').strip()
        if not question or len(question.encode('utf-8')) > 8192:
            raise RuntimeError('Acceptance question must contain 1..8192 UTF-8 bytes')
    if args.task_id:
        existing = http('/api/v1/published-agents/db_ops_assistant/questions/' + args.task_id + '/status?eventLimit=1')
        submission = {'taskId': args.task_id, 'attemptId': existing['attemptId']}
    else:
        submission = http('/api/v1/published-agents/db_ops_assistant/questions',
            {'question': question, 'sessionId': str(uuid.uuid4()), 'idempotencyKey': str(uuid.uuid4())})
    parent, run = submission['taskId'], submission['attemptId']
    root = '/api/v1/agent/runtime/runs/' + urllib.parse.quote(run) + '/coding'
    status_path = '/api/v1/published-agents/db_ops_assistant/questions/' + parent + '/status?eventLimit=1'
    report = {'mode': args.mode, 'scope': 'Real published task API and configured provider; Sandbox execution verified by task receipts',
        'taskId': parent, 'runId': run, 'startedAt': time.time(), 'productionSwitched': False,
        'tasks': [], 'events': {}, 'codeSubmissions': [], 'nodeRestarted': False, 'cancelRequested': False,
        'inputBindings': [], 'expectedInputFormat': args.expected_input_format, 'restartPhase': args.restart_phase,
        'recoveryDriver': args.recovery_driver}
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2))

    save()
    print(json.dumps({'stage': 'SUBMITTED', 'taskId': parent, 'runId': run, 'mode': args.mode}), flush=True)
    cursor, event_sets, read_codes = {}, {}, set()
    last_summary, stop_at = None, time.monotonic() + args.timeout
    try:
        while time.monotonic() < stop_at:
            state = http(status_path)
            report['parent'] = {key: state.get(key) for key in ('status', 'canonicalState', 'terminal', 'answerAvailable', 'attemptNumber')}
            try:
                tasks = http(root)['tasks']
            except urllib.error.HTTPError as pending:
                if pending.code != 404:
                    raise
                tasks = []  # The official queue can acknowledge before its Runtime run is created.
            report['tasks'] = tasks
            for task in tasks:
                tid = task['taskId']
                events = event_sets.setdefault(tid, {})
                for _ in range(64):
                    page = http(root + '/' + urllib.parse.quote(tid) + '/events?limit=128&afterSequence=' + str(cursor.get(tid, 0)))
                    for event in page['events']:
                        events[event['eventId']] = event
                    cursor[tid] = page['nextSequence']
                    if not page['hasMore']:
                        break
                ordered = sorted(events.values(), key=lambda e: e['sequence'])
                report['events'][tid] = ordered
                for event in ordered:
                    payload = event.get('payload') or {}
                    operation = payload.get('proposedOperationId')
                    if payload.get('type') == 'CODE_READY' and operation and operation not in read_codes:
                        try:
                            code = http(root + '/' + urllib.parse.quote(tid) + '/' + operation + '/code')
                        except urllib.error.HTTPError as unavailable:
                            if unavailable.code in (404, 500, 502):
                                continue  # The event may precede Java's immutable code registration.
                            raise
                        digest = hashlib.sha256(code['content'].encode()).hexdigest()
                        if not code.get('redacted') and digest != payload.get('codeSha256'):
                            raise RuntimeError('Displayed source differs from submitted code hash')
                        if args.expected_input_format:
                            binding = http(root + '/' + urllib.parse.quote(tid) + '/inputs?operationId=' + operation)
                            if binding['binding'] != 'OPERATION_FROZEN' or not binding['snapshots']:
                                # Code proposal can become visible before execution freezes its inputs.
                                continue
                            for snapshot in binding['snapshots']:
                                manifest = snapshot.get('inputManifest', {})
                                if snapshot.get('format') != args.expected_input_format or manifest.get('format') != args.expected_input_format:
                                    raise RuntimeError('Actual input format differs from the requested acceptance format')
                                if manifest.get('schemaVersion') != 'runtime_os.coding.input.v1' or not manifest.get('parts'):
                                    raise RuntimeError('Immutable file manifest is missing')
                            report['inputBindings'].append({'taskId': tid, 'operationId': operation, **binding})
                        read_codes.add(operation)
                        report['codeSubmissions'].append({'taskId': tid, 'operationId': operation,
                            'sha256': payload.get('codeSha256'), 'displayedSourceSha256': digest,
                            'redacted': code.get('redacted'), 'lines': len(code['content'].splitlines())})
                active_python = False
                if args.mode in ('cancel', 'restart-node') and not (report['cancelRequested'] or report['nodeRestarted']):
                    # This adapter persists startIntent as RECOVERY_RECONCILED, not EXECUTION_STARTED.
                    # CODE_READY names the submitted operation before dispatch; Docker state is the
                    # independent proof that the exact scoped execution has actually started.
                    starts = [e for e in ordered if e.get('payload', {}).get('type') == 'CODE_READY'
                        and str(e['payload'].get('proposedOperationId', '')).startswith('cline-')]
                    if starts:
                        start = starts[-1]
                        scope = task['outcome']['scope']
                        operation_id = start['payload']['proposedOperationId']
                        identity = '\0'.join([scope['tenantId'], scope['userId'], run, tid, operation_id, str(start.get('attempt') or 1)])
                        container = 'chatchat-py-' + hashlib.sha256(identity.encode()).hexdigest()[:40]
                        inspected = subprocess.run(['docker', 'inspect', container], capture_output=True, text=True)
                        if inspected.returncode == 0:
                            physical = json.loads(inspected.stdout)[0]
                            active_python = physical['State']['Running']
                            if active_python:
                                report['physicalSandboxAtControl'] = {'operationId': operation_id, 'containerId': physical['Id'],
                                    'startedAt': physical['State']['StartedAt'], 'network': physical['HostConfig']['NetworkMode'],
                                    'readOnly': physical['HostConfig']['ReadonlyRootfs'], 'user': physical['Config']['User']}
                if args.mode == 'cancel' and active_python and not report['cancelRequested']:
                    http(root + '/' + urllib.parse.quote(tid) + '/cancel', {}, 'POST')
                    http('/api/v1/published-agents/db_ops_assistant/questions/' + parent, method='DELETE')
                    report['cancelRequested'] = True
                    report['cancelTarget'] = tid
                validated = any(e.get('payload', {}).get('type') == 'TOOL_FINISHED'
                    and e['payload'].get('tool') == 'python_execute' and e['payload'].get('status') == 'SUCCEEDED'
                    # Public events intentionally omit artifact references. Java only reports
                    # PASSED/INCONCLUSIVE here after collection; final admission is checked below.
                    and e['payload'].get('validationStatus') in ('PASSED', 'INCONCLUSIVE') for e in ordered)
                restart_ready = active_python if args.restart_phase == 'executing' else validated
                if args.mode == 'restart-node' and restart_ready and not report['nodeRestarted'] and not task['outcome'].get('endedAt'):
                    current = next(t for t in http(root)['tasks'] if t['taskId'] == tid)
                    if current['outcome'].get('endedAt'):
                        continue  # A restart after completion is not recovery acceptance.
                    report['codeExecutionsAtRestart'] = current['outcome']['usage']['consumed']['codeExecutions']
                    subprocess.run(['docker', 'kill', '--signal', 'KILL', 'runtime-os-app-stage-coding-agent-1'], check=True, capture_output=True)
                    subprocess.run(['docker', 'start', 'runtime-os-app-stage-coding-agent-1'], check=True, capture_output=True)
                    report['nodeRestarted'] = True
                    report['nodeRestartedAt'] = time.time()
                    report['restartTarget'] = tid
                reconciliation_ready = report['cancelRequested'] or report['nodeRestarted'] and (
                    args.restart_phase == 'validated' or time.time() - report['nodeRestartedAt'] >= 30)
                if args.mode in ('cancel', 'restart-node') and args.recovery_driver == 'manual' and reconciliation_ready and not task['outcome'].get('endedAt'):
                    # The existing SPI reconciles receipts and restores only a settled checkpoint; it never blindly retries.
                    http(root + '/' + urllib.parse.quote(tid) + '/resume', {}, 'POST')
            summary = (state['status'], tuple((t['taskId'], t['outcome']['status']) for t in tasks))
            if summary != last_summary:
                print(json.dumps({'stage': 'POLL', 'parent': state['status'], 'coding': [t['outcome']['status'] for t in tasks],
                    'codes': len(read_codes), 'mode': args.mode}), flush=True)
                last_summary = summary
            save()
            if state.get('terminal') and all(t['outcome'].get('endedAt') for t in tasks):
                break
            time.sleep(0.2 if args.mode == 'restart-node' and args.restart_phase == 'validated' and tasks else 2)
        else:
            report['status'] = 'TIMED_OUT_WAITING_FOR_ACCEPTANCE'
            http('/api/v1/published-agents/db_ops_assistant/questions/' + parent, method='DELETE')
            raise RuntimeError('Published task did not reach a terminal state within acceptance timeout')
        types = [e.get('payload', {}).get('type') for values in report['events'].values() for e in values]
        report['hasActualModelTelemetry'] = 'MODEL_RESOLVED' in types
        report['hasCodeReadyEvent'] = 'CODE_READY' in types
        report['hasArchivedCode'] = bool(report['codeSubmissions'])
        report['hasCompletedCoding'] = any(t['outcome']['status'] == 'COMPLETED' for t in report['tasks'])
        report['resultAdmitted'] = False
        report['javaSemanticReviewInvoked'] = False
        report['resultAdmissionScope'] = 'STRUCTURE_AND_AUTHORIZED_REFERENCES'
        if args.mode not in ('qa', 'cancel'):
            for offset in range(0, 6400, 200):
                timeline = http('/api/v1/agent/runtime/runs/' + urllib.parse.quote(run)
                    + '/timeline?observationOffset=' + str(offset) + '&observationLimit=200&stepLimit=1&eventLimit=1')
                observations = timeline.get('observations', [])
                for observation in observations:
                    metadata = observation.get('metadata') or {}
                    if metadata.get('eventKind') == 'CODING_SEMANTIC_REVIEW':
                        report['javaSemanticReviewInvoked'] = True
                    if coding_owned_publication(metadata):
                        report['resultAdmitted'] = True
                if len(observations) < 200:
                    break
        report['orderedUniqueEvents'] = all(len(values) == len({e['eventId'] for e in values}) and
            [e['sequence'] for e in values] == sorted(e['sequence'] for e in values) for values in report['events'].values())
        if args.mode == 'qa':
            passed = not report['tasks'] and bool(report['parent']['answerAvailable'])
        elif args.mode == 'cancel':
            passed = report['cancelRequested'] and any(t['outcome']['status'] == 'CANCELLED' for t in report['tasks'])
        else:
            passed = all(report[key] for key in ('hasActualModelTelemetry', 'hasCodeReadyEvent', 'hasArchivedCode', 'hasCompletedCoding', 'orderedUniqueEvents', 'resultAdmitted'))
            passed = passed and not report['javaSemanticReviewInvoked']
            passed = passed and bool(report['parent']['answerAvailable']) and report['parent']['canonicalState'] == 'SUCCEEDED'
            if args.mode == 'restart-node':
                passed = passed and report['nodeRestarted']
                if args.restart_phase == 'validated' and report['nodeRestarted']:
                    final = next(t for t in report['tasks'] if t['taskId'] == report['restartTarget'])
                    report['noExecutionReplayAfterRestart'] = final['outcome']['usage']['consumed']['codeExecutions'] == report['codeExecutionsAtRestart']
                    passed = passed and report['noExecutionReplayAfterRestart']
        if args.expected_input_format and args.mode != 'qa':
            passed = passed and bool(report['inputBindings'])
        report['status'] = 'PASSED' if passed else 'FAILED'
        save()
        print(json.dumps({key: report.get(key) for key in ('status', 'taskId', 'hasActualModelTelemetry', 'hasArchivedCode', 'hasCompletedCoding', 'nodeRestarted', 'cancelRequested')}), flush=True)
        if not passed:
            raise RuntimeError('Acceptance criteria did not pass; see the structured evidence')
    except Exception as failure:
        report.setdefault('status', 'FAILED')
        report['failureType'] = type(failure).__name__
        save()
        raise


if __name__ == '__main__':
    main()
