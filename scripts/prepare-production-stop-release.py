"""Prepare the stop-reconciliation release using the current production state and retired node policy."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    os.umask(0o077)
    releases = Path('/opt/runtime-os/releases')
    old = releases / 'source-20261118-d736e9fdf'
    release = releases / 'source-20261118-stop-reconcile'
    retired = Path('/opt/runtime-os/native-temporal/rc-20261112')
    private = release / 'private'
    tag = 'source-20261118-stop-reconcile'
    image = 'runtime-os-native-worker:' + tag
    worker_id = subprocess.check_output(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'], text=True).strip()
    compose = json.loads((old / 'compose.production.json').read_text())
    services = compose['services']

    def config(name, data):
        path = private / name
        path.write_text(json.dumps(data, indent=2))
        return path

    def move_config(service, name, value):
        previous = old / 'private' / name
        path = config(name, value)
        spec = services[service]
        spec['image'] = image
        spec['environment'] = {k: str(path) if v == str(previous) else v
                               for k, v in spec['environment'].items()}
        spec['volumes'] = [m.replace(str(previous), str(path)) for m in spec['volumes']]
        return path

    current_gateway = json.loads((old / 'private/cn2-gateway.json').read_text())
    current_gateway['workerImage'] = worker_id
    current_cfg = move_config('native-node', 'cn2-gateway.json', current_gateway)
    for name, service in [('cn2-executor.json', 'native-executor'), ('cn2-studio-executor.json', 'native-studio-executor')]:
        executor = json.loads((old / 'private' / name).read_text())
        executor['workerImage'] = worker_id
        move_config(service, name, executor)

    retired_gateway = json.loads((retired / 'private/gateway2.json').read_text())
    retired_executor = json.loads((retired / 'private/executor2.json').read_text())
    assert retired_executor['instanceId'] == 'temporal-native-8fb1df672796f04d'
    assert retired_gateway['executor']['socketPath'] == retired_executor['socketPath']
    retired_executor['recoveryOnly'] = True
    retired_gateway_cfg = config('retired-gateway2.json', retired_gateway)
    retired_executor_cfg = config('retired-executor2.json', retired_executor)

    script_root = '/opt/runtime-os/coding-node/service/src/'
    historical_spec = json.loads((retired / 'compose.private.json').read_text())['services']['executor2']
    historical_spec.update(image=image, container_name='runtime-native-retired-executor2', pull_policy='never',
        user='0:0', init=True, restart='unless-stopped',
        entrypoint=['node', script_root + 'worker-executor.mjs'],
        environment={'CLINE_EXECUTOR_CONFIG': str(retired_executor_cfg)},
        volumes=historical_spec['volumes'] + [f'{retired_executor_cfg}:{retired_executor_cfg}:ro'])
    historical_spec.pop('depends_on', None)
    services['native-retired-executor2'] = historical_spec

    def recovery_service(cfg, root, state):
        return {'image': image, 'pull_policy': 'never', 'init': True, 'user': '0:0',
                'entrypoint': ['node', script_root + 'recovery-server.mjs'], 'restart': 'unless-stopped',
                'environment': {'CLINE_GOVERNANCE_CONFIG': str(cfg), 'CLINE_RECOVERY_HOST': '0.0.0.0'},
                'volumes': [f'{cfg}:{cfg}:ro', f'{root}:{root}:ro', f'{state}:{state}'],
                'networks': {'control': {}}, 'cap_drop': ['ALL'],
                'security_opt': ['no-new-privileges:true'], 'read_only': True}

    current_root = Path(current_gateway['stateRoot']).parent
    services['native-recovery'] = recovery_service(current_cfg, current_root, current_gateway['stateRoot'])
    services['native-retired-recovery2'] = recovery_service(
        retired_gateway_cfg, retired / 'node2', retired_gateway['stateRoot'])
    connection = json.loads(Path('/opt/runtime-os-docker-stage/config/secrets/native-coding-connection.json').read_text())
    connection.setdefault('recoveryNodes', {}).update(
        {connection.get('defaultNodeId', 'default'): 'http://native-recovery:18362',
         'cline-worker-2': 'http://native-retired-recovery2:18362'})
    api_cfg = config('native-coding-connection.json', connection)
    os.chown(api_cfg, 10001, 10001)
    api_cfg.chmod(0o400)
    api = services['api']
    build = json.loads((release / 'build-summary-chatchat-api.json').read_text())['builds'][0]
    assert build['exitCode'] == 0
    api['image'] = build['image']
    for variable in ('NO_PROXY', 'no_proxy'):
        api['environment'][variable] += ',native-recovery,native-retired-recovery2'
    matches = [mount for mount in api['volumes'] if isinstance(mount, dict)
               and mount.get('target') == '/run/secrets/native-coding-connection.json']
    assert len(matches) == 1
    matches[0]['source'] = str(api_cfg)
    old_gateway = old / 'private/public-gateway.conf'
    new_gateway = private / 'public-gateway.conf'
    manifest = (release / 'source-manifest.json').read_bytes()
    version = json.dumps({'sourceCommit': json.loads(manifest)['commit'], 'release': tag,
                          'sourceManifestSha256': hashlib.sha256(manifest).hexdigest(), 'serverBuilt': True})
    gateway_text, count = re.subn(
        r"location = /version.json \{[^\n]+",
        lambda _: "location = /version.json { default_type application/json; return 200 '" + version + "'; }",
        old_gateway.read_text())
    assert count == 1
    new_gateway.write_text(gateway_text)
    services['gateway']['volumes'] = [m.replace(str(old_gateway), str(new_gateway))
                                     for m in services['gateway']['volumes']]
    # Keep Compose relative paths, environment and existing data volume names stable.
    (private / 'release.env').write_bytes((old / 'private/release.env').read_bytes())
    (release / 'compose.production.json').write_text(json.dumps(compose, indent=2))
    subprocess.run(['docker', 'compose', '--env-file', str(private / 'release.env'),
                    '-f', str(release / 'compose.production.json'), 'config', '--quiet'], check=True)
    print('Candidate Compose validated with unchanged project, data mounts and routes.')


if __name__ == '__main__':
    main()
