"""Deploy a complete Studio application JAR, preserving runtime policy and an exact rollback."""
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
import urllib.error


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


class DockerConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect('/var/run/docker.sock')


def docker(path, body):
    client = DockerConnection('localhost', timeout=60)
    try:
        client.request('POST', '/v1.45' + path, json.dumps(body), {'Content-Type': 'application/json'})
        response = client.getresponse()
        data = response.read()
        if response.status >= 300:
            raise RuntimeError('Docker create failed: ' + data.decode()[:1000])
        return json.loads(data) if data else None
    finally:
        client.close()


def main():
    os.umask(0o077)
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / 'manifest.json').read_text())
    stage = Path('/opt/runtime-os-docker-stage')
    api, proxy = 'runtime-os-app-stage-api-1', 'runtime-os-app-stage-node-proxy-1'
    inspect = lambda name: json.loads(run('docker', 'inspect', name))[0]
    before = {name: inspect(name) for name in run('docker', 'ps', '--format', '{{.Names}}').splitlines()}
    assert run('docker', 'exec', api, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['baselineSha256']
    assert hashlib.sha256((root / 'app.jar').read_bytes()).hexdigest() == manifest['sha256']
    assert run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") == '0', 'Active Runtime tasks'
    probe = "const f=require('fs');fetch('http://127.0.0.1:3100/readyz',{headers:{authorization:'Bearer '+f.readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim()}}).then(r=>r.json()).then(r=>console.log(r.activeSessions))"
    assert run('docker', 'exec', 'runtime-os-app-stage-coding-agent-1', 'node', '-e', probe) == '0', 'Active Coding sessions'
    assert not run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'), 'Active Python sandbox'
    for name in [api, proxy]:
        for mount in before[name]['Mounts']:
            if mount['Type'] == 'bind' and not Path(mount['Source']).exists():
                raise RuntimeError('Required bind source is missing: ' + mount['Source'])
    base = 'runtime-os-api-runtime:17-sqlglot-20261024'
    assert 'app.jar' not in run('docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', base)
    (root / 'Dockerfile').write_text('FROM ' + base + '\nWORKDIR /srv/app\nCOPY app.jar /srv/app/app.jar\nENTRYPOINT ["java","-jar","/srv/app/app.jar"]\n')
    (root / '.dockerignore').write_text('*\n!app.jar\n!Dockerfile\n')
    run('docker', 'build', '--network', 'none', '-t', manifest['image'], str(root))
    backup = root / 'backup'
    backup.mkdir(mode=0o700)
    (backup / 'containers.json').write_text(json.dumps(before))
    configs = {name: (stage / name).read_bytes() for name in ['compose.yaml', 'config/deployment.env']}
    for name, data in configs.items():
        (backup / Path(name).name).write_bytes(data)
    sql = lambda query: run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c', query)
    channels = json.loads(sql("select coalesce(json_agg(json_build_object('id',session_id,'tenant',tenant_id,'channel',source_channel))::text,'[]') from chat_session"))
    (backup / 'channels.json').write_text(json.dumps(channels))
    fingerprint = sql("select md5(coalesce(jsonb_agg(to_jsonb(t)-'source_channel' order by session_id)::text,'[]')) from chat_session t")
    messages = sql('select count(*) from chat_message_index')
    with (backup / 'api.dump').open('wb') as stream:
        subprocess.run(['docker', 'exec', '-u', 'postgres', 'postgres', 'pg_dump', '-Fc', 'runtime_os_acceptance_api'], stdout=stream, check=True)
    stamp = str(int(time.time()))
    retired, created = {}, []

    def clone(name):
        old = before[name]
        config, host = copy.deepcopy(old['Config']), copy.deepcopy(old['HostConfig'])
        if name == api:
            config['Image'] = manifest['image']
        host['Mounts'] = []
        host['Binds'] = [(m.get('Name') if m['Type'] == 'volume' else m['Source']) + ':' + m['Destination'] + ('' if m['RW'] else ':ro') for m in old['Mounts']]
        if name == proxy:
            host['NetworkMode'] = 'container:' + inspect(api)['Id']
            for field in ['Hostname', 'Domainname', 'ExposedPorts']:
                config.pop(field, None)
            body = {**config, 'HostConfig': host}
        else:
            body = {**config, 'HostConfig': host, 'NetworkingConfig': {'EndpointsConfig': {net: {'Aliases': details.get('Aliases') or []} for net, details in old['NetworkSettings']['Networks'].items()}}}
        docker('/containers/create?name=' + name, body)
        created.append(name)
        run('docker', 'start', name)

    def ready():
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            try:
                response = client.open('http://127.0.0.1:18180/api/v1/enterprise/auth/me', timeout=3)
                status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
            except (OSError, urllib.error.URLError):
                status = None
            if status in [200, 401, 403]:
                return
            time.sleep(2)
        raise RuntimeError('Application readiness timed out')

    def networking():
        subprocess.run(['bash', str(root / 'restrict-coding-worker.sh')], check=True)
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')

    try:
        run('docker', 'stop', '-t', '60', proxy, api)
        for name in [api, proxy]:
            retired[name] = name + '-before-studio-' + stamp
            run('docker', 'rename', name, retired[name])
            clone(name)
        ready()
        networking()
        config = json.loads(configs['compose.yaml'])
        config['services']['api']['image'] = manifest['image']
        (stage / 'compose.yaml').write_text(json.dumps(config, indent=2))
        lines = [line for line in configs['config/deployment.env'].decode().splitlines() if not line.startswith('API_IMAGE=')]
        (stage / 'config/deployment.env').write_text('\n'.join(lines + ['API_IMAGE=' + manifest['image']]) + '\n')
        assert run('docker', 'exec', api, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['sha256']
        assert all(inspect(name)['Id'] == old['Id'] for name, old in before.items() if name not in [api, proxy])
        assert fingerprint == sql("select md5(coalesce(jsonb_agg(to_jsonb(t)-'source_channel' order by session_id)::text,'[]')) from chat_session t"), 'Conversation content changed during migration'
        assert messages == sql('select count(*) from chat_message_index'), 'Message index count changed during migration'
        report = {'status': 'DEPLOYED', 'image': manifest['image'], 'sha256': manifest['sha256'], 'backup': str(backup), 'retired': retired,
            'unrelatedContainersUnchanged': True, 'conversationContentUnchanged': True, 'messageIndexCountUnchanged': True,
            'channelCounts': sql('select source_channel,count(*) from chat_session group by source_channel')}
        (root / 'deployment.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report), flush=True)
    except Exception:
        for name, data in configs.items():
            (stage / name).write_bytes(data)
        for name in reversed(created):
            run('docker', 'stop', '-t', '30', name)
            run('docker', 'rename', name, name + '-failed-studio-' + stamp)
        for name in [api, proxy]:
            if name in retired:
                run('docker', 'rename', retired[name], name)
                run('docker', 'start', name)
        def literal(value):
            return 'NULL' if value is None else "'" + str(value).replace("'", "''") + "'"
        statements = ['BEGIN;'] + ['update chat_session set source_channel=' + literal(row['channel']) + ' where session_id=' + literal(row['id']) + ' and tenant_id=' + literal(row['tenant']) + ';' for row in channels] + ['COMMIT;']
        subprocess.run(['docker', 'exec', '-i', '-u', 'postgres', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-d', 'runtime_os_acceptance_api'], input='\n'.join(statements), text=True, check=True, stdout=subprocess.DEVNULL)
        ready()
        networking()
        raise


if __name__ == '__main__':
    main()
