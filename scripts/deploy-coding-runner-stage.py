"""Replace the audited compiled worker modules; retain console and dependencies."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = Path('/opt/runtime-os-docker-stage')
EVIDENCE = ROOT / 'evidence/template-controlled-20261020'
PROJECT = 'runtime-os-app-stage'
WORKER = PROJECT + '-coding-agent-1'


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def compose(*args):
    return run('docker', 'compose', '--project-name', PROJECT, '--env-file', str(ROOT / 'config/deployment.env'),
               '-f', str(ROOT / 'compose.yaml'), *args)


def main():
    expected = json.loads(sys.argv[1])
    names = ('runner.js', 'server.js', 'code-repair.js')
    if set(expected) != set(names):
        raise RuntimeError('Worker module manifest must contain the exact audited set')
    for name in names:
        actual = hashlib.sha256((EVIDENCE / name).read_bytes()).hexdigest()
        if actual != expected[name]:
            raise RuntimeError('Worker module checksum mismatch: ' + name)
    digest = hashlib.sha256(json.dumps(expected, sort_keys=True).encode()).hexdigest()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = (ROOT / 'config/coding-node-monitor.token').read_text().strip()

    def snapshot():
        request = urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',
                                         headers={'Authorization': 'Bearer ' + token})
        with client.open(request, timeout=5) as response:
            return json.load(response)

    active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
    if active != '0' or snapshot()['sessions']['active'] != 0:
        raise RuntimeError('Active tasks prevent runner replacement')
    before = json.loads(run('docker', 'inspect', *run('docker', 'ps', '-q').split()))
    worker = next(item for item in before if item['Name'] == '/' + WORKER)
    assets = {}
    for name in ('index.html', 'app.js', 'style.css'):
        with client.open('http://127.0.0.1:18100/monitor/' + ('' if name == 'index.html' else name), timeout=5) as response:
            assets[name] = hashlib.sha256(response.read()).hexdigest()
    backup = EVIDENCE / ('runner-backup-' + str(int(time.time())))
    backup.mkdir(mode=0o700)
    paths = [ROOT / 'compose.yaml', ROOT / 'config/deployment.env']
    for path in paths:
        shutil.copy2(path, backup / path.name)
        (backup / path.name).chmod(0o600)
    context = backup / 'context'
    context.mkdir()
    for name in names:
        shutil.copy2(EVIDENCE / name, context / name)
    (context / 'Dockerfile').write_text('FROM ' + worker['Config']['Image']
        + '\nCOPY --chown=node:node ' + ' '.join(names) + ' /srv/coding-agent/dist/\n')
    image = 'runtime-os-coding-agent:analysis-fast-' + digest[:12]
    run('docker', 'build', '--network=none', '-t', image, str(context))
    config = json.loads(paths[0].read_text())
    config['services']['coding-agent']['image'] = image
    try:
        paths[0].write_text(json.dumps(config, indent=2))
        env = [line for line in paths[1].read_text().splitlines() if not line.startswith('NODE_IMAGE=')]
        paths[1].write_text('\n'.join(env + ['NODE_IMAGE=' + image]) + '\n')
        compose('up', '-d', '--no-deps', '--force-recreate', 'coding-agent')
        for _ in range(45):
            if subprocess.run(['docker', 'exec', WORKER, 'node', 'dist/healthcheck.js'], capture_output=True).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Worker readiness failed')
        run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        for service in ('node-proxy', 'gateway'):
            run('docker', 'exec', PROJECT + '-' + service + '-1', 'nginx', '-s', 'reload')
        for _ in range(30):
            try:
                current = snapshot()
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('Monitor route failed')
        for name in names:
            actual = run('docker', 'exec', WORKER, 'sha256sum', '/srv/coding-agent/dist/' + name).split()[0]
            if actual != expected[name]:
                raise RuntimeError('Running worker source mismatch: ' + name)
        for name, asset_digest in assets.items():
            with client.open('http://127.0.0.1:18100/monitor/' + ('' if name == 'index.html' else name), timeout=5) as response:
                if hashlib.sha256(response.read()).hexdigest() != asset_digest:
                    raise RuntimeError('Console assets changed')
        for item in before:
            if item['Name'] != '/' + WORKER and run('docker', 'inspect', '--format', '{{.Id}}', item['Name']) != item['Id']:
                raise RuntimeError('Unrelated container changed')
        report = {'status': 'DEPLOYED', 'image': image, 'moduleSha256': expected, 'backup': str(backup),
                  'consoleUnchanged': True, 'unrelatedContainersUnchanged': True, 'activeTasks': current['sessions']['active']}
        (EVIDENCE / 'deployment-runner-analysis-fast.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report))
    except Exception:
        for path in paths:
            path.write_bytes((backup / path.name).read_bytes())
        compose('up', '-d', '--no-deps', '--force-recreate', 'coding-agent')
        run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        for service in ('node-proxy', 'gateway'):
            run('docker', 'exec', PROJECT + '-' + service + '-1', 'nginx', '-s', 'reload')
        raise


if __name__ == '__main__':
    main()
