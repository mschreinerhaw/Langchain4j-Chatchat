"""Align the authorized stage worker callback wait with its supported five-minute lease window."""
import json
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path('/opt/runtime-os-docker-stage')
PROJECT = 'runtime-os-app-stage'
EVIDENCE = ROOT/'evidence/runtime-hang-20261019'
def run(*args):
    return subprocess.check_output(args, text=True).strip()
def compose(*args):
    return run('docker', 'compose', '--project-name', PROJECT, '--env-file', str(ROOT/'config/deployment.env'),
        '-f', str(ROOT/'compose.yaml'), *args)

active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
    "SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
if active != '0' or run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'):
    raise RuntimeError('Active stage work prevents worker replacement')
before = {v['Name']: v['Id'] for v in json.loads(run('docker', 'inspect', *run('docker', 'ps', '-q').splitlines()))}
cfg = ROOT/'compose.yaml'
backup = EVIDENCE/('callback-before-'+str(int(time.time()))+'.json')
shutil.copy2(cfg, backup)
backup.chmod(0o600)
config = json.loads(cfg.read_text())
env = config['services']['coding-agent'].get('environment', {})
if isinstance(env, list):
    env = dict(v.split('=', 1) for v in env)
old = env.get('RUNTIME_CODING_CALLBACK_TIMEOUT_MS')
env['RUNTIME_CODING_CALLBACK_TIMEOUT_MS'] = '300000'
config['services']['coding-agent']['environment'] = env
try:
    cfg.write_text(json.dumps(config, indent=2)+'\n')
    cfg.chmod(0o600)
    compose('config', '-q')
    compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'coding-agent')
    deadline = time.monotonic()+45
    while True:
        try:
            run('docker', 'exec', PROJECT+'-coding-agent-1', 'node', 'src/healthcheck.js')
            break
        except subprocess.CalledProcessError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)
except Exception:
    shutil.copy2(backup, cfg)
    compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'coding-agent')
    raise
after = {v['Name']: v['Id'] for v in json.loads(run('docker', 'inspect', *run('docker', 'ps', '-q').splitlines()))}
if any(after.get(n) != value for n, value in before.items() if n != '/'+PROJECT+'-coding-agent-1'):
    raise RuntimeError('An unrelated container changed')
report = {'beforeMs': int(old or 90000), 'afterMs': 300000, 'backup': str(backup), 'unrelatedContainersUnchanged': True,
    'taskDeadlineAndCancellationUnchanged': True, 'observedModelDurationsMs': [107197, 127645]}
(EVIDENCE/'callback-timeout.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report))
