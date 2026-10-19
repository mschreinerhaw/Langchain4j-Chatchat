"""Run ON the test server after uploading a locally built Docker archive.

python3 deploy-cline-monitor-stage.py ARCHIVE SHA256 TAG
python3 deploy-cline-monitor-stage.py --rollback BACKUP_ENV
No credentials or production configuration are stored in this script.
"""
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys
import time

ROOT = pathlib.Path('/opt/runtime-os-docker-stage')
ENV = ROOT / 'config/deployment.env'
PROJECT = 'runtime-os-app-stage'
EVIDENCE = ROOT / 'evidence/cline-monitor-20261019'


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE)


def compose(*args):
    return run('docker', 'compose', '--project-name', PROJECT, '--env-file', str(ENV), '-f', str(ROOT / 'compose.yaml'), *args)


def inspect():
    return {name: json.loads(run('docker', 'inspect', name))[0]
            for name in run('docker', 'ps', '--format', '{{.Names}}').splitlines()}


def fingerprint(value):
    return {'containerId': value['Id'], 'imageId': value['Image']}


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    EVIDENCE.chmod(0o700)
    active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "SELECT count(*) FROM agent_task_latest WHERE canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED') OR canonical_state IS NULL").strip()
    if active != '0':
        raise RuntimeError('Active test tasks prevent service replacement')
    before = inspect()
    for name in ('api', 'coding-agent'):
        labels = before[f'{PROJECT}-{name}-1']['Config']['Labels']
        if labels.get('com.docker.compose.project') != PROJECT:
            raise RuntimeError('Refusing to replace a container outside the test project')
    backup = EVIDENCE / ('deployment-before-' + str(int(time.time())) + '.env')
    shutil.copy2(ENV, backup)
    backup.chmod(0o600)
    if sys.argv[1] == '--rollback':
        source = pathlib.Path(sys.argv[2]).resolve()
        if not source.is_relative_to(EVIDENCE.resolve()) or source.suffix != '.env':
            raise RuntimeError('Rollback must use an exact backup from this test release')
        shutil.copy2(source, ENV)
    else:
        archive, expected, tag = pathlib.Path(sys.argv[1]).resolve(), sys.argv[2], sys.argv[3]
        if not archive.is_relative_to((ROOT / 'images').resolve()) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', tag):
            raise RuntimeError('Invalid test archive or image tag')
        with archive.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != expected:
            raise RuntimeError('Uploaded archive hash mismatch')
        run('docker', 'load', '-i', str(archive))
        replacements = {'API_IMAGE': 'runtime-os-api:' + tag, 'NODE_IMAGE': 'runtime-os-coding-agent:' + tag}
        lines = ENV.read_text().splitlines()
        for key in replacements:
            if sum(line.startswith(key + '=') for line in lines) != 1:
                raise RuntimeError('Missing or duplicated image setting: ' + key)
        updated = []
        for line in lines:
            key = line.split('=', 1)[0]
            updated.append(key + '=' + replacements[key] if key in replacements else line)
        ENV.write_text('\n'.join(updated) + '\n')
    ENV.chmod(0o600)
    try:
        compose('config', '-q')
        repaired = []
        for service in ('mcp', 'news'):
            name = f'{PROJECT}-{service}-1'
            links = run('docker', 'exec', name, 'stat', '-c', '%h', '/srv/app/logs').strip()
            if links == '0':
                # External cleanup unlinked the mounted test directory. Preserve the exact image/config.
                compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', service)
                repaired.append(service)
        compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'api', 'coding-agent')
        compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'node-proxy', 'gateway')
    except Exception:
        shutil.copy2(backup, ENV)
        compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'api', 'coding-agent', 'node-proxy', 'gateway')
        raise
    after = inspect()
    changed = {f'{PROJECT}-{name}-1' for name in ('api', 'coding-agent', 'node-proxy', 'gateway', *repaired)}
    for service in repaired:
        name = f'{PROJECT}-{service}-1'
        old_env = dict(value.split('=', 1) for value in before[name]['Config']['Env'])
        new_env = dict(value.split('=', 1) for value in after[name]['Config']['Env'])
        if before[name]['Image'] != after[name]['Image'] or old_env != new_env:
            raise RuntimeError('Mount repair changed an image or effective configuration: ' + service)
    for name, value in before.items():
        if name not in changed and fingerprint(value) != fingerprint(after[name]):
            raise RuntimeError('Unrelated container changed: ' + name)
    report = {'status': 'DEPLOYED_PENDING_ACCEPTANCE', 'testProject': PROJECT, 'backupEnv': str(backup),
        'before': {name: fingerprint(value) for name, value in before.items()},
        'after': {name: fingerprint(value) for name, value in after.items()},
        'repairedDeletedTestMounts': repaired, 'unrelatedContainersUnchanged': True, 'productionSwitched': False}
    (EVIDENCE / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({'status': report['status'], 'backupEnv': str(backup), 'unrelatedContainersUnchanged': True}))


if __name__ == '__main__':
    main()
