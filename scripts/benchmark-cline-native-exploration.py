"""Two synthetic real-provider visual tasks through the existing exploration endpoint.

Uses the same immutable benchmark fixture. No Skill names, new prompt rules,
retry loop, outer Agent, permission change or execution entry is introduced.
Prepare before running; unknown execute responses are never resubmitted.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'run'])
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path)
    parser.add_argument('--auth', type=Path, required=True)
    parser.add_argument('--model', default='qwen3.8-flash')
    args = parser.parse_args()
    os.umask(0o077)
    root = args.root.resolve()
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    auth = json.loads(args.auth.read_text())

    def api(path, body=None):
        headers = {'Authorization': 'Bearer ' + auth['token'], 'X-Workspace-Id': 'data-exploration'}
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
        with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18180/api/v1' + path,
                data=data, headers=headers), timeout=1800) as response:
            value = json.load(response)
        if value.get('code') not in [200, 201, 202]:
            raise RuntimeError('Exploration API rejected request: ' + str(value.get('code')))
        return value['data']

    def save(name, value):
        (root / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))

    if args.command == 'prepare':
        if (root / 'prepare-intent.json').exists():
            raise RuntimeError('Existing prepare intent requires reconciliation, not replay')
        fixtures = json.loads(args.fixtures.read_text())
        save('fixtures.json', fixtures)
        question = ('这是合成数据验收，仅分析本次授权的 complex.csv，不联网、不安装依赖。'
            '按 ISO 周（周一到周日）计算两地区每周 amount、金额占比和数量加权单位值 sum(amount)/sum(quantity)，'
            '核对总额及日期覆盖，区分完整周和边界周。交付包含表格、趋势图、地区比较图和证据边界的图文报告。'
            '金额和数量为演示单位，自主选择适用方法。')
        save('prepare-intent.json', {'model': args.model, 'question': question,
            'scope': 'DIRECT_AUTHORIZED_FILE_ANALYSIS_IN_EXPLORATION_WITHOUT_OUTER_AGENT'})
        state = api('/data-science/explorations', {'title': 'Cline 原生 Skills 图文验收（合成）'})
        save('workspace-private.json', state)
        nodes = []
        for repeat in range(2):
            prior = {n['id'] for n in state['nodes']}
            state = api('/data-science/explorations/' + state['id'] + '/nodes', {
                'version': state['version'], 'parentId': '', 'fileIds': [fixtures['complex.csv']['id']],
                'question': question, 'modelName': args.model})
            new = [n for n in state['nodes'] if n['id'] not in prior]
            assert len(new) == 1
            nodes.append({'case': 'visual-' + str(repeat), 'nodeId': new[0]['id']})
            save('workspace-private.json', state)
            save('prepared.json', {'workspaceId': state['id'], 'nodes': nodes, 'model': args.model})
        print(json.dumps({'prepared': len(nodes), 'workspaceId': state['id'], 'syntheticOnly': True}))
        return

    prepared = json.loads((root / 'prepared.json').read_text())
    assert len(prepared['nodes']) == 2
    directory = root / 'native'
    directory.mkdir(exist_ok=True)
    worker = json.loads(subprocess.check_output(['docker', 'inspect', 'runtime-os-app-stage-coding-agent-1'], text=True))[0]
    save('native-worker.json', {'image': worker['Image'], 'name': worker['Config']['Image'], 'model': prepared['model']})
    question = json.loads((root / 'prepare-intent.json').read_text())['question']

    def execute(node):
        case = node['case']
        request = directory / (case + '-request.json')
        if request.exists():
            print(json.dumps({'case': case, 'status': 'PENDING_RECONCILIATION'}), flush=True)
            return
        request.write_text(json.dumps({'requestId': node['nodeId'], 'instruction': question,
            'modelName': prepared['model'], 'scope': 'EXISTING_EXPLORATION_VISUAL_CONTRACT'}))
        started = time.monotonic()
        try:
            state = api('/data-science/explorations/' + prepared['workspaceId'] + '/nodes/' + node['nodeId'] + '/execute', {})
            save(case + '-workspace-private.json', state)
            result = next(n for n in state['nodes'] if n['id'] == node['nodeId'])
            receipt = result.get('receipt') or {}
            elapsed = round((time.monotonic() - started) * 1000)
            (directory / (case + '-receipt.json')).write_text(json.dumps({'elapsedMs': elapsed, 'receipt': receipt}, ensure_ascii=False, indent=2))
            print(json.dumps({'case': case, 'status': receipt.get('status'), 'taskId': receipt.get('taskId'),
                'resultAvailable': receipt.get('resultAvailable'), 'visualReport': bool(receipt.get('visualReport')),
                'elapsedMs': elapsed}), flush=True)
        except Exception as error:
            (directory / (case + '-error.json')).write_text(json.dumps({'type': type(error).__name__,
                'message': str(error), 'elapsedMs': round((time.monotonic() - started) * 1000)}))
            print(json.dumps({'case': case, 'status': 'HTTP_ERROR', 'type': type(error).__name__}), flush=True)

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(execute, prepared['nodes']))


if __name__ == '__main__':
    main()
