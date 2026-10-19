"""Deploy the locally packaged API to the existing authorized Docker stage, with rollback.
Usage: prepare | deploy SHA256 [rN]
"""
import hashlib
import json
from pathlib import Path
import shutil
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path('/opt/runtime-os-docker-stage')
PROJECT = 'runtime-os-app-stage'
EVIDENCE = ROOT / 'evidence/runtime-hang-20261019'
EVIDENCE.mkdir(mode=0o700, exist_ok=True)

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def compose(*args):
    return run('docker', 'compose', '--project-name', PROJECT, '--env-file', str(ROOT/'config/deployment.env'),
        '-f', str(ROOT/'compose.yaml'), *args)

def inspect():
    return {name: json.loads(run('docker', 'inspect', name))[0]
        for name in run('docker', 'ps', '--format', '{{.Names}}').splitlines()}

if sys.argv[1] == 'prepare':
    run('docker', 'cp', PROJECT+'-api-1:/srv/app/app.jar', str(EVIDENCE/'before.jar'))
    with zipfile.ZipFile(EVIDENCE/'before.jar') as jar, zipfile.ZipFile(EVIDENCE/'deployed-web.zip', 'w') as web:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/classes/static/') and not name.endswith('/'):
                web.writestr(name.removeprefix('BOOT-INF/classes/static/'), jar.read(name))
    print('Preserved deployed web assets')
    sys.exit(0)

jar = EVIDENCE/'app.jar'
expected = sys.argv[2]
if hashlib.sha256(jar.read_bytes()).hexdigest() != expected:
    raise RuntimeError('API jar checksum mismatch')
active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
    "SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
if active != '0':
    raise RuntimeError('Active parent tasks prevent API replacement')
before = inspect()
api = before[PROJECT+'-api-1']
if api['Config']['Labels']['com.docker.compose.project'] != PROJECT:
    raise RuntimeError('Unexpected project')
# Backend-only fix must preserve the deployed user interface byte for byte.
with zipfile.ZipFile(EVIDENCE/'before.jar') as old, zipfile.ZipFile(jar) as new:
    old_web = {n: hashlib.sha256(old.read(n)).hexdigest() for n in old.namelist() if n.startswith('BOOT-INF/classes/static/') and not n.endswith('/')}
    new_web = {n: hashlib.sha256(new.read(n)).hexdigest() for n in new.namelist() if n.startswith('BOOT-INF/classes/static/') and not n.endswith('/')}
    if not old_web or old_web != new_web:
        raise RuntimeError('Packaged web assets differ from deployed assets')
release = sys.argv[3] if len(sys.argv) > 3 else 'r1'
if not re.fullmatch(r'r[1-9][0-9]*', release):
    raise RuntimeError('Explicit valid release suffix required')
tag = 'runtime-os-api:runtime-hang-20261019-'+release
(EVIDENCE/'Dockerfile').write_text('FROM '+api['Config']['Image']+'\nCOPY app.jar /srv/app/app.jar\n')
(EVIDENCE/'.dockerignore').write_text('*\n!app.jar\n!Dockerfile\n')
run('docker', 'build', '-t', tag, str(EVIDENCE))
backup = EVIDENCE/('backup-'+str(int(time.time())))
backup.mkdir(mode=0o700)
cfg = ROOT/'compose.yaml'
env = ROOT/'config/deployment.env'
shutil.copy2(cfg, backup/'compose.json')
shutil.copy2(env, backup/'deployment.env')
config = json.loads(cfg.read_text())
config['services']['api']['image'] = tag
try:
    cfg.write_text(json.dumps(config, indent=2)+'\n')
    cfg.chmod(0o600)
    lines = [v for v in env.read_text().splitlines() if not v.startswith('API_IMAGE=')]
    env.write_text('\n'.join(lines+['API_IMAGE='+tag])+'\n')
    env.chmod(0o600)
    compose('config', '-q')
    compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'api')
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic()+180
    while True:
        try:
            with client.open('http://127.0.0.1:18180/api/v1/enterprise/auth/me', timeout=3) as response:
                ready = response.status == 200
        except urllib.error.HTTPError as error:
            ready = error.code in (401, 403)
        except (OSError, urllib.error.URLError):
            ready = False
        if ready:
            break
        if time.monotonic() > deadline:
            raise RuntimeError('API startup timed out')
        time.sleep(2)
    compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'node-proxy', 'gateway')
except Exception:
    shutil.copy2(backup/'compose.json', cfg)
    shutil.copy2(backup/'deployment.env', env)
    compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'api', 'node-proxy', 'gateway')
    raise
after = inspect()
changed = {PROJECT+'-'+s+'-1' for s in ('api', 'node-proxy', 'gateway')}
if any(after.get(n, {}).get('Id') != v['Id'] for n, v in before.items() if n not in changed):
    raise RuntimeError('Unrelated container changed')
report = {'image': tag, 'jarSha256': expected, 'backup': str(backup), 'webUnchanged': True,
    'unrelatedContainersUnchanged': True, 'apiImageId': after[PROJECT+'-api-1']['Image']}
(EVIDENCE/'deployment.json').write_text(json.dumps(report, indent=2))
(EVIDENCE/('deployment-'+release+'.json')).write_text(json.dumps(report, indent=2))
print(json.dumps(report))
