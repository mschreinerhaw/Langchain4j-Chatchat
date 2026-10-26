"""Out-of-band Docker checks for the opt-in arithmetic codegen benchmark only.

This does not grant workspace tasks execution authority or invoke production data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


VERIFIER = '''import json, pathlib, runpy, sqlite3
root = pathlib.Path('/workspace')
if (root / 'src/a.py').exists():
    total = runpy.run_path(str(root / 'src/a.py'))['total']
    average = runpy.run_path(str(root / 'src/b.py'))['average']
    assert total([]) == 0
    assert total([1, 2, 3]) == 6
    assert total([-2, 2]) == 0
    assert average([]) is None
    assert average([1, 2, 3]) == 2
    assert average([-2, 2]) == 0
else:
    db = sqlite3.connect(':memory:')
    db.execute('CREATE TABLE input_values(value REAL)')
    db.executemany('INSERT INTO input_values VALUES (?)', [(1,), (2,), (3,), (None,)])
    for name, expected in [('a', 6), ('b', 2)]:
        sql = (root / ('sql/' + name + '.sql')).read_text()
        assert db.execute(sql).fetchall() == [(expected,)]
    db.execute('DELETE FROM input_values')
    for name in ['a', 'b']:
        assert db.execute((root / ('sql/' + name + '.sql')).read_text()).fetchall() == [(None,)]
print(json.dumps({'status': 'PASSED', 'network': 'NONE', 'data': 'SYNTHETIC_ONLY'}))
'''


def verify(report_path, image):
    report = json.loads(report_path.read_text(encoding='utf-8'))
    assert report['scope'] == 'REAL_PROVIDER_ISOLATED_JAVA_NODE_H2_PARSER'
    assert report['status'] == 'COMPLETED'
    expected = {'src/a.py', 'src/b.py'} if report['language'] == 'python' else {'sql/a.sql', 'sql/b.sql'}
    files = report['output']['files']
    assert {f['path'] for f in files} == expected
    with tempfile.TemporaryDirectory(prefix='runtime-codegen-benchmark-') as directory:
        root = Path(directory)
        root.chmod(0o755)
        hashes = {}
        for file in files:
            path = root / file['path']
            path.parent.mkdir(exist_ok=True, mode=0o755)
            path.write_text(file['content'], encoding='utf-8')
            path.chmod(0o644)
            hashes[file['path']] = hashlib.sha256(file['content'].encode()).hexdigest()
        (root / 'verify.py').write_text(VERIFIER, encoding='utf-8')
        (root / 'verify.py').chmod(0o644)
        name = 'runtime-codegen-benchmark-' + uuid.uuid4().hex[:16]
        started = time.monotonic()
        try:
            execution = subprocess.run(['docker', 'run', '--rm', '--name', name,
                '--network', 'none', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                '--read-only', '--pids-limit', '64', '--memory', '256m', '--cpus', '1',
                '--user', '1000:1000', '--workdir', '/workspace',
                '--tmpfs', '/tmp:rw,noexec,nosuid,size=32m',
                '--mount', f'type=bind,src={root},dst=/workspace,readonly',
                '--entrypoint', 'python', image, '-I', '-S', '/workspace/verify.py'],
                capture_output=True, text=True, timeout=30)
            assert execution.returncode == 0, execution.stderr[-2000:]
            result = json.loads(execution.stdout)
            assert result['status'] == 'PASSED'
            return {'case': report_path.name, 'status': 'PASSED', 'sourceSha256': hashes,
                    'containerWallMs': round((time.monotonic() - started) * 1000),
                    'image': image, 'network': 'NONE', 'productionDataMounted': False}
        finally:
            subprocess.run(['docker', 'rm', '-f', name], capture_output=True, check=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports-root', type=Path, required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reports = sorted(args.reports_root.glob('*.json'))
    assert len(reports) == 4, 'Expected exactly the four isolated benchmark reports'
    result = {'scope': 'OUT_OF_BAND_GENERATED_CANDIDATE_BEHAVIOR_CHECK',
              'cases': [verify(path, args.image) for path in reports]}
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
