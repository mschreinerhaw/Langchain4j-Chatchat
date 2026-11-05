"""Read stopped-execution clocks from the owner's existing immutable asset API.

Run after collect-coding-progress.py. No execution, retries or model calls.
Private receipts stay under --root; exported timing metadata contains no data/code.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import statistics
import urllib.parse
import urllib.request


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--auth', type=Path, required=True)
    args = parser.parse_args()
    root = args.root
    auth = json.loads(args.auth.read_text())
    base = 'http://127.0.0.1:18180/api/v1/data-science/user-assets'

    def get(suffix):
        request = urllib.request.Request(base + suffix, headers={
            'Authorization': 'Bearer ' + auth['token'], 'X-Workspace-Id': 'data-science'})
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()

    def instant(value):
        return datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))

    report = json.loads((root / 'metrics-public.json').read_text())
    rows = []
    for item in report['rows']:
        variant, case = item['variant'], item['case']
        private = root / (variant + '-evidence-private')
        nodes_file = private / (case + '-nodes.json')
        journal_file = private / (case + '-journal.json')
        row = {'variant': variant, 'case': case, 'taskId': item.get('taskId'),
               'firstExecutorStartedMs': None, 'clocks': []}
        rows.append(row)
        if not nodes_file.exists() or not journal_file.exists():
            continue
        nodes = json.loads(nodes_file.read_text())
        node = min(nodes, key=lambda n: n['createdAt'])
        task = json.loads(journal_file.read_text())
        stopped = {key for key, value in task.get('operations', {}).items()
                   if value.get('kind') == 'CODE' and value.get('stopProofRef')}
        files = []
        for offset in range(0, 1000, 100):
            query = urllib.parse.urlencode({'taskId': node['taskId'], 'kind': 'LOG',
                'personalOnly': 'false', 'limit': 100, 'offset': offset})
            page = json.loads(get('?' + query))
            if page.get('code') != 200:
                raise RuntimeError('Owner asset listing rejected')
            files.extend(page['data'])
            if len(page['data']) < 100:
                break
        else:
            raise RuntimeError('Timing export exceeded bounded asset pagination')
        seen = set()
        for file in files:
            source = file.get('sourceRef', '')
            if not source.startswith('execution:') or not source.endswith(':logs:receipt.json'):
                continue
            operation = source[len('execution:'):-len(':logs:receipt.json')]
            if operation not in stopped:
                continue
            assert file['taskId'] == node['taskId'] and file['runId'] == node['runId']
            assert operation not in seen, 'Immutable execution receipt binding changed'
            seen.add(operation)
            raw = get('/' + urllib.parse.quote(file['id'], safe='') + '/content')
            assert len(raw) == file['size'] and hashlib.sha256(raw).hexdigest() == file['sha256']
            (private / (case + '-' + operation + '-receipt-private.json')).write_bytes(raw)
            receipt = json.loads(raw)
            assert receipt['operationId'] == operation
            row['clocks'].append({'operationId': operation, 'codeHash': receipt.get('codeHash'),
                'startedAt': receipt.get('startedAt'), 'finishedAt': receipt.get('finishedAt'),
                'receiptSha256': file['sha256']})
        starts = [instant(c['startedAt']) for c in row['clocks'] if c['startedAt']]
        if starts:
            row['firstExecutorStartedMs'] = round((min(starts) - instant(node['createdAt'])).total_seconds() * 1000)
    aggregate = {}
    for variant in sorted({r['variant'] for r in rows}):
        group = [r['firstExecutorStartedMs'] for r in rows
                 if r['variant'] == variant and r['firstExecutorStartedMs'] is not None]
        aggregate[variant] = {'observed': len(group), 'median': statistics.median(group) if group else None}
    result = {'source': 'OWNER_AUTHENTICATED_IMMUTABLE_MCP_ARCHIVED_RECEIPT',
        'scope': 'MCP execution receipt startedAt, separate from Node Python tool dispatch; not an OS process-spawn probe. Missing clocks stay UNKNOWN. Receipts require the exact task/run, stop proof and registry hash/size.',
        'rows': rows, 'aggregate': aggregate}
    (root / 'executor-clocks-public.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(aggregate))


if __name__ == '__main__':
    main()
