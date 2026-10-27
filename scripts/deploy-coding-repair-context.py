"""Replace only the hash-pinned Coding worker; retain its runtime policy and rollback container."""
import argparse
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.parse


BASELINES = {
    'runner.js': 'dffac47fc2caf1841214097d085ccbb1ad0830e80fc44f4aa662c89d3a17351b',
    'code-repair.js': 'e1d7f8fffc18d1e5d58ea600e403b3bb18a18fedcd02e1e85de86f69b71f256f',
}


def run(*args):
    return subprocess.check_output(args, text=True).strip()


class DockerConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect('/var/run/docker.sock')


def docker(method, path, body=None):
    connection = DockerConnection('localhost', timeout=60)
    try:
        connection.request(method, '/v1.45' + path, body=json.dumps(body) if body else None,
                           headers={'Content-Type': 'application/json'})
        response = connection.getresponse()
        data = response.read()
        if response.status >= 300:
            raise RuntimeError('Docker operation failed: ' + str(response.status))
        return json.loads(data) if data else None
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    root = args.release_dir.resolve()
    name = 'runtime-os-app-stage-coding-agent-1'
    old = json.loads(run('docker', 'inspect', name))[0]
    assert old['State']['Running']
    baseline_file = root / 'node-baseline.json'
    baselines = json.loads(baseline_file.read_text()) if baseline_file.is_file() else BASELINES
    assert baselines and all(module in {'runner.js', 'code-repair.js'} for module in baselines)
    for module, expected in baselines.items():
        assert run('docker', 'exec', name, 'sha256sum', '/srv/coding-agent/dist/' + module).split()[0] == expected
    (root / 'container-before-private.json').write_text(json.dumps(old))
    config = copy.deepcopy(old['Config'])
    host = copy.deepcopy(old['HostConfig'])
    # Recover deleted deployment assets from the still-mounted running files.
    # Keep the original paths usable so the retained container can also roll back.
    for mount in old['Mounts']:
        source = Path(mount['Source'])
        if not source.exists():
            assert mount['Type'] == 'bind' and mount['Destination'].startswith('/run/secrets/')
            value = subprocess.check_output(['docker', 'exec', name, 'cat', mount['Destination']])
            backup = root / Path(mount['Destination']).name
            backup.write_bytes(value)
            source.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            source.write_bytes(value)
            source.chmod(0o640)
            uid, _, gid = config['User'].partition(':')
            os.chown(source, 0, int(gid or uid))
    image = 'runtime-os-coding-agent:repair-' + hashlib.sha256((root/'patched/runner.js').read_bytes()).hexdigest()[:16]
    baseline_tag = 'runtime-os-coding-agent:baseline-' + old['Image'].split(':')[1][:16]
    run('docker', 'tag', old['Image'], baseline_tag)
    assert run('docker', 'image', 'inspect', '--format', '{{.Id}}', baseline_tag) == old['Image']
    (root / 'Dockerfile').write_text('FROM ' + baseline_tag + '\nCOPY patched/ /srv/coding-agent/dist/\n')
    subprocess.run(['docker', 'build', '-t', image, str(root)], check=True)
    # Exercise the actual production Node and installed SDK before replacing anything.
    verify = root / 'verification'
    verify.mkdir(exist_ok=True)
    verify.chmod(0o755)
    for directory in ['src', 'test', 'fixtures']:
        shutil.copytree(root / directory, verify / directory, dirs_exist_ok=True)
    (verify / 'package.json').write_text('{"type":"module"}')
    (verify / 'package.json').chmod(0o644)
    if not (verify / 'node_modules').is_symlink():
        (verify / 'node_modules').symlink_to('/srv/coding-agent/node_modules')
    with (root / 'production-worker-tests.log').open('w') as log:
        subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--entrypoint', 'node',
                        '-v', str(verify) + ':/verify:ro', '-w', '/verify', image,
                        '--test', 'test/code-repair.test.js', 'test/code-lifecycle.test.js',
                        'test/reviewed-result.test.js', 'test/runner.test.js',
                        'test/recovery.test.js', 'test/runtime-model.test.js',
                        'test/server.test.js', 'test/sdk-packages.test.js'],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    def idle():
        assert run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d',
                   'runtime_os_acceptance_api', '-c', "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") == '0'
        observations = json.loads(Path('/opt/runtime-os-docker-stage/coding-monitor/data/observations.json').read_text())
        assert all(t['status'] in {'COMPLETED', 'FAILED', 'CANCELLED'} for t in observations['tasks'])
        assert not run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}')
    idle()
    assert json.loads(run('docker', 'inspect', name))[0]['Id'] == old['Id']
    backup_name = name + '-before-repair-' + old['Image'].split(':')[-1][:16]
    config['Image'] = image
    endpoints = {network: {'Aliases': details.get('Aliases', []),
                           'IPAMConfig': details.get('IPAMConfig')}
                 for network, details in old['NetworkSettings']['Networks'].items()}
    report = {'previousImage': old['Image'], 'image': image, 'backupContainer': backup_name,
              'changedModules': list(baselines), 'runtimePolicyPreserved': True}
    compose_path = Path('/opt/runtime-os-docker-stage/compose.yaml')
    compose_before = compose_path.read_bytes()
    (root / 'compose-before.json').write_bytes(compose_before)
    created = False
    renamed = False
    disconnected = []
    try:
        run('docker', 'stop', '--time', '30', name)
        run('docker', 'rename', name, backup_name)
        renamed = True
        for network in endpoints:
            run('docker', 'network', 'disconnect', network, backup_name)
            disconnected.append(network)
        docker('POST', '/containers/create?name=' + urllib.parse.quote(name),
               {**config, 'HostConfig': host, 'NetworkingConfig': {'EndpointsConfig': endpoints}})
        created = True
        run('docker', 'start', name)
        for _ in range(60):
            current = json.loads(run('docker', 'inspect', name))[0]
            if current['State'].get('Health', {}).get('Status') == 'healthy':
                break
            time.sleep(1)
        else:
            raise RuntimeError('Worker readiness failed')
        for key in ['User', 'Env', 'Entrypoint', 'Cmd', 'Healthcheck', 'WorkingDir']:
            assert current['Config'].get(key) == old['Config'].get(key), 'Worker configuration changed: ' + key
        for key in ['ReadonlyRootfs', 'CapAdd', 'CapDrop', 'SecurityOpt', 'Memory', 'NanoCpus',
                    'PidsLimit', 'Binds', 'Mounts', 'NetworkMode', 'RestartPolicy', 'Tmpfs']:
            assert current['HostConfig'].get(key) == old['HostConfig'].get(key), 'Worker policy changed: ' + key
        assert set(current['NetworkSettings']['Networks']) == set(old['NetworkSettings']['Networks'])
        for network, details in old['NetworkSettings']['Networks'].items():
            assert current['NetworkSettings']['Networks'][network]['IPAddress'] == details['IPAddress']
        for module in baselines:
            expected = hashlib.sha256((root / 'patched' / module).read_bytes()).hexdigest()
            assert run('docker', 'exec', name, 'sha256sum', '/srv/coding-agent/dist/' + module).split()[0] == expected
        run('docker', 'exec', 'runtime-os-app-stage-node-proxy-1', 'nginx', '-t')
        run('docker', 'exec', 'runtime-os-app-stage-node-proxy-1', 'nginx', '-s', 'reload')
        compose = json.loads(compose_before)
        compose['services']['coding-agent']['image'] = image
        compose_path.write_text(json.dumps(compose, indent=2))
        report['status'] = 'DEPLOYED'
    except Exception:
        compose_path.write_bytes(compose_before)
        if created:
            run('docker', 'rm', '-f', name)
        if renamed:
            run('docker', 'rename', backup_name, name)
        for network in disconnected:
            docker('POST', '/networks/' + urllib.parse.quote(network) + '/connect',
                   {'Container': name, 'EndpointConfig': endpoints[network]})
        run('docker', 'start', name)
        run('docker', 'exec', 'runtime-os-app-stage-node-proxy-1', 'nginx', '-s', 'reload')
        report['status'] = 'ROLLED_BACK'
        raise
    finally:
        (root / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
