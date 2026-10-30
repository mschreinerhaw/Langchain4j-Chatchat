"""Attach the stage host's existing Oracle driver read-only; retain an exact rollback.

Run on the deployment host after all Coding sessions have stopped. No credentials,
database grants, application binaries, or generated SQL are changed by this script.
"""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def main():
    os.umask(0o077)
    release = Path(__file__).resolve().parent
    stage = Path('/opt/runtime-os-docker-stage')
    name = 'runtime-os-app-stage-mcp-1'
    source = Path('/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/lib/drivers/oracle').resolve(strict=True)
    jar = source / 'ojdbc8.jar'
    assert jar.is_file() and jar.resolve().parent == source
    with zipfile.ZipFile(jar) as archive:
        assert 'oracle/jdbc/OracleDriver.class' in archive.namelist()
    driver_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    probe = "const f=require('fs');fetch('http://127.0.0.1:3100/readyz',{headers:{authorization:'Bearer '+f.readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim()}}).then(r=>r.json()).then(r=>console.log(r.activeSessions))"
    assert run('docker', 'exec', 'runtime-os-app-stage-coding-agent-1', 'node', '-e', probe) == '0'
    spec = importlib.util.spec_from_file_location('studio_deploy', release / 'deploy.py')
    deploy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(deploy)
    before = json.loads(run('docker', 'inspect', name))[0]
    assert not any(m['Destination'] == '/srv/app/lib/drivers/oracle' for m in before['Mounts'])
    assert all(Path(m['Source']).exists() for m in before['Mounts'] if m['Type'] == 'bind')
    backup = release / 'oracle-driver-backup'
    backup.mkdir(mode=0o700)
    (backup / 'container.json').write_text(json.dumps(before))
    compose = stage / 'compose.yaml'
    original = compose.read_bytes()
    (backup / 'compose.yaml').write_bytes(original)
    config = json.loads(original)
    bind = str(source) + ':/srv/app/lib/drivers/oracle:ro'
    config['services']['mcp'].setdefault('volumes', []).append(bind)
    retired = name + '-before-oracle-driver-' + str(int(time.time()))
    created = False

    def ready():
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(120):
            try:
                status = client.open('http://127.0.0.1:18190/api/v1/admin/auth/me', timeout=3).status
            except urllib.error.HTTPError as error:
                status = error.code
            except (OSError, urllib.error.URLError):
                status = None
            if status in (200, 401, 403):
                return
            time.sleep(2)
        raise RuntimeError('MCP readiness timed out')

    run('docker', 'stop', '-t', '60', name)
    run('docker', 'rename', name, retired)
    try:
        cfg, host = copy.deepcopy(before['Config']), copy.deepcopy(before['HostConfig'])
        host['Mounts'] = []
        host['Binds'] = [(m.get('Name') if m['Type'] == 'volume' else m['Source']) + ':' + m['Destination']
                         + ('' if m['RW'] else ':ro') for m in before['Mounts']] + [bind]
        deploy.docker('/containers/create?name=' + name, {**cfg, 'HostConfig': host,
            'NetworkingConfig': {'EndpointsConfig': {network: {'Aliases': details.get('Aliases') or []}
                for network, details in before['NetworkSettings']['Networks'].items()}}})
        created = True
        run('docker', 'start', name)
        ready()
        assert run('docker', 'exec', name, 'sha256sum', '/srv/app/lib/drivers/oracle/ojdbc8.jar').split()[0] == driver_hash
        current = json.loads(run('docker', 'inspect', name))[0]
        assert current['Image'] == before['Image']
        mount = next(m for m in current['Mounts'] if m['Destination'] == '/srv/app/lib/drivers/oracle')
        assert mount['Source'] == str(source) and not mount['RW']
        compose.write_text(json.dumps(config, indent=2))
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        result = {'status': 'ATTACHED', 'container': name, 'driverSha256': driver_hash,
                  'readOnly': True, 'applicationImageUnchanged': True, 'retiredContainer': retired,
                  'source': str(source), 'target': '/srv/app/lib/drivers/oracle'}
        (release / 'oracle-driver.json').write_text(json.dumps(result, indent=2))
        print(json.dumps(result), flush=True)
    except Exception:
        compose.write_bytes(original)
        if created:
            run('docker', 'stop', '-t', '30', name)
            run('docker', 'rename', name, name + '-failed-oracle-driver-' + str(int(time.time())))
        run('docker', 'rename', retired, name)
        run('docker', 'start', name)
        ready()
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        raise


if __name__ == '__main__':
    main()
