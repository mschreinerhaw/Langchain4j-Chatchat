"""Opt-in real Docker checks with controlled data. No model or business database.

Uses a cached image only and the actual Runtime capture entrypoint. Does not
change an application's configured environment, firewall, mounts or tasks.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid


PROGRAM = '''
import csv,json
from collections import defaultdict
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
rows=list(csv.DictReader(Path('/data/input/schedule.csv').open()))
dates=sorted({row['date'] for row in rows})
totals=defaultdict(float)
for row in rows:totals[row['date']]+=float(row['seconds'])
ordered=[totals[day] for day in dates]
trend=('insufficient_data' if len(ordered)<2 else 'increasing' if ordered[-1]>ordered[0]
       else 'decreasing' if ordered[-1]<ordered[0] else 'unchanged')
stages={row['stage'] for row in rows}
missing=[{'date':day,'stage':stage} for day in dates for stage in sorted(stages)
         if not any(row['date']==day and row['stage']==stage for row in rows)]
result={'recordCount':len(rows),'firstDate':dates[0] if dates else None,
        'lastDate':dates[-1] if dates else None,'totals':dict(totals),
        'trend':trend,'missing':missing}
Path('/workspace/output/results/rows.jsonl').write_text(json.dumps(result)+'\\n')
fig,ax=plt.subplots();ax.plot(dates,ordered);ax.set_ylabel('Duration (seconds)')
fig.savefig('/workspace/output/results/trend.png');plt.close(fig)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, help='Existing published Python image; never downloaded')
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    def docker(*command, timeout=30):
        return subprocess.run(['docker', *command], capture_output=True, text=True, timeout=timeout, check=True)
    image = docker('image', 'inspect', '--format', '{{.Id}}', args.image).stdout.strip()
    proof = {'scope': 'CONTROLLED_REAL_DOCKER_ONLY_NOT_CLINE_END_TO_END', 'imageId': image,
             'runnerSha256': hashlib.sha256(args.runner.read_bytes()).hexdigest(), 'cases': {}}
    with tempfile.TemporaryDirectory(prefix='runtime-analysis-boundary-') as directory:
        root = Path(directory); root.chmod(0o755)
        scripts, inputs = root / 'scripts', root / 'input'
        scripts.mkdir(mode=0o755); inputs.mkdir(mode=0o755)
        (scripts / '_runtime_capture.py').write_bytes(args.runner.read_bytes())
        (scripts / '_runtime_capture.py').chmod(0o444)
        source = inputs / 'schedule.csv'
        def write_input(rows):
            source.chmod(0o644) if source.exists() else None
            with source.open('w', newline='') as output:
                writer = csv.DictWriter(output, fieldnames=['date', 'stage', 'seconds'])
                writer.writeheader(); writer.writerows(rows)
            source.chmod(0o444)
        def run(label, program, timeout=None):
            (scripts / 'main.py').write_text(program); (scripts / 'main.py').chmod(0o444)
            out = root / label; out.mkdir(mode=0o755)
            for name in ('results', 'logs', 'validation'):
                path = out / name; path.mkdir(); os.chown(path, 10001, 10001); path.chmod(0o700)
            before = hashlib.sha256(source.read_bytes()).hexdigest()
            name = 'runtime-analysis-test-' + uuid.uuid4().hex[:16]
            try:
                docker('create', '--pull=never', '--name', name, '--network', 'none', '--read-only',
                       '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--pids-limit', '64',
                       '--memory', '512m', '--cpus', '1', '--user', '10001:10001',
                       '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m',
                       '-e', 'MPLCONFIGDIR=/tmp/matplotlib', '-e', 'PYTHONDONTWRITEBYTECODE=1',
                       '-v', str(scripts) + ':/workspace/scripts:ro', '-v', str(inputs) + ':/data/input:ro',
                       '-v', str(out) + ':/workspace/output:rw', '--entrypoint', 'python', image,
                       '/workspace/scripts/_runtime_capture.py', '/workspace/scripts/main.py')
                docker('start', name)
                timed_out = False
                try: docker('wait', name, timeout=timeout or 30)
                except subprocess.TimeoutExpired:
                    timed_out = True; docker('kill', name)
                state = json.loads(docker('inspect', '--format', '{{json .State}}', name).stdout)
                assert not state['Running']
                assert hashlib.sha256(source.read_bytes()).hexdigest() == before
                diagnostic_file = out / 'logs/runtime-diagnostic.json'
                diagnostic = json.loads(diagnostic_file.read_text()) if diagnostic_file.exists() else {}
                entry = {'exitCode': state['ExitCode'], 'executorStopped': True, 'timedOut': timed_out,
                         'inputUnchanged': True, 'diagnostic': diagnostic}
                proof['cases'][label] = entry
                return out, entry
            finally:
                subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
        rows = [{'date': day, 'stage': stage, 'seconds': seconds}
                for day, seconds in [('2026-10-25', 10), ('2026-10-26', 8), ('2026-10-27', 12)]
                for stage in ('DWD', 'DWS', 'ADS')]
        write_input(rows)
        out, entry = run('schedule', PROGRAM)
        assert entry['exitCode'] == 0
        result = json.loads((out / 'results/rows.jsonl').read_text())
        assert result['recordCount'] == 9 and result['totals'] == {'2026-10-25':30, '2026-10-26':24, '2026-10-27':36}
        assert result['trend'] == 'increasing' and not result['missing']
        assert (out / 'results/trend.png').stat().st_size > 100
        entry['verifiedResult'] = result; entry['chartBytes'] = (out / 'results/trend.png').stat().st_size
        write_input(rows[:-1]); out, entry = run('partial', PROGRAM)
        result = json.loads((out / 'results/rows.jsonl').read_text())
        assert result['recordCount'] == 8 and result['missing'] == [{'date':'2026-10-27','stage':'ADS'}]
        entry['missingDataDisclosed'] = True
        write_input([]); out, entry = run('empty', PROGRAM)
        result = json.loads((out / 'results/rows.jsonl').read_text())
        assert result['firstDate'] is None and result['trend'] == 'insufficient_data'
        entry['emptyHandled'] = True
        _, entry = run('missing-dependency', "print('must-not-execute')\nimport missing_runtime_dependency_7ac4\n")
        assert entry['diagnostic']['preflightStatus'] == 'FAILED' and entry['diagnostic']['stdoutBytes'] == 0
        _, entry = run('write-input-denied', "from pathlib import Path\nPath('/data/input/schedule.csv').write_text('tampered')\n")
        assert entry['exitCode'] != 0 and entry['diagnostic']['exceptionType'] in ('PermissionError', 'OSError')
        _, entry = run('network-denied', "import socket\nsocket.create_connection(('192.168.195.221',3306),timeout=1)\n")
        assert entry['exitCode'] != 0
        _, entry = run('timeout', 'import time\ntime.sleep(30)\n', timeout=1)
        assert entry['timedOut'] and entry['executorStopped']
    encoded = json.dumps(proof, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(encoded + '\n', encoding='utf-8')
    print(encoded)


if __name__ == '__main__': main()
