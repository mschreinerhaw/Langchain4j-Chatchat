"""Read-only independent check of the real authorized day Parquet, not an Agent.

Run on the deployment host after collecting the native day task. No network is
available to the verifier container; only the three delivered files are mounted.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import urllib.request


def main():
    root = Path(sys.argv[1]).resolve()
    proof = json.loads((root / 'autonomous-day-proof.json').read_text())
    receipt = proof['daySnapshotReceipts'][0]
    assert receipt['rawItemsInjectedIntoModel'] is False
    files = receipt['calculationInput']['fileLayout']['files']
    shared = Path('/opt/runtime-os-docker-stage/mcp/data/python-user-data').resolve()
    mounts = []
    verified = []
    for item in files:
        identity, name = item['path'].rsplit('/', 2)[-2:]
        matches = list(shared.rglob(identity + '/' + name))
        assert len(matches) == 1, (identity, name)
        path = matches[0].resolve()
        assert path.is_relative_to(shared)
        assert hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256']
        mounts += ['--mount', 'type=bind,source=' + str(path) + ',target=/input/' + name + ',readonly']
        verified.append({'fileName': name, 'sha256': item['sha256'], 'bytes': path.stat().st_size})
    code = '''
import json, math
from pathlib import Path
import pyarrow.parquet as pq
m=json.loads(Path('/input/manifest.json').read_text())
physical=pq.read_table('/input/part-00000.parquet').to_pylist()
rows=[{c['name']:r[c['storageName']] for c in m['columns']} for r in physical]
assert len(rows)==m['rowCount']==2943
assert all(r['source_id']==38 and r['trade_date']=='2026-11-02' for r in rows)
sample=[r for r in rows if r['provider']=='SZSE' and r['instrument_type']=='EQUITY']
assert len({r['quote_code'] for r in sample})==len(sample)
target=[r for r in sample if r['quote_code']=='301218']
assert len(target)==1
t=target[0]
valid=[r for r in sample if isinstance(r['change_pct'],(float,int)) and math.isfinite(r['change_pct'])]
above=sum(r['change_pct']>t['change_pct'] for r in valid)
ties=sum(r['change_pct']==t['change_pct'] for r in valid)
print(json.dumps({'status':'PASSED','rowCount':len(rows),'market':'SZSE','instrumentType':'EQUITY',
 'sourceId':38,'tradeDate':'2026-11-02','validSampleCount':len(valid),'quoteCode':t['quote_code'],
 'close':t['close'],'changePct':t['change_pct'],'rankCompetition':above+1,'tieCount':ties,
 'tieLastPosition':above+ties,'amountUnit':t['amount_unit'],'volumeField':'volume10_k_units',
 'coverage':'Complete stored source/day snapshot; exchange collection completeness is unproven',
 'realtime':False}))
'''
    command = ['docker', 'run', '--rm', '-i', '--network', 'none', '--read-only',
               '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '--memory', '512m', '--cpus', '1', '--pids-limit', '64',
               '--tmpfs', '/tmp:rw,noexec,nosuid,size=32m', *mounts,
               'chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022', 'python', '-']
    result = json.loads(subprocess.check_output(command, input=code, text=True, timeout=120))
    result.update(readOnly=True, network='none', files=verified,
                  agentTaskId=proof['taskId'], agentStatus=proof['status'],
                  scope='Independent arithmetic and delivered-file verification; does not certify Agent report delivery')
    response = root / 'autonomous-day-response-private.json'
    if response.exists() and proof['status'] == 'COMPLETED':
        receipt = json.loads(response.read_text())['receipt']
        journal = json.loads((root / 'autonomous-day-journal-private.json').read_text())
        reference = journal['outcome']['resultDatasetRef']
        outputs = [part['output'] for message in journal['sdkCheckpoint']['messages']
                   if isinstance(message.get('content'), list) for part in message['content']
                   if part.get('type') == 'tool-result' and part.get('toolName') == 'python_execute'
                   and isinstance(part.get('output'), dict)]
        execution = next(value for value in outputs if value.get('resultDatasetRef') == reference)
        operation = execution['operationId']
        assert re.fullmatch('cline-[a-f0-9]{64}', operation)
        assert execution['executorStopped'] is True and execution['exitCode'] == 0
        query = "select row_to_json(t) from runtime_workspace_file t where task_id='" + proof['taskId'] + "' and state='AVAILABLE'"
        records = [json.loads(line) for line in subprocess.check_output(
            ['docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d',
             'runtime_os_acceptance_api', '-c', query], text=True).splitlines()]
        archives = [value for value in records if value['source_ref'] == 'execution:' + operation + ':results:rows.jsonl']
        assert len(archives) == 1
        archived = archives[0]
        assert re.fullmatch('[a-f0-9]{64}', archived['id'])
        token = json.loads((root / 'auth-private.json').read_text())['token']
        request = urllib.request.Request('http://127.0.0.1:18180/api/v1/data-science/user-assets/' + archived['id'] + '/content',
                                         headers={'Authorization': 'Bearer ' + token})
        with urllib.request.urlopen(request, timeout=30) as stream:
            raw = stream.read()
        assert hashlib.sha256(raw).hexdigest() == archived['sha256']
        assert len(raw) == archived['size_bytes']
        rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
        assert len(rows) == 1 and rows == receipt['results']
        (root / 'autonomous-day-accepted-report-private.json').write_bytes(raw)
        reviews = [part['output'] for message in journal['sdkCheckpoint']['messages']
                   if isinstance(message.get('content'), list) for part in message['content']
                   if part.get('type') == 'tool-result' and part.get('toolName') == 'artifact_read'
                   and isinstance(part.get('output'), dict) and part['output'].get('reference') == reference]
        assert reviews and reviews[-1]['reportReferenceValidation']['valid'] is True
        result['reportArchive'] = {'status': 'PASSED', 'operationId': operation,
                                  'reportFileId': archived['id'],
                                  'sha256': archived['sha256'], 'bytes': len(raw), 'rowCount': 1,
                                  'analysisStatus': rows[0].get('analysisStatus'), 'executorStopped': True,
                                  'referenceValidation': 'PASSED', 'apiResultsMatchArchive': True,
                                  'chartContract': 'Text report; charts not required',
                                  'businessCorrectnessCertified': False}
    (root / 'day-file-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
