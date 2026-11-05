"""Promote the isolated, hash-pinned worker candidate, keeping an exact rollback.

Usage: python3 deploy-cline-sdk-reuse.py --release-dir RELEASE
Requires deploy-coding-repair-context.py and verify-coding-callback.py alongside it.
"""
import argparse
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.parse


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    root = args.release_dir.resolve()
    docker = load('worker_deploy', Path(__file__).with_name('deploy-coding-repair-context.py')).docker
    callback = load('callback_gate', Path(__file__).with_name('verify-coding-callback.py')).verify
    candidate = json.loads((root / 'candidate.json').read_text())
    if candidate.get('isolatedGatesPassed') is not True:
        raise RuntimeError('Isolated gates have not passed')
    name = 'runtime-os-app-stage-coding-agent-1'
    inspect = lambda container: json.loads(run('docker', 'inspect', container))[0]
    old = inspect(name)
    assert old['Id'] == candidate['baselineContainerId'] and old['Image'] == candidate['baselineImageId']
    assert run('docker', 'image', 'inspect', '--format', '{{.Id}}', candidate['image']) == candidate['imageId']
    for module, digest in candidate['modulesBefore'].items():
        assert run('docker', 'exec', name, 'sha256sum', '/srv/coding-agent/dist/' + module).split()[0] == digest
    probe = "import fs from 'node:fs';const secret=fs.readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim();const r=await fetch('http://127.0.0.1:3100/readyz',{headers:{Authorization:'Bearer '+secret}});const j=await r.json();if(!r.ok||j.activeSessions!==0)throw Error('Active Coding sessions');console.log('IDLE');"
    assert run('docker', 'exec', name, 'node', '--input-type=module', '-e', probe) == 'IDLE'
    active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
    assert active == '0', 'Active parent tasks prevent worker replacement'
    assert not run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'), 'Active Python sandbox'
    for mount in old['Mounts']:
        if mount['Type'] == 'bind':
            assert Path(mount['Source']).exists(), 'Required deployment mount missing'
    fleet = {inspect(c)['Name']: c for c in run('docker', 'ps', '-q', '--no-trunc').split()}
    stage = Path('/opt/runtime-os-docker-stage')
    compose = stage / 'compose.yaml'
    env = stage / 'config/deployment.env'
    before_files = {compose: compose.read_bytes(), env: env.read_bytes()}
    backup = root / 'backup'
    backup.mkdir(mode=0o700, exist_ok=True)
    (backup / 'worker-private.json').write_text(json.dumps(old))
    for path, content in before_files.items():
        (backup / path.name).write_bytes(content)
    config, host = copy.deepcopy(old['Config']), copy.deepcopy(old['HostConfig'])
    config['Image'] = candidate['image']
    config['Env'] = [value for value in config['Env'] if not value.startswith('RUNTIME_CODING_BUILD=')] + ['RUNTIME_CODING_BUILD=' + candidate['sourceCommit']]
    endpoints = {network: {'Aliases': details.get('Aliases', []), 'IPAMConfig': details.get('IPAMConfig')}
        for network, details in old['NetworkSettings']['Networks'].items()}
    retired = name + '-before-sdk-reuse-' + old['Id'][:12]
    created, renamed, disconnected = False, False, []
    report = {'status': 'FAILED', 'image': candidate['image'], 'imageId': candidate['imageId'],
        'sourceCommit': candidate['sourceCommit'], 'backupContainer': retired, 'backupDirectory': str(backup)}
    try:
        run('docker', 'stop', '--time', '30', name)
        run('docker', 'rename', name, retired)
        renamed = True
        for network in endpoints:
            run('docker', 'network', 'disconnect', network, retired)
            disconnected.append(network)
        docker('POST', '/containers/create?name=' + urllib.parse.quote(name),
            {**config, 'HostConfig': host, 'NetworkingConfig': {'EndpointsConfig': endpoints}})
        created = True
        run('docker', 'start', name)
        for _ in range(45):
            current = inspect(name)
            if current['State'].get('Health', {}).get('Status') == 'healthy':
                break
            time.sleep(1)
        else:
            raise RuntimeError('Candidate readiness failed')
        for key in ['User', 'Entrypoint', 'Cmd', 'WorkingDir', 'Healthcheck']:
            assert current['Config'].get(key) == old['Config'].get(key), 'Changed worker configuration: ' + key
        for key in ['ReadonlyRootfs', 'CapAdd', 'CapDrop', 'SecurityOpt', 'Memory', 'NanoCpus', 'PidsLimit', 'Binds', 'Mounts', 'NetworkMode', 'RestartPolicy', 'Tmpfs']:
            assert current['HostConfig'].get(key) == old['HostConfig'].get(key), 'Changed worker boundary: ' + key
        assert current['Mounts'] == old['Mounts'], 'Changed effective mounts'
        for module, digest in candidate['modulesAfter'].items():
            assert run('docker', 'exec', name, 'sha256sum', '/srv/coding-agent/dist/' + module).split()[0] == digest
        run('bash', str(stage / 'restrict-coding-worker.sh'))
        report['callback'] = callback(name)
        run('docker', 'exec', 'runtime-os-app-stage-node-proxy-1', 'nginx', '-t')
        run('docker', 'exec', 'runtime-os-app-stage-node-proxy-1', 'nginx', '-s', 'reload')
        deployment = json.loads(before_files[compose])
        deployment['services']['coding-agent']['image'] = candidate['image']
        environment = deployment['services']['coding-agent'].setdefault('environment', {})
        if isinstance(environment, dict):
            environment['RUNTIME_CODING_BUILD'] = candidate['sourceCommit']
        else:
            deployment['services']['coding-agent']['environment'] = [value for value in environment
                if not value.startswith('RUNTIME_CODING_BUILD=')] + ['RUNTIME_CODING_BUILD=' + candidate['sourceCommit']]
        compose.write_text(json.dumps(deployment, indent=2))
        env.write_text(re.sub(r'^NODE_IMAGE=.*$', 'NODE_IMAGE=' + candidate['image'], before_files[env].decode(), flags=re.MULTILINE))
        for container_name, identifier in fleet.items():
            if container_name != '/' + name:
                assert inspect(container_name)['Id'] == identifier, 'Unrelated container changed'
        report.update(status='DEPLOYED', policyPreserved=True, unrelatedContainersUnchanged=True)
    except Exception:
        for path, content in before_files.items():
            path.write_bytes(content)
        if created:
            run('docker', 'rm', '-f', name)
        if renamed:
            run('docker', 'rename', retired, name)
        for network in disconnected:
            docker('POST', '/networks/' + urllib.parse.quote(network) + '/connect', {'Container': name, 'EndpointConfig': endpoints[network]})
        run('docker', 'start', name)
        run('bash', str(stage / 'restrict-coding-worker.sh'))
        run('docker', 'exec', 'runtime-os-app-stage-node-proxy-1', 'nginx', '-s', 'reload')
        report['status'] = 'ROLLED_BACK'
        raise
    finally:
        (root / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
