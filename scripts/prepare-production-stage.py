"""Prepare an isolated release using the existing runtime-os-docker-stage configuration."""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--worker-image', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    release, stage = Path(args.release).resolve(), Path('/opt/runtime-os-docker-stage')
    old = json.loads((stage / 'compose.before-native-studio.private.json').read_text())
    infra = json.loads((release / 'infrastructure-template.json').read_text())
    revision = json.loads((release / 'source-manifest.json').read_text())['commit']
    tag = 'source-20261118-' + revision[:9]
    worker_id = subprocess.check_output(['docker', 'image', 'inspect', args.worker_image,
                                        '--format', '{{.Id}}'], text=True).strip()
    assert re.fullmatch(r'sha256:[0-9a-f]{64}', worker_id)
    private, data = release / 'private', release / 'data'
    data.mkdir(exist_ok=True)
    values = {'JDBC_DRIVERS_DIR': '/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/lib/drivers',
              'NATIVE_SEARCH_TLS_DIR': str(stage / 'config/native-search-tls')}
    def substitute(value):
        if isinstance(value, dict):
            return {k: substitute(v) for k, v in value.items()}
        if isinstance(value, list):
            return [substitute(v) for v in value]
        if isinstance(value, str):
            for key, replacement in values.items():
                value = re.sub(r'\$\{' + key + r'(?::[^}]*)?\}', lambda _: replacement, value)
        return value
    services = {}
    writable = set()
    readable = set()
    for name in ['api', 'mcp', 'news']:
        service = substitute(copy.deepcopy(old['services'][name]))
        service['image'] = {'api': 'chatchat-api', 'mcp': 'chatchat-mcp-server',
                            'news': 'chatchat-runtime-news'}[name] + ':' + tag
        service['pull_policy'] = 'never'
        service['init'] = True
        service['stop_grace_period'] = '60s'
        service['pids_limit'] = 512
        service['logging'] = {'driver': 'json-file', 'options': {'max-size': '20m', 'max-file': '3'}}
        service['extra_hosts'] = ['host.docker.internal:host-gateway']
        if 'depends_on' in service:
            service['depends_on'] = {k: v for k, v in service['depends_on'].items() if k != 'silo'}
        environment = service.setdefault('environment', {})
        if name == 'mcp':
            service['group_add'] = [str(Path('/var/run/docker.sock').stat().st_gid)]
            environment.update(CHATCHAT_TOOLS_DATABASEQUERY_DRIVERLIBPATH='/srv/app/lib/drivers',
                SPRING_CONFIG_ADDITIONAL_LOCATION='optional:file:/srv/app/config/,optional:file:/srv/app/config/native-search-tls/application.yml')
        if name == 'news':
            environment.update(CHATCHAT_RUNTIME_NEWS_TEMPORAL_SERVER_MODE='external',
                CHATCHAT_RUNTIME_NEWS_TEMPORAL_TARGET='temporal:7233',
                CHATCHAT_RUNTIME_NEWS_MCP_SERVER_BASE_URL='http://mcp:8090')
            service['depends_on'] = {'temporal-namespace': {'condition': 'service_completed_successfully'}}
        for mount in service.get('volumes', []):
            if isinstance(mount, dict) and mount.get('type') == 'bind':
                src = Path(mount['source'])
                if mount.get('read_only'):
                    if (src.is_file() or src.is_dir()) and not str(src).startswith('/sys/'):
                        readable.add(str(src))
                elif str(src) != '/var/run/docker.sock':
                    src.mkdir(parents=True, exist_ok=True)
                    writable.add(str(src))
        services[name] = service
    # Reuse the active cn2 governance stack; retired legacy services stay retired.
    cn2 = Path('/opt/runtime-os/cn2')
    current = json.loads((cn2 / 'compose.json').read_text())
    short = Path('/opt/runtime-os/p18-' + revision[:7])
    short.mkdir(mode=0o700, exist_ok=True)
    backup = private / 'cn2-before'
    backup.mkdir(exist_ok=True)
    for name in ['gateway.json', 'executor.json', 'studio-executor.json']:
        src = cn2 / 'private' / name
        if not (backup / name).exists():
            (backup / name).write_bytes(src.read_bytes())
    if not (backup / 'compose.json').exists():
        (backup / 'compose.json').write_bytes((cn2 / 'compose.json').read_bytes())
    configs = {n: json.loads((backup / n).read_text()) for n in ['gateway.json', 'executor.json', 'studio-executor.json']}
    gateway = configs['gateway.json']
    connection = json.loads((stage / 'config/secrets/native-coding-connection.json').read_text())
    assert connection['issuerKey'] == gateway['signingKey'], 'Java/Node issuer identity mismatch'
    for directory in ['workers', 'state', 'executor-control', 'studio-workers', 'studio-executor-control']:
        (short / directory).mkdir(mode=0o700, exist_ok=True)
    gateway.update(workerImage=worker_id, workerRoot=str(short / 'workers'), stateRoot=str(short / 'state'))
    gateway['executor']['socketPath'] = str(short / 'executor.sock')
    for filename, prefix in [('executor.json', ''), ('studio-executor.json', 'studio-')]:
        configs[filename].update(workerImage=worker_id,
            workersRoot=str(short / (prefix + 'workers')), hostWorkersRoot=str(short / (prefix + 'workers')),
            controlRoot=str(short / (prefix + 'executor-control')), socketPath=str(short / (prefix + 'executor.sock')))
    for filename, cfg in configs.items():
        (private / ('cn2-' + filename)).write_text(json.dumps(cfg, indent=2))
    for original, name, script, config_var, filename in [
        ('node', 'native-node', 'governance-server.mjs', 'CLINE_GOVERNANCE_CONFIG', 'gateway.json'),
        ('executor', 'native-executor', 'worker-executor.mjs', 'CLINE_EXECUTOR_CONFIG', 'executor.json'),
        ('studio-executor', 'native-studio-executor', 'worker-executor.mjs', 'CLINE_EXECUTOR_CONFIG', 'studio-executor.json')]:
        service = copy.deepcopy(current['services'][original])
        service.update(image=args.worker_image, pull_policy='never', init=True,
            entrypoint=['node', '/opt/runtime-os/coding-node/service/src/' + script],
            environment={config_var: str(private / ('cn2-' + filename)), 'NODE_USE_ENV_PROXY': '1'},
            volumes=[str(short) + ':' + str(short), str(private / ('cn2-' + filename)) + ':' + str(private / ('cn2-' + filename)) + ':ro',
                     str(cn2 / 'template') + ':' + str(cn2 / 'template') + ':ro'],
            restart='unless-stopped', stop_grace_period='60s')
        service.pop('profiles', None)
        service.pop('depends_on', None)
        service['extra_hosts'] = ['silo:127.0.0.1', 'host.docker.internal:172.17.0.1']
        if original != 'node':
            service['environment']['CLINE_EXECUTOR_PUBLISHED_DAEMON_BINDINGS'] = '1'
            service['volumes'].append('/srv/runtime-os-coding-node-20261110/strict-daemon-3:/srv/runtime-os-coding-node-20261110/strict-daemon-3:ro')
        else:
            service['depends_on'] = {'native-executor': {'condition': 'service_started'}}
            service['healthcheck'] = {'test': ['CMD', 'node', '-e',
                "fetch('http://172.17.0.1:18360/health/live').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"],
                'interval': '10s', 'timeout': '5s', 'retries': 12}
        services[name] = service
    gateway_text = (stage / 'config/public-gateway.conf').read_text()
    gateway_text = gateway_text.replace('root /srv/task-lifecycle-ui; try_files /index.html =404;',
                                        'proxy_pass http://$api_endpoint;')
    gateway_text = gateway_text.replace('location ^~ /assets/ { root /srv/task-lifecycle-ui; try_files $uri @previous_assets; }',
                                        'location ^~ /assets/ { proxy_pass http://$api_endpoint; }')
    version_json = json.dumps({'sourceCommit': revision, 'release': tag, 'serverBuilt': True})
    gateway_text = re.sub(r"location = /version.json \{[^\n]+", lambda _: "location = /version.json { default_type application/json; return 200 '" + version_json + "'; }", gateway_text)
    (private / 'public-gateway.conf').write_text(gateway_text)
    services['gateway'] = {'image': 'nginx:1.28-alpine', 'restart': 'unless-stopped',
        'ports': ['0.0.0.0:18100:8080', '0.0.0.0:18101:8090'],
        'volumes': [str(private / 'public-gateway.conf') + ':/etc/nginx/nginx.conf:ro',
                    str(release / 'source/chatchat-api/src/main/resources/coding-monitor') + ':/srv/coding-monitor:ro'],
        'depends_on': {'api': {'condition': 'service_started'}, 'mcp': {'condition': 'service_started'}},
        'networks': {'control': {}}}
    token_file = private / 'frontend-hub-token'
    hub_token = token_file.read_text().strip() if token_file.exists() else secrets.token_urlsafe(36)
    token_file.write_text(hub_token)
    gateway['frontendHub'] = {'url': 'http://127.0.0.1:18220', 'token': hub_token}
    (private / 'cn2-gateway.json').write_text(json.dumps(gateway, indent=2))
    for name, service in infra['services'].items():
        service = copy.deepcopy(service)
        if name in ['frontend-resource-hub', 'frontend-preview-worker']:
            service['image'] = 'frontend-resource-hub:' + tag
            service['environment']['HUB_TOKEN'] = hub_token
            service['networks'] = {'control': {}, 'coding': {}}
            if name == 'frontend-resource-hub':
                service['tmpfs'] = ['/tmp:size=128m']
            if name == 'frontend-preview-worker':
                service['security_opt'] = ['no-new-privileges:true', 'seccomp=' + str(release / 'source/frontend-resource-hub/seccomp_profile.json')]
        else:
            service['networks'] = {'control': {}}
        if name.startswith('temporal'):
            service['volumes'] = [v.replace('./temporal-entrypoint.sh:', str(release / 'source/deploy/docker/production/temporal-entrypoint.sh') + ':') for v in service.get('volumes', [])]
        services[name] = service
    services['temporal-namespace'].pop('networks', None)
    services['temporal-namespace']['network_mode'] = 'service:temporal'
    internal_bypass = 'localhost,127.0.0.1,172.17.0.1,127.0.0.0/8,::1,0.0.0.0,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,host.docker.internal,api,mcp,news,redis,silo,opensearch,temporal,temporal-postgresql,frontend-resource-hub,frontend-preview-worker'
    for name, service in services.items():
        bypass = internal_bypass if name.startswith('native-') else internal_bypass.replace('127.0.0.1,172.17.0.1,', '')
        service.setdefault('environment', {}).update(NO_PROXY=bypass, no_proxy=bypass)
    config = {'name': 'runtime-os-source-production', 'services': services,
        'networks': {'control': {'external': True, 'name': 'runtime-os-app-stage_control'},
                     'coding': {'external': True, 'name': 'runtime-os-app-stage_coding'}},
        'volumes': infra.get('volumes', {}), 'secrets': infra.get('secrets', {})}
    serialized = json.dumps(config, indent=2).replace('${RUNTIME_CONFIG_DIR}', str(private))
    (release / 'compose.production.json').write_text(serialized)
    (private / 'secrets').mkdir(exist_ok=True)
    for name in ['redis_password', 'temporal_database_password']:
        secret_path = private / 'secrets' / name
        if not secret_path.exists():
            secret_path.write_text(secrets.token_urlsafe(36))
    # Preserve the existing Redis password and default-user contract.
    command = old['services']['redis']['command']
    redis_password = command[command.index('--requirepass') + 1]
    (private / 'secrets/redis_password').write_text(redis_password)
    redis_config = private / 'redis.conf'
    redis_config.write_text('bind 0.0.0.0\nprotected-mode yes\nappendonly yes\ndir /data\nrequirepass '
                            + json.dumps(redis_password) + '\n')
    redis_config.chmod(0o644)
    services['redis'].pop('entrypoint', None)
    services['redis']['command'] = ['redis-server', '/usr/local/etc/redis/redis.conf']
    services['redis']['ports'] = copy.deepcopy(old['services']['redis'].get('ports', []))
    services['redis']['volumes'] = ['redis-data:/data', str(redis_config) + ':/usr/local/etc/redis/redis.conf:ro']
    services['redis']['healthcheck']['test'] = ['CMD-SHELL', 'REDISCLI_AUTH=$$(cat /run/secrets/redis_password) redis-cli --no-auth-warning ping | grep -q PONG']
    serialized = json.dumps(config, indent=2).replace('${RUNTIME_CONFIG_DIR}', str(private))
    (release / 'compose.production.json').write_text(serialized)
    for filename in ['redis_password', 'temporal_database_password']:
        (private / 'secrets' / filename).chmod(0o444)
    (private / 'release.env').write_text('HUB_TOKEN=' + hub_token + '\nRUNTIME_CONFIG_DIR=' + str(private) + '\n')
    (release / 'acl-targets.json').write_text(json.dumps({'writable': sorted(writable), 'readable': sorted(readable)}, indent=2))
    subprocess.run(['docker', 'compose', '--env-file', str(private / 'release.env'),
                    '-f', str(release / 'compose.production.json'), 'config', '--quiet'], check=True)
    print('Release composition prepared; original configuration and databases preserved.')


if __name__ == '__main__':
    main()
