"""Real SQL chat regression through authenticated task/file APIs; no direct DB grants.

Run on the deployment host alongside benchmark-runtime-analysis.py. Evidence may
contain private task context and stays in the explicitly chosen output directory.
The CSV is an uploaded, synthetic test fixture, not production business data.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth-file', required=True)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--model', required=True)
    parser.add_argument('--base-url', default='http://127.0.0.1:18180')
    parser.add_argument('--cases', nargs='+', default=['bare-sql', 'sql-explain', 'sql-invalid', 'sql-no-source', 'sql-file'])
    parser.add_argument('--source-task-id', help='Observe an already submitted source-required case without resubmitting it')
    args = parser.parse_args()
    os.umask(0o077)
    args.output_dir.mkdir(parents=True, exist_ok=False)
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = None

    def http(path, body=None, content='application/json'):
        headers = {'Content-Type': content}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(args.base_url + '/api/v1' + path, headers=headers, data=body)
        with client.open(request, timeout=60) as response:
            return json.load(response)['data']

    auth = http('/enterprise/auth/login', Path(args.auth_file).read_bytes())
    token = auth['token']
    tenant_query = '?tenantId=' + urllib.parse.quote(auth['user']['tenantId'])
    asset = None
    if 'sql-file' in args.cases:
        boundary = 'sql' + uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="sql-regression.csv"\r\n'
            'Content-Type: text/csv\r\n\r\namount\n1.25\n2.50\n\r\n--' + boundary + '--\r\n').encode()
        asset = http('/interactions/user-assets', body, 'multipart/form-data; boundary=' + boundary)
        (args.output_dir / 'uploaded-file.private.json').write_text(json.dumps(asset))
    cases = {
        'bare-sql': ('SELECT 1 AS sample_value;', {}),
        'sql-explain': ('Explain this SQL without executing a query or claiming actual data:\n```sql\n'
            'SELECT SUM(amount) AS total FROM transactions;\n```', {}),
        'sql-invalid': ('Validate the syntax only. Do not execute a query:\n```sql\nSELECT FROM ;\n```', {}),
        'sql-no-source': ('Execute this SQL and report the actual total. I have not attached a file or selected a datasource. '
            'Do not invent results:\n```sql\nSELECT SUM(amount) FROM transactions;\n```', {}),
        'sql-file': ('Execute the SQL using ONLY the attached CSV. Map it to an in-memory SQLite table named input_csv. '
            'Compute the actual total with Python, report its numeric value, cite the file, and never connect to an external datasource. '
            '\n```sql\nSELECT SUM(amount) AS total FROM input_csv;\n```', {'__userAssetIds': [asset['id']] if asset else []}),
    }
    controls = []
    for label in args.cases:
        query, tools = cases[label]
        fixture = args.output_dir / (label + '-request.json')
        fixture.write_text(json.dumps({'request': {'mode': 'llm_chat', 'modelName': args.model,
            'requestSource': 'sql-input-regression', 'query': query, 'toolInput': tools}}))
        output = args.output_dir / label
        if label == 'sql-no-source':
            # This intentionally stops before a Runtime/Coding run exists. A 404
            # from its child-run API is expected, not a failed data execution.
            request = json.loads(fixture.read_text())['request']
            request.update(sessionId=str(uuid.uuid4()), idempotencyKey=str(uuid.uuid4()))
            task_id = args.source_task_id or http('/agent/tasks', json.dumps(request).encode())['taskId']
            output.mkdir()
            report = {'taskId': task_id, 'events': []}
            (output / 'run-1.json').write_text(json.dumps(report))
            cursor = 0
            for _ in range(200):
                parent = http('/agent/tasks/' + task_id + tenant_query)
                assert parent.get('question') == query, 'Recovered task does not match this workload'
                page = http('/agent/tasks/' + task_id + '/events' + tenant_query + '&limit=200&afterSequence=' + str(cursor))
                report['events'].extend(page)
                cursor = max([cursor] + [int(event.get('sequence') or 0) for event in page])
                report['parent'] = parent
                (output / 'run-1.json').write_text(json.dumps(report))
                if parent.get('canonicalState') in {'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'REJECTED'}:
                    break
                time.sleep(3)
            else:
                raise RuntimeError('Source-required task did not settle: ' + task_id)
            results = [json.loads(event['payload']) for event in report['events'] if event['type'] == 'RESULT']
            outcome = results[-1].get('metadata', {}).get('workflowOutcome', {}) if results else {}
            assert outcome.get('type') == 'INPUT_REQUIRED' and not outcome.get('usableResult'), 'Missing explicit input-required result'
            assert outcome.get('reason') in {'SQL_DATA_SOURCE_REQUIRED', 'PROBLEM_ANALYSIS_CLARIFICATION_REQUIRED'}, 'Unexpected rejection reason'
            assert 'ANALYSIS_TASK_BUILT' not in json.dumps(report), 'Missing source must never submit code'
            controls.append({'case': label, 'taskId': task_id, 'status': parent.get('canonicalState'),
                'reason': outcome['reason'], 'codingTaskCount': 0, 'pythonOperationCount': 0})
            (args.output_dir / 'verification.json').write_text(json.dumps(controls, indent=2))
            print(json.dumps(controls[-1]), flush=True)
            continue
        with (args.output_dir / (label + '.log')).open('w') as log:
            process = subprocess.run([sys.executable, str(Path(__file__).with_name('benchmark-runtime-analysis.py')),
                '--auth-file', args.auth_file, '--request-file', str(fixture), '--output-dir', str(output),
                '--base-url', args.base_url, '--label', label, '--repetitions', '1', '--timeout', '600'],
                stdout=log, stderr=subprocess.STDOUT)
        if process.returncode:
            raise RuntimeError(label + ' observation failed; inspect evidence, do not automatically resubmit')
        parent = json.loads((output / 'run-1.json').read_text())
        coding = json.loads((output / 'coding-1.json').read_text())
        python = json.loads((output / 'python-runtime-1.json').read_text())
        serialized = json.dumps(parent)
        assert 'Bounded Coding parameter required' not in serialized
        if label == 'sql-no-source':
            assert 'SQL_DATA_SOURCE_REQUIRED' in serialized, 'Missing explicit source-required result'
            assert not coding.get('tasks') and not python, 'Missing source must never submit code'
        else:
            assert parent['parent'].get('canonicalState') == 'SUCCEEDED', label + ' did not succeed'
            assert len(coding.get('tasks', [])) == 1, 'Exactly one Coding assignment is required'
            assert python and all(row.get('exit_code') == 0 for row in python), 'Missing real successful Python receipt'
        controls.append({'case': label, 'taskId': parent['taskId'],
            'status': parent['parent'].get('canonicalState'), 'codingTaskCount': len(coding.get('tasks', [])),
            'pythonOperationCount': len(python)})
        (args.output_dir / 'verification.json').write_text(json.dumps(controls, indent=2))
        print(json.dumps(controls[-1]), flush=True)


if __name__ == '__main__':
    main()
