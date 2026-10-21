"""Real, authenticated Excel/Cline acceptance. Uses synthetic records and existing published Agents.

Requires openpyxl in the verification environment; does not install anything in Runtime or Sandbox.
The auth file is private and must never be copied into the evidence directory.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import time
import urllib.request
import uuid


def objects(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from objects(item)
    elif isinstance(value, list):
        for item in value:
            yield from objects(item)
    elif isinstance(value, str) and value.startswith(('{', '[')):
        try:
            yield from objects(json.loads(value))
        except (ValueError, RecursionError):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', choices=['single', 'two', 'optional', 'required'], required=True)
    parser.add_argument('--base', default='http://192.168.195.221:18100/api/v1')
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--resume-task', help='Observe an already submitted task; never resubmit its analysis')
    args = parser.parse_args()
    auth = json.loads(args.auth.read_text(encoding='utf-8'))
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    args.output.mkdir(parents=True, exist_ok=True)

    def save(name, data):
        (args.output / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

    def call(path, data=None, content_type='application/json'):
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data).encode()
        request = urllib.request.Request(args.base + path, data=data, headers={
            'Authorization': 'Bearer ' + auth['token'], 'Content-Type': content_type})
        with client.open(request, timeout=30) as response:
            result = json.load(response)
        if result.get('code') not in (None, 200, 202, 0):
            raise RuntimeError(str(result)[:800])
        return result.get('data', result)

    from openpyxl import Workbook
    files = []
    rows = [[['id', 'amount'], [1, 10], [2, None], [2, -5], [4, 'invalid']],
            [['id', 'amount'], [1, 12], [3, 30], [4, 40]]]
    for index in range(0 if args.resume_task else 1 if args.case == 'single' else 2):
        workbook = Workbook()
        sheet = workbook.active
        sheet.title = 'Records'
        for row in rows[index]:
            sheet.append(row)
        output = io.BytesIO()
        workbook.save(output)
        content = output.getvalue()
        name = f'file-analysis-acceptance-{args.case}-{index + 1}.xlsx'
        (args.output / name).write_bytes(content)
        boundary = 'runtime-' + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                'Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode()
        body += content + f'\r\n--{boundary}--\r\n'.encode()
        registered = call('/interactions/user-assets', body, 'multipart/form-data; boundary=' + boundary)
        assert registered['sha256'] == hashlib.sha256(content).hexdigest()
        files.append(registered)
    if args.resume_task:
        files = json.loads((args.output / 'files.json').read_text(encoding='utf-8'))
    else:
        save('files.json', files)
    agent = call('/agents/workshop/financial_indicator')
    workflow_hash = hashlib.sha256(json.dumps(agent['workflowConfig'], sort_keys=True).encode()).hexdigest()
    query = ('分析附件 Excel 数据质量：报告记录数、amount 缺失数、非数值数、负数数、重复 id、'
             '有效数值合计；如果有两份文件，还需按 id 比较差异。第一份有效数值合计应由 Python 实际计算。'
             '这是合成验收数据，不涉及业务金额单位。用 Decimal 校验，简洁中文报告，不生成图表，不联网安装依赖。')
    if args.case in ('optional', 'required'):
        query = ('用Python分析两份Excel的amount质量并按id比较，统计缺失、非数值、负数、重复id和Decimal合计。'
                 '查询资产file_acceptance_missing_20261021的业务数据'
                 + ('作为可选补充，查询为空时记录空结果并完成Excel分析。' if args.case == 'optional' else
                    '与Excel核对，此业务数据为强依赖，缺少时停止核对并说明原因。'))
    nonce = 'file-analysis-' + args.case + '-' + uuid.uuid4().hex
    if args.resume_task:
        request = {'resumedTaskId': args.resume_task}
        task = {'taskId': args.resume_task}
    elif args.case == 'single':
        identity = auth['user']
        request = {'tenantId': identity.get('tenantId', identity.get('tenant_id')),
                   'userId': identity.get('username', 'admin'), 'agentId': 'general', 'mode': 'llm_chat',
                   'sessionId': str(uuid.uuid4()), 'query': query, 'modelName': 'qwen3.6-plus',
                   'idempotencyKey': nonce, 'toolInput': {'reportMode': 'off', '__userAssetIds': [f['id'] for f in files]}}
        save('request.json', request)
        task = call('/agent/tasks', request)
    else:
        request = {'question': query, 'sessionId': str(uuid.uuid4()), 'idempotencyKey': nonce,
                   'parameters': {'reportMode': 'off'}, 'attachmentIds': [f['id'] for f in files]}
        save('request.json', request)
        task = call('/published-agents/financial_indicator/questions', request)
        repeated = call('/published-agents/financial_indicator/questions', request)
        assert repeated['taskId'] == task['taskId'], 'Idempotency must prevent duplicate analysis submission'
    save('submission.json', {'request': request, 'response': task, 'workflowSha256Before': workflow_hash})
    print('SUBMITTED', args.case, task['taskId'], flush=True)
    tenant = request.get('tenantId') or auth['user'].get('tenantId')
    suffix = '?tenantId=' + tenant
    started = time.monotonic()
    states = []
    while time.monotonic() - started < args.timeout:
        snapshot = call('/agent/tasks/' + task['taskId'] + suffix)
        state = snapshot.get('canonicalState', snapshot.get('status'))
        if not states or states[-1]['state'] != state:
            states.append({'state': state, 'elapsedSeconds': round(time.monotonic() - started, 2)})
            print('STATE', args.case, state, flush=True)
        save('events.json', call('/agent/tasks/' + task['taskId'] + '/events' + suffix + '&limit=1000'))
        save('snapshot.json', snapshot)
        if state in ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'REJECTED']:
            break
        time.sleep(5)
    else:
        save('acceptance.json', {'status': 'TIMEOUT', 'states': states, 'taskId': task['taskId']})
        raise RuntimeError('Acceptance deadline exceeded; inspect/cancel the real task before redeploying')
    result = call('/agent/tasks/' + task['taskId'] + '/result' + suffix + '&timeoutMs=1000')
    save('result.json', result)
    after = call('/agents/workshop/financial_indicator')
    assert hashlib.sha256(json.dumps(after['workflowConfig'], sort_keys=True).encode()).hexdigest() == workflow_hash
    refs = sorted(set(ref for obj in objects(result) for ref in obj.get('attachmentReferences', []) if isinstance(ref, str)))
    native = sorted(set(obj.get('taskId') for obj in objects(result) if obj.get('resultAvailable') is True and obj.get('taskId')))
    save('acceptance.json', {'taskId': task['taskId'], 'case': args.case, 'state': state, 'states': states,
        'attachmentReferences': refs, 'codingTaskIds': native, 'workflowUnchanged': True,
        'expectedSourceReferences': ['user-asset:' + f['id'] + ':' + f['sha256'] for f in files]})
    print('FINISHED', args.case, state, flush=True)


if __name__ == '__main__':
    main()
