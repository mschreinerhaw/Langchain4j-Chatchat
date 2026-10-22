"""Authenticated smoke acceptance with real QA and synthetic attachment analysis.

Credentials are read from a private --auth-file, never from command arguments.
Creates two labeled test conversations and one synthetic CSV in the test account.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth-file', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--base-url', default='http://127.0.0.1:18180')
    parser.add_argument('--timeout', type=int, default=480)
    parser.add_argument('--analysis-agent', default='db_ops_assistant')
    parser.add_argument('--reuse-report', type=Path, help='Reuse successful QA checks on the same unchanged deployment')
    args = parser.parse_args()
    os.umask(0o077)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    token = None
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    report = {'status': 'RUNNING', 'fixture': 'Synthetic CSV, 3 records, CNY total 33.68', 'checks': {}}

    def call(path, body=None, content_type='application/json', method=None):
        data = body if isinstance(body, bytes) else None if body is None else json.dumps(body, ensure_ascii=False).encode()
        headers = {'Content-Type': content_type}
        if token: headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(args.base_url + '/api/v1' + path, data=data, headers=headers, method=method)
        try:
            with client.open(request, timeout=60) as response: payload = json.load(response)
        except urllib.error.HTTPError as error:
            (args.output_dir / 'http-error-private.json').write_bytes(error.read())
            raise RuntimeError('HTTP ' + str(error.code) + ' at ' + path) from None
        if payload.get('code', 200) not in (0, 200, 202): raise RuntimeError('Application rejected ' + path)
        return payload.get('data')

    def save(): (args.output_dir / 'business-acceptance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    def question(label, agent, text, attachment=None):
        endpoint = '/published-agents/' + agent + '/questions'
        request = {'question': text, 'sessionId': str(uuid.uuid4()), 'idempotencyKey': 'release-1.2-' + uuid.uuid4().hex}
        if attachment: request['attachmentIds'] = [attachment]
        task = call(endpoint, request)
        report[label] = {'taskId': task['taskId'], 'runId': task['attemptId'], 'agentId': agent}
        save(); print(json.dumps({'phase': 'SUBMITTED', 'case': label, 'taskId': task['taskId']}), flush=True)
        deadline, last = time.monotonic() + args.timeout, None
        while time.monotonic() < deadline:
            state = call(endpoint + '/' + task['taskId'] + '/status?eventLimit=1')
            if state.get('canonicalState') != last:
                last = state.get('canonicalState')
                print(json.dumps({'case': label, 'state': last}), flush=True)
            if state.get('terminal'): break
            time.sleep(3)
        else:
            # Only cancel this verifier's own task on timeout.
            call(endpoint + '/' + task['taskId'], method='DELETE')
            raise RuntimeError('Acceptance task timed out: ' + label)
        answer = call(endpoint + '/' + task['taskId'] + '/answer')
        (args.output_dir / (label + '-answer-private.json')).write_text(json.dumps(answer, ensure_ascii=False, indent=2))
        text = answer.get('answer') or ''
        report[label].update({'canonicalState': state.get('canonicalState'), 'answerCharacters': len(text),
                              'answerSha256': hashlib.sha256(text.encode()).hexdigest()})
        if state.get('canonicalState') != 'SUCCEEDED' or not answer.get('ready') or not text:
            raise RuntimeError('Published task did not produce a successful answer: ' + label)
        return task, text

    try:
        auth = call('/enterprise/auth/login', json.loads(args.auth_file.read_text()))
        token = auth['token']; user = auth['user']
        report['checks']['login'] = bool(token)
        call('/enterprise/auth/me'); report['checks']['authenticatedIdentity'] = True
        summaries = call('/conversations/user/' + user['id'] + '/summaries?limit=5')
        report['checks']['historyReadable'] = isinstance(summaries, (list, dict))
        report['historySampleCount'] = len(summaries) if isinstance(summaries, list) else None
        if args.reuse_report:
            previous = json.loads(args.reuse_report.read_text())
            if not previous.get('checks', {}).get('realQaAnswer'): raise RuntimeError('Previous QA did not pass')
            report['qa'] = previous['qa']; report['checks']['realQaAnswer'] = True
            prior_analysis = previous.get('analysis')
            if prior_analysis:
                key = 'previousAnalysis' if prior_analysis.get('canonicalState') == 'SUCCEEDED' else 'previousAnalysisFailure'
                report[key] = prior_analysis
        else:
            _, answer = question('qa', 'db_ops_assistant',
                '发布验证：这是知识问答，请简要解释数据库事务 ACID 的四个含义。只回答概念，不查询当前数据库，不执行代码。')
            report['checks']['realQaAnswer'] = all(letter in answer.upper() for letter in 'ACID')
        boundary = uuid.uuid4().hex
        csv = b'category,amount,currency\nalpha,11.23,CNY\nalpha,14.56,CNY\nbeta,7.89,CNY\n'
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="release-1.2-validation.csv"\r\n'
                'Content-Type: text/csv\r\n\r\n').encode() + csv + (f'\r\n--{boundary}--\r\n').encode()
        attachment = call('/published-agents/' + args.analysis_agent + '/attachments', body, 'multipart/form-data; boundary=' + boundary)
        report['checks']['syntheticAttachmentUploaded'] = attachment.get('state') == 'AVAILABLE'
        task, answer = question('analysis', args.analysis_agent,
            '发布验证：用 Python 实际读取附件 CSV，使用 Decimal 按 currency 汇总 amount，报告数据行数、币种合计和方法限制。'
            '仅分析本附件，不查询数据库，不需要图表，不输出原始逐行数据。', attachment['id'])
        report['checks']['aggregateCorrect'] = '33.68' in answer and 'CNY' in answer.upper()
        tasks = call('/agent/runtime/runs/' + task['attemptId'] + '/coding')['tasks']
        report['analysis']['codingTasks'] = [{'taskId': x['taskId'], 'status': x['outcome']['status'],
            'validationStatus': x['outcome']['validationStatus'], 'usage': x['outcome']['usage']['consumed']} for x in tasks]
        report['checks']['codingCompleted'] = any(x['outcome']['status'] == 'COMPLETED' for x in tasks)
        report['checks']['pythonExecuted'] = any(x['outcome']['usage']['consumed']['codeExecutions'] > 0 for x in tasks)
        report['checks']['realModelCalled'] = any(x['outcome']['usage']['consumed']['modelCalls'] > 0 for x in tasks)
        report['status'] = 'PASSED' if all(report['checks'].values()) else 'FAILED'
        save(); print(json.dumps(report, ensure_ascii=False), flush=True)
        if report['status'] != 'PASSED': raise RuntimeError('Business acceptance checks failed')
    except Exception as failure:
        report['status'] = 'FAILED'; report['failureType'] = type(failure).__name__; save(); raise


if __name__ == '__main__': main()
