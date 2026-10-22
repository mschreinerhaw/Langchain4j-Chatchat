"""Run synthetic single/joint file analysis through an existing published Agent and prove Cline/Python execution.
Uses only the standard library, existing models and the existing Sandbox. No Runtime settings are changed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request
import uuid


def objects(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from objects(child)
    elif isinstance(value, str) and value.startswith(('{', '[')):
        try:
            yield from objects(json.loads(value))
        except (ValueError, RecursionError):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--case', choices=['single', 'joint'], required=True)
    parser.add_argument('--timeout', type=int, default=900)
    args = parser.parse_args()
    args.output.mkdir(mode=0o700, parents=True, exist_ok=True)
    auth = json.loads(args.auth.read_text())
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, body=None, content_type='application/json'):
        data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
        request = urllib.request.Request('http://127.0.0.1:18180/api/v1' + path, data=data,
            headers={'Content-Type': content_type, 'Authorization': 'Bearer ' + auth['token']})
        with client.open(request, timeout=90) as response:
            value = json.load(response)
        if value.get('code') not in (None, 0, 200, 202):
            (args.output / 'api-error-private.json').write_text(json.dumps(value))
            raise RuntimeError('Runtime API rejected ' + path)
        return value.get('data')

    def save(name, value):
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))

    before = call('/agents/workshop/' + urllib.parse.quote(args.agent))
    workflow_hash = hashlib.sha256(json.dumps(before['workflowConfig'], sort_keys=True).encode()).hexdigest()
    files = []
    contents = [b'id,amount\nA,10\nB,20\n']
    if args.case == 'joint':
        contents.append(b'id,amount\nA,3\nC,7\n')
    for index, content in enumerate(contents):
        boundary = uuid.uuid4().hex
        name = 'unified-acceptance-' + args.case + '-' + str(index + 1) + '.csv'
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                'Content-Type: text/csv\r\n\r\n').encode() + content + (f'\r\n--{boundary}--\r\n').encode()
        item = call('/interactions/user-assets', body, 'multipart/form-data; boundary=' + boundary)
        assert item['sha256'] == hashlib.sha256(content).hexdigest()
        files.append(item)
    save('files.json', files)
    session = str(uuid.uuid4())
    question = ('请用 Python 实际读取附件 CSV 并计算每个文件的行数和 amount 合计，展示计算结果。'
                '这是合成验收数据，只分析附件，不调用外部业务数据，不绘图，不联网安装依赖。')
    if args.case == 'joint':
        question += '还要对两份文件按 id 做外连接比较，给出匹配、仅第一份、仅第二份的 id 及 amount 差值。'
    request = {'question': question, 'sessionId': session, 'idempotencyKey': str(uuid.uuid4()),
               'parameters': {'reportMode': 'off'}, 'attachmentIds': [item['id'] for item in files]}
    task = call('/published-agents/' + urllib.parse.quote(args.agent) + '/questions', request)
    assert call('/published-agents/' + urllib.parse.quote(args.agent) + '/questions', request)['taskId'] == task['taskId']
    save('submission.json', {'request': request, 'response': task})
    print(json.dumps({'stage': 'SUBMITTED', 'case': args.case, 'taskId': task['taskId']}), flush=True)
    query = '?tenantId=' + urllib.parse.quote(auth['user']['tenantId'])
    started = time.monotonic()
    states = []
    while time.monotonic() - started < args.timeout:
        snapshot = call('/agent/tasks/' + task['taskId'] + query)
        state = snapshot.get('canonicalState', snapshot.get('status'))
        if not states or states[-1] != state:
            states.append(state)
            print(json.dumps({'stage': state, 'case': args.case}), flush=True)
        save('snapshot.json', snapshot)
        if state in ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'REJECTED']:
            break
        time.sleep(5)
    else:
        raise RuntimeError('Task still active at deadline; inspect the existing task before retrying or deploying')
    result = call('/agent/tasks/' + task['taskId'] + '/result' + query + '&timeoutMs=1000')
    events = call('/agent/tasks/' + task['taskId'] + '/events' + query + '&limit=1000')
    save('result.json', result)
    save('events.json', events)
    runs = call('/agent/runtime/runs?conversationId=' + urllib.parse.quote(session) + '&limit=50')
    observations = []
    monitors = []
    for run in runs:
        run_id = run.get('runId') or run.get('id')
        if not run_id:
            continue
        observations.extend(call('/agent/runtime/runs/' + run_id + '/observations?limit=1000'))
        monitors.append(call('/agent/runtime/runs/' + run_id + '/coding'))
    save('observations.json', observations)
    save('coding-journal.json', monitors)
    consumed = [row for row in objects(monitors) if isinstance(row.get('consumed'), dict)
                and row['consumed'].get('codeExecutions', 0) > 0]
    receipts = [row for row in objects(observations) if row.get('operation') == 'RUN_CODING'
                and row.get('status') == 'COMPLETED' and row.get('resultAvailable') is True
                and len(set(row.get('evidenceRefs', []))) >= 6]
    after = call('/agents/workshop/' + urllib.parse.quote(args.agent))
    unchanged = hashlib.sha256(json.dumps(after['workflowConfig'], sort_keys=True).encode()).hexdigest() == workflow_hash
    checks = {'taskSucceeded': state == 'SUCCEEDED', 'publishedWorkflowUnchanged': unchanged,
              'sandboxExecutionProven': bool(consumed), 'admittedCodingEvidence': bool(receipts),
              'attachmentHashesVerified': len(files) == len(contents)}
    summary = {'status': 'PASSED' if all(checks.values()) else 'FAILED', 'case': args.case,
               'taskId': task['taskId'], 'states': states, 'checks': checks,
               'expectedTotals': [30] if args.case == 'single' else [30, 10],
               'elapsedSeconds': round(time.monotonic() - started, 1)}
    save('acceptance.json', summary)
    print(json.dumps(summary), flush=True)
    if summary['status'] != 'PASSED':
        raise RuntimeError('Runtime evidence gate failed; inspect retained real task evidence')


if __name__ == '__main__':
    main()
