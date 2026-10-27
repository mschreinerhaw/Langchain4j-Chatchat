"""Patch verified Runtime classes in the running API; retain image/config for rollback."""
import argparse
import copy
import hashlib
import http.client
import io
import json
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
import urllib.parse
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def digest(body):
    return hashlib.sha256(body).hexdigest()


class DockerConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect('/var/run/docker.sock')


def docker(method, path, body):
    connection = DockerConnection('localhost', timeout=60)
    try:
        connection.request(method, '/v1.45' + path, body=json.dumps(body),
                           headers={'Content-Type': 'application/json'})
        response = connection.getresponse()
        data = response.read()
        if response.status >= 300:
            raise RuntimeError('Docker operation failed: ' + str(response.status) + '; '
                               + str(json.loads(data).get('message', '')))
        return json.loads(data) if data else None
    finally:
        connection.close()


def replace_entries(body, replacements, removals=(), allowed_new_prefixes=()):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(body)) as source, zipfile.ZipFile(output, 'w') as target:
        assert all(name.endswith(".class") or any(name.startswith(prefix) for prefix in allowed_new_prefixes)
                   for name in set(replacements)-set(source.namelist()))
        assert set(removals).issubset(source.namelist())
        for entry in source.infolist():
            if entry.filename in removals: continue
            target.writestr(entry, replacements.get(entry.filename, source.read(entry.filename)))
        for name in sorted(set(replacements)-set(source.namelist())):
            target.writestr(name,replacements[name])
    with zipfile.ZipFile(io.BytesIO(body)) as source, zipfile.ZipFile(output) as target:
        assert set(target.namelist()) == (set(source.namelist()) | set(replacements))-set(removals)
        for name in source.namelist():
            if name in removals: continue
            assert target.read(name) == replacements.get(name, source.read(name)), name
    return output.getvalue()


