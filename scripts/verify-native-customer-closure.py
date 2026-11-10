"""Run on the authorized test host. Never replay a submitted customer request.

Raw customer evidence and auth stay in --root (mode 0700), outside Git.
This collects real Runtime/Native receipts; it does not certify their business claims.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import urllib.request
import urllib.error
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['submit', 'collect'])
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--base', default='http://127.0.0.1:18340/api/v1')
    parser.add_argument('--verify-account', action='store_true', help='Opt into the existing Runtime plan with customer source-metric verification')
    parser.add_argument('--model', help='Select an already authorized model for this request only; shared configuration is unchanged')
    args = parser.parse_args()
    os.umask(0o077)
    args.root.mkdir(mode=0o700, parents=True, exist_ok=True)
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = None

    def call(path, body=None):
        headers = {'Content-Type': 'application/json', 'X-Workspace-Id': 'data-science'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(args.base + path, headers=headers,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'))
        with client.open(request, timeout=120) as response:
            result = json.load(response)
        if result.get('code') != 200:
            raise RuntimeError(str(result.get('message')))
        return result.get('data')

    def save(name, value):
        (args.root / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

    auth = call('/enterprise/auth/login', {'username': os.environ['ACCEPTANCE_USERNAME'],
                                         'password': os.environ['ACCEPTANCE_PASSWORD']})
    token = auth['token']
    if args.action == 'submit':
        intent = args.root / 'dispatch-intent.json'
        if intent.exists():
            raise RuntimeError('Existing dispatch intent: collect it; never replay an uncertain request')
        request_id = 'account-closure-' + uuid.uuid4().hex
        body = {'mode': 'agent_chat', 'skillId': 'financial_indicator',
            'sessionId': request_id, 'idempotencyKey': request_id,
            'requestSource': 'native-customer-closure-20261110',
            'query': '查询客户号字符串 070200046604 的账户资产、持仓、交易和盈亏，并总结有数据支持的交易偏好。'
                     '本次从当前已授权的客户 API 服务取得业务记录，具体相关模板和参数由模型自主选择绑定，不指定模板 ID。'
                     '保留客户号前导零。先核对实际字段和各数据集来源，再计算；'
                     '不要猜测字段名或用零替代缺失值。日期以模板实际返回的范围为准，明确标注默认日期，不能称为今日数据。'
                     '请由原生 Cline 编写并实际运行 Python，交付完整中文分析报告、计算指标、数据范围和缺失口径。'
                     '本次交付精简的核心账户报告：资产概况、持仓汇总、交易统计、盈亏口径、单日行为和限制。'
                     '无需图表，无需逐条抄录所有明细或长篇背景；数据不足的结论如实标注，禁止编造。',
            'toolInput': {'resourceExplorationApproved': True, 'reportMode': 'off'}}
        if args.verify_account:
            body['toolInput']['responseContract'] = {'customerAccountVerification':
                {'profile': 'customer-account-v1', 'customerNumber': '070200046604'}}
        if args.model:
            body['modelName'] = args.model
        save('request.json', body)
        # Exclusive durable intent precedes dispatch, including on network failure.
        with intent.open('x', encoding='utf-8') as output:
            json.dump({'requestId': request_id, 'at': datetime.datetime.now(datetime.timezone.utc).isoformat()}, output)
        submission = call('/agent/tasks', body)
        save('submission.json', submission)
        print(json.dumps({'taskId': submission['taskId'], 'status': submission['status']}))
        return
    submission = json.loads((args.root / 'submission.json').read_text(encoding='utf-8'))
    task_id = submission['taskId']
    tenant = submission['tenantId']
    task = call('/agent/tasks/' + task_id + '?tenantId=' + tenant)
    events = call('/agent/tasks/' + task_id + '/events?tenantId=' + tenant + '&limit=1000&afterSequence=0')
    save('task.json', task)
    save('events.json', events)
    natives = []
    state_root = Path('/opt/cline-native-business-20261108/state')
    for file in state_root.glob('*/run.json'):
        run = json.loads(file.read_text())
        scope = run.get('contract', {}).get('scope', {})
        if scope.get('runId') == submission.get('attemptId'):
            save('native-private.json', run)
            natives.append({'workerId': run['workerId'], 'taskId': run['taskId'],
                'status': run['status'], 'workerStopped': run.get('workerStopped'),
                'activeOperations': run.get('activeOperations'),
                'lastReceiptStatus': (run.get('lastReceipt') or {}).get('status'),
                'validationStatus': (run.get('lastReceipt') or {}).get('validationStatus'),
                'codeVersions': len((run.get('codeLifecycle') or {}).get('versions', []))})
            if not (args.root / 'runtime-monitor-unavailable.json').exists():
                try:
                    runtime = call('/agent/runtime/runs/' + submission['attemptId'] + '/coding/' + run['taskId'])
                    save('runtime-private.json', runtime)
                except urllib.error.HTTPError as error:
                    save('runtime-monitor-unavailable.json', {'httpStatus': error.code,
                        'reason': 'Preserve the read failure; do not repeat a forbidden monitor request'})
    summary = {'taskId': task_id, 'status': task['status'], 'eventCount': len(events), 'native': natives}
    save('summary.json', summary)
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
