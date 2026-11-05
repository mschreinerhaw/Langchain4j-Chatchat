"""Paired real-platform benchmark. Synthetic inputs, real model/Java/Python; no mocks.

On the deployment host: --root PRIVATE_DIR --auth AUTH_JSON prepare|run --variant baseline|candidate
Run each variant against its intended worker image, at rest between batches.
Pending requests are retained, never silently resubmitted after a lost response.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import base64
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'run'])
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--auth', required=True, type=Path)
    parser.add_argument('--variant', choices=['baseline', 'candidate', 'final', 'verified', 'recovery'])
    parser.add_argument('--model', default='qwen3.8-flash')
    parser.add_argument('--repeats', type=int, default=2)
    args = parser.parse_args()
    os.umask(0o077)
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    auth = json.loads(args.auth.read_text())

    def api(path, body=None, multipart=None):
        headers = {'Authorization': 'Bearer ' + auth['token'], 'X-Workspace-Id': 'data-science'}
        if multipart:
            boundary = 'coding-progress-' + uuid.uuid4().hex
            filename, content = multipart
            data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                    'Content-Type: application/octet-stream\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
            headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
        else:
            headers['Content-Type'] = 'application/json'
            data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        request = urllib.request.Request('http://127.0.0.1:18180/api/v1' + path, data=data, headers=headers)
        with urllib.request.urlopen(request, timeout=1800) as response:
            value = json.load(response)
        if value.get('code') not in [200, 201, 202]:
            raise RuntimeError(str(value.get('code')) + ': ' + str(value.get('message')))
        return value['data']

    def save(name, value):
        (root / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))

    if args.command == 'prepare':
        if (root / 'fixtures.json').exists():
            raise RuntimeError('Fixtures already prepared; reuse their immutable IDs')
        files = {
            'simple.csv': b'region,amount\nEast,10\nWest,14\nEast,22\n',
            'customers.csv': b'customer_id,region\n001,East\n002,West\n003,East\n',
            'orders.csv': b'order_id,customer_id,amount\nA,001,10\nB,002,14\nC,001,22\nD,003,6\n',
        }
        text = io.StringIO()
        writer = csv.writer(text)
        writer.writerow(['day', 'region', 'amount', 'quantity'])
        for day in range(1, 29):
            for index, region in enumerate(['East', 'West']):
                writer.writerow([f'2026-10-{day:02d}', region, day * (index + 1), day % 4 + 1])
        files['complex.csv'] = text.getvalue().encode()
        # Use the installed Sandbox runtime to produce genuine binary Parquet.
        source = "import pandas as pd,io,base64; b=io.BytesIO();pd.DataFrame({'region':['East','West','East'],'amount':[10,14,22]}).to_parquet(b,index=False);print(base64.b64encode(b.getvalue()).decode())"
        encoded = subprocess.check_output(['docker', 'run', '--rm', '--network', 'none', '--read-only',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,size=32m',
            '--entrypoint', 'python', 'chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022', '-c', source], text=True)
        files['simple.parquet'] = base64.b64decode(encoded.strip())
        fixtures = {}
        for filename, content in files.items():
            asset = api('/data-science/user-assets', multipart=(filename, content))
            fixtures[filename] = {'id': asset['id'], 'sha256': hashlib.sha256(content).hexdigest()}
        save('fixtures.json', fixtures)
        print(json.dumps({'prepared': len(fixtures), 'syntheticOnly': True}), flush=True)
        return

    if not args.variant or not 2 <= args.repeats <= 5:
        raise ValueError('Choose variant and 2..5 repeats')
    fixtures = json.loads((root / 'fixtures.json').read_text())
    directory = root / args.variant
    directory.mkdir(exist_ok=True)
    worker = json.loads(subprocess.check_output(['docker', 'inspect', 'runtime-os-app-stage-coding-agent-1'], text=True))[0]
    save(args.variant + '-worker.json', {'image': worker['Image'], 'name': worker['Config']['Image'], 'model': args.model})
    cases = {
        'csv': (['simple.csv'], '执行 Python 按 region 汇总 amount 并核对总和，输出分组表和总和。'),
        'parquet': (['simple.parquet'], '读取真实 Parquet 文件，执行 Python 按 region 汇总 amount 并核对总和，输出分组表。'),
        'join': (['customers.csv', 'orders.csv'], '保留客户号前导零，执行 Python 按 customer_id 多对一关联客户和订单，再按 region 汇总 amount，报告关联基数及总和核对。'),
        'complex': (['complex.csv'], '执行 Python 计算 region 每周 amount 总和、两地区每周占比及每周数量加权单位值（sum(amount)/sum(quantity)），验证分组与全表总和一致，说明日期覆盖及周边界。'),
        'repair': ([], '先以 project:{reuse:true} 原样执行，获取真实 SyntaxError；在同一任务做最小补丁，保留原计算，返回演示数值6，并满足 Runtime 报告输出合同。'),
    }

    def run_case(item):
        case, repeat = item
        key = f'{case}-{repeat}'
        receipt_file = directory / (key + '-receipt.json')
        request_file = directory / (key + '-request.json')
        if receipt_file.exists():
            return
        if request_file.exists():
            print(json.dumps({'case': key, 'status': 'PENDING_RECONCILIATION', 'variant': args.variant}), flush=True)
            return
        names, question = cases[case]
        request_id = 'progress-' + args.variant + '-' + key + '-' + uuid.uuid4().hex[:10]
        body = {'requestId': request_id, 'modelName': args.model,
            'instruction': '这是合成输入的执行基准，无业务数据。仅使用本次授权文件，不联网、不安装依赖。金额为演示单位。'
                + question + '无需额外图表；保留精确证据引用和真实执行回执，不把数据样例当作全体。',
            'fileIds': [fixtures[n]['id'] for n in names], 'contextIds': []}
        request_file.write_text(json.dumps(body, ensure_ascii=False))
        started = time.monotonic()
        try:
            if case == 'repair':
                prepared = api('/data-science/python/code-reuse', {'requestId': request_id, 'modelName': args.model,
                    'instruction': body['instruction'], 'entryPath': 'main.py', 'dataFileIds': [], 'skillIds': [],
                    'references': [{'kind': 'SNIPPET', 'path': 'main.py', 'content': 'value = (\nprint(sum([1, 2, 3]))\n',
                        'sha256': hashlib.sha256(b'value = (\nprint(sum([1, 2, 3]))\n').hexdigest()}]})
                (directory / (key + '-prepared.json')).write_text(json.dumps(prepared))
                receipt = api('/data-science/python/code-reuse/' + request_id + '/execute',
                    {'snapshotSha256': prepared['snapshotSha256'], 'authorizeExecution': True})
                state = api('/data-science/python/code-reuse/' + request_id)
                (directory / (key + '-state.json')).write_text(json.dumps(state, ensure_ascii=False))
            else:
                receipt = api('/data-science/user-assets/analyses', body)
            elapsed = round((time.monotonic() - started) * 1000)
            receipt_file.write_text(json.dumps({'elapsedMs': elapsed, 'receipt': receipt}, ensure_ascii=False, indent=2))
            print(json.dumps({'case': key, 'variant': args.variant, 'elapsedMs': elapsed,
                'taskId': receipt.get('taskId'), 'status': receipt.get('status'), 'resultAvailable': receipt.get('resultAvailable')}), flush=True)
        except Exception as error:
            (directory / (key + '-error.json')).write_text(json.dumps({'elapsedMs': round((time.monotonic()-started)*1000),
                'type': type(error).__name__, 'message': str(error)}))
            print(json.dumps({'case': key, 'variant': args.variant, 'status': 'FAILED', 'errorType': type(error).__name__}), flush=True)

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(run_case, [(case, repeat) for repeat in range(args.repeats) for case in cases]))


if __name__ == '__main__':
    main()