def rebind_node_proxy(release, api_container):
    """The node proxy shares API loopback; a container restart cannot change its namespace."""
    name = 'runtime-os-app-stage-node-proxy-1'
    api = json.loads(run('docker', 'inspect', api_container))[0]
    original = json.loads(run('docker', 'inspect', name))[0]
    desired = 'container:' + api['Id']
    if original['HostConfig']['NetworkMode'] == desired:
        return {'status': 'ALREADY_BOUND', 'networkMode': desired}
    assert original['HostConfig']['NetworkMode'].startswith('container:')
    for mount in original['Mounts']:
        assert Path(mount['Source']).exists(), 'Proxy mount source must exist before replacement'
    (release / 'node-proxy-before-private.json').write_text(json.dumps(original))
    config = copy.deepcopy(original['Config'])
    # Docker inherits hostname from the shared namespace and rejects an explicit override.
    config['Hostname'] = ''
    config.pop('ExposedPorts', None)
    host = copy.deepcopy(original['HostConfig'])
    host['NetworkMode'] = desired
    backup = name + '-before-template-supply-' + api['Id'][:12]
    created = renamed = False
    try:
        run('docker', 'stop', '--time', '15', name)
        run('docker', 'rename', name, backup)
        renamed = True
        docker('POST', '/containers/create?name=' + urllib.parse.quote(name),
               {**config, 'HostConfig': host})
        created = True
        run('docker', 'start', name)
        run('docker', 'exec', name, 'nginx', '-t')
        current = json.loads(run('docker', 'inspect', name))[0]
        for key in ['Image', 'Env', 'User', 'Entrypoint', 'Cmd', 'WorkingDir']:
            assert current['Config'].get(key) == original['Config'].get(key), key
        for key in ['ReadonlyRootfs', 'CapAdd', 'CapDrop', 'SecurityOpt', 'Memory', 'NanoCpus', 'Binds', 'RestartPolicy']:
            assert current['HostConfig'].get(key) == original['HostConfig'].get(key), key
        return {'status': 'REBOUND', 'networkMode': desired,
                'previousNetworkMode': original['HostConfig']['NetworkMode'], 'backupContainer': backup,
                'configurationPreserved': True}
    except Exception:
        if created:
            run('docker', 'rm', '-f', name)
        if renamed:
            run('docker', 'rename', backup, name)
            # During API rollback the original namespace owner is started by the caller.
            subprocess.run(['docker', 'start', name], capture_output=True, check=False)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    args = parser.parse_args()
    release = args.release_dir.resolve()
    root = Path('/opt/runtime-os-docker-stage')
    manifest = json.loads((release / 'manifest.json').read_text())
    config_file = root / 'compose.yaml'
    config = json.loads(config_file.read_text())
    container = 'runtime-os-app-stage-api-1'
    original = json.loads(run('docker', 'inspect', container))[0]
    assert original['State']['Running']
    assert run('docker', 'exec', container, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['baselineSha256']
    before = subprocess.check_output(['docker', 'exec', container, 'cat', '/srv/app/app.jar'])
    assert digest(before) == manifest['baselineSha256']
    (release / 'before.jar').write_bytes(before)
    (release / 'container-before-private.json').write_text(json.dumps(original))
    shutil.copy2(config_file, release / 'compose-before.json')
    shutil.copy2(root / 'config/deployment.env', release / 'deployment-before.env')
    entry = 'BOOT-INF/lib/chatchat-agents-1.0.0-SNAPSHOT.jar'
    with zipfile.ZipFile(io.BytesIO(before)) as jar:
        library = jar.read(entry)
    replacements = {}
    with zipfile.ZipFile(release / 'classes.zip') as overlay:
        assert set(overlay.namelist()) == set(manifest['classes'])
        for name in overlay.namelist():
            body = overlay.read(name)
            assert name.startswith('com/chatchat/agents/') and name.endswith('.class') and '..' not in name
            assert digest(body) == manifest['classes'][name]
            replacements[name] = body
    agent_removals = manifest.get('removeAgentClasses', [])
    assert all(name.startswith('com/chatchat/agents/') and name.endswith('.class') and '..' not in name
               for name in agent_removals)
    outer = {entry: replace_entries(library, replacements, agent_removals)}
    with zipfile.ZipFile(release / 'api-classes.zip') as overlay:
        assert set(overlay.namelist()) == set(manifest['apiClasses'])
        for name in overlay.namelist():
            body=overlay.read(name)
            assert name.startswith('BOOT-INF/classes/com/chatchat/api/') and name.endswith('.class') and '..' not in name
            assert digest(body)==manifest['apiClasses'][name]
            outer[name]=body
    web_assets = manifest.get('webAssets', {})
    if web_assets:
        with zipfile.ZipFile(release / 'web-assets.zip') as overlay:
            assert set(overlay.namelist()) == set(web_assets)
            for name in overlay.namelist():
                assert name.startswith('BOOT-INF/classes/static/') and '..' not in name
                body = overlay.read(name)
                assert digest(body) == web_assets[name]
                outer[name] = body
    removals=manifest.get('removeApiClasses',[])
    assert all(name.startswith('BOOT-INF/classes/com/chatchat/api/runtime/coding/analysis/CodingAnalysisServiceTest')
               and name.endswith('.class') for name in removals)
    candidate = replace_entries(before, outer, removals, ('BOOT-INF/classes/static/',))
    (release / 'app.jar').write_bytes(candidate)
    tag = 'runtime-os-api:template-supply-20261027-' + digest(candidate)[:12]
    baseline_tag = 'runtime-os-api:before-template-supply-' + original['Image'].split(':')[-1][:12]
    run('docker', 'tag', original['Image'], baseline_tag)
    assert run('docker', 'image', 'inspect', '--format', '{{.Id}}', baseline_tag) == original['Image']
    (release / '.dockerignore').write_text('*\n!Dockerfile\n!app.jar\n')
    (release / 'Dockerfile').write_text('FROM ' + baseline_tag + '\nCOPY app.jar /srv/app/app.jar\n')
    subprocess.run(['docker', 'build', '-t', tag, str(release)], check=True)

    def idle():
        assert run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d',
                   'runtime_os_acceptance_api', '-c', "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") == '0', 'Active tasks'
        assert not run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}')
        observations = json.loads((root / 'coding-monitor/data/observations.json').read_text())
        assert all(t['status'] in {'COMPLETED', 'FAILED', 'CANCELLED'} for t in observations['tasks'])

    def ready():
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        stable = None
        for _ in range(120):
            try:
                client.open('http://127.0.0.1:18180/api/v1/enterprise/auth/me', timeout=3).close()
                reachable = True
            except urllib.error.HTTPError as error:
                reachable = error.code in (401, 403)
            except OSError:
                reachable = False
            stable = (stable or time.monotonic()) if reachable else None
            if stable and time.monotonic() - stable >= 20:
                return
            time.sleep(2)
        raise RuntimeError('API readiness failed')

    idle()
    assert run('docker', 'exec', container, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['baselineSha256']
    report = {'baselineSha256': manifest['baselineSha256'], 'candidateSha256': digest(candidate),
              'image': tag, 'previousImage': original['Config']['Image'],
              'previousConfiguredImage': config['services']['api']['image'],
              'changedClasses': sorted(replacements), 'changedApiClasses': sorted(manifest['apiClasses']),
              'removedAgentClasses': agent_removals, 'removedAccidentalTestClasses': removals,
              'webAssets': web_assets, 'sourceCommit': manifest['sourceCommit'],
              'sourceDiffSha256': manifest['sourceDiffSha256'],
              'deploymentScriptSha256': digest(Path(__file__).read_bytes())}
    backup_name = container + '-before-template-supply-' + manifest['baselineSha256'][:12]
    cloned_config = copy.deepcopy(original['Config'])
    cloned_config['Image'] = tag
    endpoints = {network: {'Aliases': details.get('Aliases', []),
                          'IPAMConfig': details.get('IPAMConfig')}
                 for network, details in original['NetworkSettings']['Networks'].items()}
    assert all('api' in (endpoint['Aliases'] or []) for endpoint in endpoints.values())
    for proxy in ['runtime-os-app-stage-node-proxy-1', 'runtime-os-app-stage-gateway-1']:
        run('docker', 'exec', proxy, 'nginx', '-t')
    created = renamed = False
    disconnected = []
    proxy_rebind = None
    try:
        run('docker', 'stop', '--time', '45', container)
        run('docker', 'rename', container, backup_name)
        renamed = True
        for network in endpoints:
            run('docker', 'network', 'disconnect', network, backup_name)
            disconnected.append(network)
        docker('POST', '/containers/create?name=' + urllib.parse.quote(container),
               {**cloned_config, 'HostConfig': original['HostConfig'],
                'NetworkingConfig': {'EndpointsConfig': endpoints}})
        created = True
        run('docker', 'start', container)
        config['services']['api']['image'] = tag
        config_file.write_text(json.dumps(config, indent=2))
        ready()
        proxy_rebind = rebind_node_proxy(release, container)
        current = json.loads(run('docker', 'inspect', container))[0]
        for key in ['User', 'Env', 'Entrypoint', 'Cmd', 'WorkingDir']:
            assert current['Config'].get(key) == original['Config'].get(key), key
        for key in ['ReadonlyRootfs', 'CapAdd', 'CapDrop', 'SecurityOpt', 'Memory', 'NanoCpus', 'Binds', 'NetworkMode', 'RestartPolicy', 'Tmpfs']:
            assert current['HostConfig'].get(key) == original['HostConfig'].get(key), key
        assert run('docker', 'exec', container, 'sha256sum', '/srv/app/app.jar').split()[0] == digest(candidate)
        assert set(current['NetworkSettings']['Networks']) == set(original['NetworkSettings']['Networks'])
        for network in endpoints:
            assert current['NetworkSettings']['Networks'][network].get('Aliases') == endpoints[network]['Aliases']
        assert (root / 'config/deployment.env').read_bytes() == (release / 'deployment-before.env').read_bytes()
        for proxy in ['runtime-os-app-stage-node-proxy-1', 'runtime-os-app-stage-gateway-1']:
            run('docker', 'exec', proxy, 'nginx', '-t')
            run('docker', 'exec', proxy, 'nginx', '-s', 'reload')
        report['status'] = 'DEPLOYED'
        report['backupContainer'] = backup_name
        report['runtimeConfigurationPreserved'] = True
        report['nodeProxy'] = proxy_rebind
    except Exception:
        shutil.copy2(release / 'compose-before.json', config_file)
        if created:
            run('docker', 'rm', '-f', container)
        if renamed:
            run('docker', 'rename', backup_name, container)
        for network in disconnected:
            docker('POST', '/networks/' + urllib.parse.quote(network) + '/connect',
                   {'Container': container, 'EndpointConfig': endpoints[network]})
        run('docker', 'start', container)
        if proxy_rebind and proxy_rebind.get('backupContainer'):
            proxy = 'runtime-os-app-stage-node-proxy-1'
            run('docker', 'rm', '-f', proxy)
            run('docker', 'rename', proxy_rebind['backupContainer'], proxy)
            run('docker', 'start', proxy)
        else:
            proxy = 'runtime-os-app-stage-node-proxy-1'
            if not json.loads(run('docker', 'inspect', proxy))[0]['State']['Running']:
                run('docker', 'start', proxy)
        ready()
        for proxy in ['runtime-os-app-stage-node-proxy-1', 'runtime-os-app-stage-gateway-1']:
            run('docker', 'exec', proxy, 'nginx', '-s', 'reload')
        report['status'] = 'ROLLED_BACK'
        raise
    finally:
        (release / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
