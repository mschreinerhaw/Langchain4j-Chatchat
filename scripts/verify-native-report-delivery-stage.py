"""Real Provider checks through the existing exploration API; never replay unknown requests."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'run', 'collect'])
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, required=True)
    parser.add_argument('--auth', type=Path, required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--scenario', choices=['weekly','compact'], default='weekly')
    parser.add_argument('--cases', default='single,multi')
    parser.add_argument('--http-timeout', type=float, default=1800)
    args = parser.parse_args()
    os.umask(0o077)
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    token = json.loads(args.auth.read_text())['token']
    def request(path, body=None, raw=False):
        headers = {'Authorization': 'Bearer ' + token, 'X-Workspace-Id': 'data-exploration', 'Content-Type': 'application/json'}
        req = urllib.request.Request('http://127.0.0.1:18180/api/v1' + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers=headers)
        with urllib.request.urlopen(req, timeout=args.http_timeout if body is not None and path.endswith('/execute') else 60) as response:
            if raw:
                return response.read()
            value = json.load(response)
            if value.get('code') != 200:
                raise RuntimeError('API rejected request: ' + str(value.get('code')))
            return value['data']
    def save(name, value):
        (root / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))
    if args.command == 'prepare':
        if (root / 'prepare-intent.json').exists():
            raise RuntimeError('Existing preparation requires reconciliation, not replay')
        fixtures = json.loads(args.fixtures.read_text())
        input_name='simple.csv' if args.scenario=='compact' else 'complex.csv'
        save('prepare-intent.json', {'model': args.model, 'input': fixtures[input_name], 'syntheticOnly': True, 'scenario': args.scenario})
        state = request('/data-science/explorations', {'title': '第二阶段图文交付验收（合成）'})
        save('workspace-private.json', state)
        cases = []
        questions = {
            'single': '这是合成数据验收。仅分析本次授权的 complex.csv，按 region 汇总 amount 与 quantity，核对全表总和。交付汇总表和一张地区金额比较图，金额数量为演示单位，不联网不安装依赖。',
            'multi': '这是合成数据验收。仅分析本次授权的 complex.csv，按 ISO 周（周一到周日）汇总各地区 amount、数量及加权单位值 sum(amount)/sum(quantity)，核对总额与日期覆盖并区分边界周。交付包含表格、趋势图、地区比较图的图文报告，金额数量为演示单位，不联网不安装依赖。自主选择适用方法。'}
        if args.scenario=='compact':
            declaration='该合成 CSV 的生产者字段声明：region 为地区类别字符串；amount 为每条演示记录的金额，单位为演示单位，可按地区相加；不代表实际货币、真实交易或时间序列。'
            questions={
                'single':'这是合成数据验收。'+declaration+'仅分析本次授权的 simple.csv，按 region 汇总 amount 并核对总和，给出简洁分组表和一张地区金额比较图。不联网、不安装依赖。',
                'multi':'这是合成数据验收。'+declaration+'仅分析本次授权的 simple.csv，按 region 汇总 amount 并核对总和，给出简洁分组表、地区金额比较图与占比图。不联网、不安装依赖。自主选择适用方法。'}
        for case, question in questions.items():
            if case not in args.cases.split(','):
                continue
            before = {n['id'] for n in state['nodes']}
            state = request('/data-science/explorations/' + state['id'] + '/nodes', {'version': state['version'],
                'parentId': '', 'fileIds': [fixtures[input_name]['id']], 'question': question, 'modelName': args.model})
            node = next(n for n in state['nodes'] if n['id'] not in before)
            cases.append({'case': case, 'nodeId': node['id']})
            save('workspace-private.json', state)
            save('prepared.json', {'workspaceId': state['id'], 'cases': cases, 'model': args.model})
        print(json.dumps({'prepared': len(cases), 'workspaceId': state['id']}), flush=True)
        return
    prepared = json.loads((root / 'prepared.json').read_text())
    base = '/data-science/explorations/' + prepared['workspaceId']
    if args.command == 'run':
        def execute(case):
            name = case['case']
            if (root / (name + '-request.json')).exists():
                raise RuntimeError('Execution intent exists; do not replay ' + name)
            save(name + '-request.json', {'workspaceId': prepared['workspaceId'], 'nodeId': case['nodeId'], 'requestedAt': time.time()})
            start = time.monotonic()
            try:
                state = request(base + '/nodes/' + case['nodeId'] + '/execute', {})
                node = next(n for n in state['nodes'] if n['id'] == case['nodeId'])
                save(name + '-receipt-private.json', {'elapsedMs': round((time.monotonic() - start) * 1000), 'receipt': node.get('receipt', {})})
                print(json.dumps({'case': name, 'status': node.get('receipt', {}).get('status'), 'taskId': node.get('receipt', {}).get('taskId')}), flush=True)
            except Exception as error:
                if isinstance(error, urllib.error.HTTPError):
                    save(name + '-http-error-private.json', {'httpStatus': error.code, 'body': error.read().decode(errors='replace')})
                save(name + '-unknown.json', {'status': 'PENDING_RECONCILIATION', 'errorType': type(error).__name__, 'elapsedMs': round((time.monotonic() - start) * 1000)})
                print(json.dumps({'case': name, 'status': 'PENDING_RECONCILIATION', 'errorType': type(error).__name__}), flush=True)
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(execute, prepared['cases']))
        return
    state = request(base)
    results = []
    for case in prepared['cases']:
        state = request(base + '/nodes/' + case['nodeId'] + '/status')
        node = next(n for n in state['nodes'] if n['id'] == case['nodeId'])
        receipt = node.get('receipt', {})
        report = receipt.get('visualReport', {})
        artifacts = []
        for chart in report.get('charts', []):
            ref = chart.get('artifact')
            if not ref:
                continue
            content = request('/interactions/user-assets/' + ref['fileId'] + '/content', raw=True)
            metadata = request('/interactions/user-assets/' + ref['fileId'])
            digest = hashlib.sha256(content).hexdigest()
            valid = digest == ref['sha256'] and len(content) == ref['size'] and metadata.get('sha256') == digest \
                and metadata.get('size') == len(content) and metadata.get('state') == 'AVAILABLE' and metadata.get('fileName') == ref['fileName']
            owner_task = metadata.get('taskId') == receipt.get('taskId') == report.get('execution', {}).get('taskId')
            entry = {'chartId': chart['id'], 'kind': chart['kind'], 'fileId': ref['fileId'], 'fileName': ref['fileName'],
                     'mediaType': ref['mediaType'], 'size': len(content), 'sha256': digest,
                     'downloadMatchesArchive': valid, 'sameTask': owner_task,
                     'archiveReference': 'user-asset:' + ref['fileId'], 'generationStatus': metadata.get('state'),
                     'securityValidation': 'ADMITTED_BY_VISUAL_REPORT'}
            if chart['kind'] == 'plotly':
                figure = json.loads(content)
                entry['syntheticTraces'] = [{k: trace[k] for k in ['type', 'name', 'x', 'y', 'labels', 'values'] if k in trace} for trace in figure['data']]
            artifacts.append(entry)
            (root / (case['case'] + '-' + ref['fileName'])).write_bytes(content)
        minimum = 1 if case['case'] == 'single' else 2
        passed = receipt.get('resultAvailable') is True and report.get('status') == 'SUCCESS' and len(artifacts) >= minimum \
            and all(a['downloadMatchesArchive'] and a['sameTask'] for a in artifacts) \
            and len(artifacts) == len(report.get('charts', []))
        results.append({'case': case['case'], 'nodeId': case['nodeId'], 'taskId': receipt.get('taskId'),
            'executionStatus': receipt.get('status'), 'reportStatus': report.get('status'), 'status': 'PASS' if passed else 'FAIL',
            'artifacts': artifacts, 'validation': report.get('validation'), 'syntheticTables': report.get('tables', []),
            'syntheticMetrics': report.get('metrics', [])})
    save('delivery-public.json', {'scope': 'REAL_PROVIDER_FORMAL_EXPLORATION_OWNER_DOWNLOAD', 'workspaceId': prepared['workspaceId'], 'cases': results})
    print(json.dumps({'cases': [{'case': r['case'], 'status': r['status'], 'artifacts': len(r['artifacts'])} for r in results]}), flush=True)


if __name__ == '__main__':
    main()
