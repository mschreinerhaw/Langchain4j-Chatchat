"""Deploy the compiled standalone monitor on the authorized Docker stage host.

Usage: python3 deploy-coding-node-monitor-stage.py ARCHIVE SHA256
No credentials are embedded or printed. Existing API/MCP containers are preserved.
"""
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import urllib.error

ROOT = pathlib.Path('/opt/runtime-os-docker-stage')
PROJECT = 'runtime-os-app-stage'
EVIDENCE = ROOT / 'evidence/coding-node-monitor-20261020'
IMAGE = 'runtime-os-coding-agent:node-monitor-20261020-r1'
COMPOSE = ROOT / 'compose.yaml'
ENV = ROOT / 'config/deployment.env'
NODE_PROXY = ROOT / 'config/node-proxy.conf'
GATEWAY = ROOT / 'config/public-gateway.conf'


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE)


def compose(*args):
    return run('docker', 'compose', '--project-name', PROJECT, '--env-file', str(ENV), '-f', str(COMPOSE), *args)


def containers():
    return {item['Name'].lstrip('/'): item for item in json.loads(run('docker', 'inspect', *run('docker', 'ps', '-q').split()))}


def main():
    archive = pathlib.Path(sys.argv[1]).resolve()
    if not archive.is_relative_to((ROOT / 'images').resolve()):
        raise RuntimeError('Archive must be inside the test images directory')
    if hashlib.sha256(archive.read_bytes()).hexdigest() != sys.argv[2]:
        raise RuntimeError('Archive hash mismatch')
    active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "SELECT count(*) FROM agent_task_latest WHERE canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED') OR canonical_state IS NULL").strip()
    if active != '0':
        raise RuntimeError('Active tasks prevent worker replacement')
    before = containers()
    worker = before[PROJECT + '-coding-agent-1']
    if worker['Config']['Labels'].get('com.docker.compose.project') != PROJECT:
        raise RuntimeError('Worker is not in the authorized stage project')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    EVIDENCE.chmod(0o700)
    backup = EVIDENCE / ('before-' + str(int(time.time())))
    backup.mkdir(mode=0o700)
    for file in (COMPOSE, ENV, NODE_PROXY, GATEWAY):
        shutil.copy2(file, backup / file.name)
        (backup / file.name).chmod(0o600)
    context = EVIDENCE / 'image-context'
    context.mkdir(exist_ok=True)
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            name = pathlib.PurePosixPath(member.name)
            if name.is_absolute() or '..' in name.parts or member.issym() or member.islnk() or not (member.name == 'package.json' or name.parts[0] == 'dist'):
                raise RuntimeError('Unexpected build archive member')
        source.extractall(context, filter='data')
    (context / 'Dockerfile').write_text(
        f'FROM {worker["Config"]["Image"]}\nUSER root\n'
        'COPY --chown=node:node dist ./dist\nCOPY --chown=node:node package.json ./package.json\n'
        'RUN mkdir -p /srv/monitor-data && chown node:node /srv/monitor-data\n'
        'ENV RUNTIME_CODING_BUILD=node-monitor-20261020-r1\nUSER node\n'
        'HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 CMD ["node","dist/healthcheck.js"]\n'
        'CMD ["node","dist/server.js"]\n')
    (context / '.dockerignore').write_text('*\n!Dockerfile\n!dist\n!dist/**\n!package.json\n')
    run('docker', 'build', '--network=none', '-t', IMAGE, str(context))

    token_file = ROOT / 'config/coding-node-monitor.token'
    if not token_file.exists():
        token_file.write_text(secrets.token_urlsafe(48) + '\n')
    os.chown(token_file, 0, 32101)
    token_file.chmod(0o640)
    data_dir = ROOT / 'coding-monitor/data'
    data_dir.mkdir(parents=True, exist_ok=True)
    os.chown(data_dir, 1000, 32101)
    data_dir.chmod(0o750)
    deployment = json.loads(COMPOSE.read_text())
    node = deployment['services']['coding-agent']
    node['image'] = IMAGE
    node['command'] = ['node', 'dist/server.js']
    node['environment'].update(RUNTIME_CODING_MONITOR_TOKEN_FILE='/run/secrets/coding_node_monitor',
        RUNTIME_CODING_MONITOR_DIRECTORY='/srv/monitor-data')
    for source, target, readonly in ((str(token_file), '/run/secrets/coding_node_monitor', True), (str(data_dir), '/srv/monitor-data', False)):
        node['volumes'] = [item for item in node.get('volumes', []) if item.get('target') != target]
        node['volumes'].append({'type': 'bind', 'source': source, 'target': target, 'read_only': readonly})
    node_proxy = NODE_PROXY.read_text()
    if 'listen 8792;' in node_proxy or 'location ^~ /v1/monitor/' in GATEWAY.read_text():
        raise RuntimeError('Monitor routes already installed; review the existing release before redeploying')
    extra_server = '''
  server {
    listen 8792;
    proxy_set_header Host $http_host;
    proxy_set_header Connection "";
    proxy_read_timeout 30s;
    location = /monitor { proxy_pass http://coding-agent:3100; }
    location ^~ /monitor/ { proxy_pass http://coding-agent:3100; }
    location ^~ /v1/monitor/ { proxy_pass http://coding-agent:3100; }
    location / { return 404; }
  }
'''
    node_proxy = node_proxy.rsplit('}', 1)[0] + extra_server + '}\n'
    gateway = GATEWAY.read_text().replace('    location = /admin {', '''    location = /monitor { proxy_pass http://api:8792; }
    location ^~ /monitor/ { proxy_pass http://api:8792; }
    location ^~ /v1/monitor/ { proxy_pass http://api:8792; }
    location = /admin {''', 1)
    try:
        COMPOSE.write_text(json.dumps(deployment, indent=2))
        env = ENV.read_text()
        ENV.write_text(re.sub(r'^NODE_IMAGE=.*$', 'NODE_IMAGE=' + IMAGE, env, flags=re.MULTILINE))
        compose('up', '-d', '--no-deps', '--force-recreate', 'coding-agent')
        for _ in range(45):
            if subprocess.run(['docker', 'exec', PROJECT + '-coding-agent-1', 'node', 'dist/healthcheck.js'], capture_output=True).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('New worker readiness failed')
        run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        NODE_PROXY.write_text(node_proxy)
        GATEWAY.write_text(gateway)
        for service in ('node-proxy', 'gateway'):
            run('docker', 'exec', PROJECT + '-' + service + '-1', 'nginx', '-t')
        for service in ('node-proxy', 'gateway'):
            run('docker', 'exec', PROJECT + '-' + service + '-1', 'nginx', '-s', 'reload')
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(30):
            try:
                request = urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',
                    headers={'Authorization': 'Bearer ' + token_file.read_text().strip()})
                with client.open(request, timeout=5) as response:
                    snapshot = json.load(response)
                if snapshot.get('schemaVersion') == 'coding_agent.monitor.v1':
                    break
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('Public monitor route is unavailable')
        with client.open('http://127.0.0.1:18100/monitor/', timeout=5) as response:
            if b'Cline' not in response.read():
                raise RuntimeError('Monitor page not served')
        after = containers()
        changed = [name for name in before if name != PROJECT + '-coding-agent-1'
            and (name not in after or before[name]['Id'] != after[name]['Id'])]
        if changed:
            raise RuntimeError('Unrelated containers changed')
        report = {'status': 'DEPLOYED', 'image': IMAGE, 'imageId': after[PROJECT + '-coding-agent-1']['Image'],
            'url': 'http://192.168.195.221:18100/monitor/', 'backup': str(backup), 'archiveSha256': sys.argv[2],
            'monitorTokenFile': str(token_file), 'dataDirectory': str(data_dir), 'node': snapshot,
            'unrelatedContainersUnchanged': True, 'proxiesReloadedInPlace': True}
        (EVIDENCE / 'deployment.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report), flush=True)
    except Exception:
        for file in (COMPOSE, ENV, NODE_PROXY, GATEWAY):
            file.write_bytes((backup / file.name).read_bytes())
        compose('up', '-d', '--no-deps', '--force-recreate', 'coding-agent')
        run('bash', str(ROOT / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        for service in ('node-proxy', 'gateway'):
            run('docker', 'exec', PROJECT + '-' + service + '-1', 'nginx', '-s', 'reload')
        raise


if __name__ == '__main__':
    main()
