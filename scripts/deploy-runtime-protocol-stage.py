"""Deploy a backend-only protocol fix to the existing stage, preserving web assets."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path('/opt/runtime-os-docker-stage')
EVIDENCE = ROOT / 'evidence/ids-runtime-20261020'
PROJECT = 'runtime-os-app-stage'


def run(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()
    except subprocess.CalledProcessError as error:
        raise RuntimeError(error.stderr) from error


def compose(*args):
    return run('docker', 'compose', '--project-name', PROJECT, '--env-file', str(ROOT / 'config/deployment.env'),
               '-f', str(ROOT / 'compose.yaml'), *args)


def inspect():
    return {item['Name'].lstrip('/'): item for item in json.loads(run('docker', 'inspect', *run('docker', 'ps', '-q').split()))}


def web_hashes(path):
    with zipfile.ZipFile(path) as jar:
        return {name: hashlib.sha256(jar.read(name)).hexdigest() for name in jar.namelist()
                if name.startswith('BOOT-INF/classes/static/') and not name.endswith('/')}


def main():
    service = sys.argv[2] if len(sys.argv) > 2 else 'api'
    if service not in {'api', 'mcp'}:
        raise RuntimeError('Unexpected service')
    jar = EVIDENCE / ('app.jar' if service == 'api' else 'mcp.jar')
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest != sys.argv[1]:
        raise RuntimeError('Jar checksum mismatch')
    before = inspect()
    current = before[PROJECT + '-' + service + '-1']
    original = EVIDENCE / ('pre-deploy.jar' if service == 'api' else 'mcp-pre-deploy.jar')
    run('docker', 'cp', PROJECT + '-' + service + '-1:/srv/app/app.jar', str(original))
    if not web_hashes(jar) or web_hashes(jar) != web_hashes(original):
        raise RuntimeError('Deployed UI must be preserved byte for byte')
    active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
                 "SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
    if active != '0':
        raise RuntimeError('Active tasks prevent API replacement')
    tag = 'runtime-os-' + service + ':protocol-recovery-' + digest[:12]
    context = EVIDENCE / ('image-context' if service == 'api' else 'mcp-image-context')
    context.mkdir(exist_ok=True)
    shutil.copy2(jar, context / 'app.jar')
    (context / 'Dockerfile').write_text('FROM ' + current['Config']['Image'] + '\nCOPY app.jar /srv/app/app.jar\n')
    run('docker', 'build', '--network=none', '-t', tag, str(context))
    backup = EVIDENCE / ('backup-' + str(int(time.time())))
    backup.mkdir(mode=0o700)
    paths = [ROOT / 'compose.yaml', ROOT / 'config/deployment.env']
    for path in paths:
        shutil.copy2(path, backup / path.name)
        (backup / path.name).chmod(0o600)
    config = json.loads(paths[0].read_text())
    config['services'][service]['image'] = tag
    image_key = service.upper() + '_IMAGE='
    try:
        paths[0].write_text(json.dumps(config, indent=2))
        env = [line for line in paths[1].read_text().splitlines() if not line.startswith(image_key)]
        paths[1].write_text('\n'.join(env + [image_key + tag]) + '\n')
        compose('up', '-d', '--no-deps', '--force-recreate', service)
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 180
        while True:
            try:
                url = 'http://127.0.0.1:18180/api/v1/enterprise/auth/me' if service == 'api' else 'http://127.0.0.1:18190/api/v1/python/input-capabilities'
                with client.open(url, timeout=3) as response:
                    ready = response.status == 200
            except urllib.error.HTTPError as error:
                ready = error.code in (401, 403)
            except (OSError, urllib.error.URLError):
                ready = False
            if ready and service == 'mcp':
                try:
                    run('docker', 'exec', PROJECT + '-api-1', 'bash', '-c', 'exec 3<>/dev/tcp/mcp/9091')
                except RuntimeError:
                    ready = False
            if ready:
                break
            if time.monotonic() > deadline:
                raise RuntimeError('Service readiness timed out')
            time.sleep(2)
        # This proxy shares API's network namespace and must follow its replacement.
        if service == 'api':
            compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        run('docker', 'exec', PROJECT + '-gateway-1', 'nginx', '-t')
        run('docker', 'exec', PROJECT + '-gateway-1', 'nginx', '-s', 'reload')
        for _ in range(30):
            try:
                with client.open('http://127.0.0.1:18100/monitor/', timeout=5) as response:
                    if b'coding-agent' in response.read():
                        break
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('Public gateway route failed')
        after = inspect()
        changed = {PROJECT + '-api-1', PROJECT + '-node-proxy-1'} if service == 'api' else {PROJECT + '-mcp-1'}
        if any(after.get(name, {}).get('Id') != item['Id'] for name, item in before.items() if name not in changed):
            raise RuntimeError('Unrelated container changed')
        report = {'status': 'DEPLOYED', 'image': tag, 'jarSha256': digest, 'backup': str(backup),
                  'webUnchanged': True, 'unrelatedContainersUnchanged': True, 'gatewayReloadedInPlace': True}
        (EVIDENCE / ('deployment.json' if service == 'api' else 'mcp-deployment.json')).write_text(json.dumps(report, indent=2))
        print(json.dumps(report), flush=True)
    except Exception:
        for path in paths:
            path.write_bytes((backup / path.name).read_bytes())
        compose('up', '-d', '--no-deps', '--force-recreate', *(['api', 'node-proxy'] if service == 'api' else ['mcp']))
        run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        run('docker', 'exec', PROJECT + '-gateway-1', 'nginx', '-s', 'reload')
        raise


if __name__ == '__main__':
    main()
