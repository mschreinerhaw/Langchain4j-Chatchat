"""Deploy a reviewed API jar to the existing authorized Docker environment, with rollback.

Usage: python3 deploy-report-presentation.py app.jar SHA256 BASELINE_SHA256
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

ROOT = Path('/opt/runtime-os-docker-stage')
PROJECT = 'runtime-os-app-stage'
API = PROJECT + '-api-1'


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True)
    if result.returncode:
        (evidence / 'command-error-private.log').write_text(result.stdout + result.stderr)
        raise RuntimeError('Command failed: ' + args[0] + '; private log retained')
    return result.stdout.strip()


def compose(*args):
    return run('docker', 'compose', '--project-name', PROJECT, '--env-file',
               str(ROOT / 'config/deployment.env'), '-f', str(ROOT / 'compose.yaml'), *args)


def jar_hash():
    return run('docker', 'exec', API, 'sha256sum', '/srv/app/app.jar').split()[0]


def healthy(expected_index):
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    auth_files = sorted((ROOT / 'evidence').glob('*/browser-auth-private.json'),
                        key=lambda path: path.stat().st_mtime, reverse=True)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        authenticated = False
        for auth_file in auth_files:
            try:
                auth = json.loads(auth_file.read_text())
                request = urllib.request.Request('http://127.0.0.1:18180/api/v1/health',
                                                headers={'Authorization': 'Bearer ' + auth['token']})
                with client.open(request, timeout=4) as response:
                    if response.status != 200:
                        continue
                    authenticated = True
                    break
            except (OSError, ValueError, KeyError):
                pass
        try:
            with client.open('http://127.0.0.1:18180/', timeout=4) as response:
                if response.status == 200 and response.read() == expected_index:
                    return authenticated
        except OSError:
            pass
        time.sleep(2)
    raise RuntimeError('Deployed application index verification timed out')


def refresh_routes():
    compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
    run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
    run('docker', 'exec', PROJECT + '-gateway-1', 'nginx', '-t')
    run('docker', 'exec', PROJECT + '-gateway-1', 'nginx', '-s', 'reload')


def main():
    global evidence
    os.umask(0o077)
    jar, expected, baseline = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    evidence = ROOT / 'evidence' / ('report-presentation-' + str(int(time.time())))
    evidence.mkdir(mode=0o700)
    assert hashlib.file_digest(jar.open('rb'), 'sha256').hexdigest() == expected, 'Upload hash mismatch'
    assert jar_hash() == baseline, 'Running application changed; rebase deployment'
    assert run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d',
               'runtime_os_acceptance_api', '-c', "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") == '0', 'Active tasks'
    assert not run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'), 'Active Python operations'
    monitor_token = (ROOT / 'config/coding-node-monitor.token').read_text().strip()
    request = urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',
                                    headers={'Authorization': 'Bearer ' + monitor_token})
    with urllib.request.urlopen(request, timeout=10) as response:
        assert json.load(response)['sessions']['active'] == 0, 'Active coding sessions'
    before = {item['Name']: item['Id'] for item in json.loads(run('docker', 'inspect', *run('docker', 'ps', '-q').split()))}
    config_file, env_file = ROOT / 'compose.yaml', ROOT / 'config/deployment.env'
    shutil.copy2(config_file, evidence / 'compose-before.json')
    shutil.copy2(env_file, evidence / 'deployment-before.env')
    run('docker', 'cp', API + ':/srv/app/app.jar', str(evidence / 'app-before.jar'))
    with zipfile.ZipFile(evidence / 'app-before.jar') as archive:
        old_index = archive.read('BOOT-INF/classes/static/index.html')
    with zipfile.ZipFile(jar) as archive:
        assert archive.testzip() is None
        new_index = archive.read('BOOT-INF/classes/static/index.html')
    config = json.loads(config_file.read_text())
    old_image = config['services']['api']['image']
    image = 'runtime-os-api:report-presentation-' + expected[:12]
    context = evidence / 'image'
    context.mkdir()
    shutil.copy2(jar, context / 'app.jar')
    (context / 'Dockerfile').write_text('FROM ' + old_image + '\nCOPY app.jar /srv/app/app.jar\n')
    run('docker', 'build', '--network=none', '-t', image, str(context))
    assert jar_hash() == baseline, 'Application changed during preparation'
    try:
        config['services']['api']['image'] = image
        config_file.write_text(json.dumps(config, indent=2))
        env_file.write_text('\n'.join(line for line in env_file.read_text().splitlines()
                                     if not line.startswith('API_IMAGE=')) + '\nAPI_IMAGE=' + image + '\n')
        compose('up', '-d', '--no-deps', '--force-recreate', 'api')
        authenticated = healthy(new_index)
        refresh_routes()
        assert jar_hash() == expected
        with urllib.request.urlopen('http://127.0.0.1:18100/', timeout=10) as response:
            assert response.read() == new_index, 'Gateway serves stale frontend'
        after = {item['Name']: item['Id'] for item in json.loads(run('docker', 'inspect', *run('docker', 'ps', '-q').split()))}
        assert all(after.get(name) == identity for name, identity in before.items()
                   if name not in ('/' + API, '/' + PROJECT + '-node-proxy-1')), 'Unrelated container changed'
    except Exception:
        shutil.copy2(evidence / 'compose-before.json', config_file)
        shutil.copy2(evidence / 'deployment-before.env', env_file)
        compose('up', '-d', '--no-deps', '--force-recreate', 'api')
        healthy(old_index)
        refresh_routes()
        raise
    proof = {'status': 'DEPLOYED', 'image': image, 'jarSha256': expected,
             'baselineSha256': baseline, 'backup': str(evidence), 'authenticatedHealthVerified': authenticated,
             'gatewayIndexMatchesPackage': True, 'unrelatedContainersUnchanged': True}
    (evidence / 'deployment.json').write_text(json.dumps(proof, indent=2))
    print(json.dumps(proof))


if __name__ == '__main__':
    main()
