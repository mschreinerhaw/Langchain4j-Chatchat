"""Real Linux bootstrap isolation check. Requires root; never stops business services.

This checks the bootstrap only, not the V1 application deployment acceptance.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('--jar', type=Path, required=True)
p.add_argument('--java', required=True)
p.add_argument('--work', type=Path, required=True)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
unit = 'deployment-ops-bootstrap-test-' + str(int(time.time()))
results = {}

def run(*args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    return {'exit': result.returncode, 'output': result.stdout}

def inventory():
    ids = run('docker', 'ps', '-q')['output'].split()
    return run('docker', 'inspect', '--format', '{{.Id}} {{.State.StartedAt}} {{.State.Running}}', *ids) if ids else {'exit': 0, 'output': ''}

def health():
    for attempt in range(30):
        pid = run('systemctl', 'show', unit, '-p', 'MainPID', '--value')['output'].strip()
        if pid and pid != '0':
            result = run('nsenter', '-t', pid, '-n', 'curl', '-fsS', 'http://127.0.0.1:19240/actuator/health')
            if result['exit'] == 0:
                return result
        time.sleep(1)
    return {'exit': 1, 'output': 'Health check timed out'}

results['before'] = inventory()
try:
    results['start'] = run('systemd-run', '--unit=' + unit,
        '--property=PrivateNetwork=yes', '--property=InaccessiblePaths=/var/run/docker.sock',
        '--property=ProtectSystem=strict', '--property=ReadWritePaths=' + str(a.work),
        '--setenv=OPS_DATA_DIR=' + str(a.work / 'data'), '--setenv=OPS_PORT=19240',
        a.java, '-Djava.io.tmpdir=' + str(a.work), '-jar', str(a.jar))
    results['health'] = health()
    results['restart'] = run('systemctl', 'restart', unit)
    results['health_after_restart'] = health()
finally:
    results['stop'] = run('systemctl', 'stop', unit)
    results['after'] = inventory()
    results['business_unchanged'] = results['before'] == results['after']
    results['h2_exists'] = (a.work / 'data/deployment-ops.mv.db').is_file()
    results['journal'] = run('journalctl', '-u', unit, '--no-pager')
    results['passed'] = all(results.get(key, {}).get('exit') == 0 for key in ['start', 'health', 'restart', 'health_after_restart', 'stop']) and results['business_unchanged'] and results['h2_exists']
    a.report.write_text(json.dumps(results, indent=2))
print(json.dumps({k: v for k, v in results.items() if k not in ['before', 'after', 'journal']}, indent=2))
raise SystemExit(0 if results['passed'] else 1)
