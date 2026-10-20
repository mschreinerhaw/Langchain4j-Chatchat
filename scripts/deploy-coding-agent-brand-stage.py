"""Update only the standalone console assets on the existing stage worker."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request

ROOT = pathlib.Path('/opt/runtime-os-docker-stage')
PROJECT = 'runtime-os-app-stage'
IMAGE = 'runtime-os-coding-agent:product-console-20261020-r1'
WORKER = PROJECT + '-coding-agent-1'


def run(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(error.stderr) from error


def compose(*args):
    return run('docker', 'compose', '--project-name', PROJECT, '--env-file',
               str(ROOT / 'config/deployment.env'), '-f', str(ROOT / 'compose.yaml'), *args)


def main():
    archive = pathlib.Path(sys.argv[1]).resolve()
    if not archive.is_relative_to(ROOT / 'images'):
        raise RuntimeError('Unexpected archive location')
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != sys.argv[2]:
        raise RuntimeError('Archive hash mismatch')
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = (ROOT / 'config/coding-node-monitor.token').read_text().strip()

    def snapshot():
        request = urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',
                                         headers={'Authorization': 'Bearer ' + token})
        with client.open(request, timeout=10) as response:
            return json.load(response)

    def idle():
        active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At',
                     '-d', 'runtime_os_acceptance_api', '-c',
                     "SELECT count(*) FROM agent_task_latest WHERE canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED') OR canonical_state IS NULL").strip()
        if active != '0' or snapshot()['sessions']['active'] != 0:
            raise RuntimeError('Active tasks prevent worker replacement')

    idle()
    before = json.loads(run('docker', 'inspect', *run('docker', 'ps', '-q').split()))
    worker = next(item for item in before if item['Name'] == '/' + WORKER)
    if worker['Config']['Labels'].get('com.docker.compose.project') != PROJECT:
        raise RuntimeError('Unexpected compose project')
    evidence = ROOT / 'evidence' / ('coding-agent-brand-' + str(int(time.time())))
    evidence.mkdir(mode=0o700)
    context = evidence / 'context'
    context.mkdir()
    with tarfile.open(archive) as source:
        if {m.name for m in source.getmembers()} != {'index.html', 'app.js', 'style.css'}:
            raise RuntimeError('Unexpected assets')
        if any(not m.isfile() for m in source.getmembers()):
            raise RuntimeError('Assets must be regular files')
        source.extractall(context / 'public', filter='data')
    (context / 'Dockerfile').write_text(
        'FROM ' + worker['Config']['Image'] + '\nCOPY --chown=node:node public ./dist/public\n')
    run('docker', 'build', '--network=none', '-t', IMAGE, str(context))
    paths = [ROOT / 'compose.yaml', ROOT / 'config/deployment.env']
    for path in paths:
        shutil.copy2(path, evidence / path.name)
        (evidence / path.name).chmod(0o600)
    deployment = json.loads(paths[0].read_text())
    deployment['services']['coding-agent']['image'] = IMAGE
    idle()
    try:
        paths[0].write_text(json.dumps(deployment, indent=2))
        env = paths[1].read_text().splitlines()
        paths[1].write_text('\n'.join('NODE_IMAGE=' + IMAGE if line.startswith('NODE_IMAGE=') else line for line in env) + '\n')
        compose('up', '-d', '--no-deps', '--force-recreate', 'coding-agent')
        for _ in range(45):
            if subprocess.run(['docker', 'exec', WORKER, 'node', 'dist/healthcheck.js'], capture_output=True).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Worker readiness failed')
        run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        for _ in range(30):
            try:
                current = snapshot()
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('Monitor route not ready')
        hashes = {}
        for asset in ('index.html', 'app.js', 'style.css'):
            route = '' if asset == 'index.html' else asset
            with client.open('http://127.0.0.1:18100/monitor/' + route, timeout=10) as response:
                body = response.read()
            if body != (context / 'public' / asset).read_bytes():
                raise RuntimeError('Served asset mismatch: ' + asset)
            if b'cline' in body.lower():
                raise RuntimeError('Unexpected third-party branding: ' + asset)
            hashes[asset] = hashlib.sha256(body).hexdigest()
        for item in before:
            if item['Name'] != '/' + WORKER:
                if run('docker', 'inspect', '--format', '{{.Id}}', item['Name']).strip() != item['Id']:
                    raise RuntimeError('Unrelated container changed')
        report = {'status': 'DEPLOYED', 'image': IMAGE, 'url': 'http://192.168.195.221:18100/monitor/',
                  'backup': str(evidence), 'archiveSha256': digest, 'assets': hashes,
                  'nodeStatus': current['status'], 'activeTasks': current['sessions']['active'],
                  'unrelatedContainersUnchanged': True}
        (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report))
    except Exception:
        for path in paths:
            path.write_bytes((evidence / path.name).read_bytes())
        compose('up', '-d', '--no-deps', '--force-recreate', 'coding-agent')
        run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        raise


if __name__ == '__main__':
    main()
