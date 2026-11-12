"""Build an isolated candidate Compose definition from operator-owned config.

Run on the deployment host. Private generated files remain outside the checkout.
Never prints credentials; does not stop, replace, or prune existing services.
"""
import argparse
import copy
import json
import os
import pathlib
import secrets
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--root', required=True)
p.add_argument('--app-compose', required=True)
p.add_argument('--app-env', required=True)
p.add_argument('--native-private', required=True)
p.add_argument('--connection', required=True)
p.add_argument('--api-image', required=True)
p.add_argument('--node-image', required=True)
p.add_argument('--temporal-image', required=True)
p.add_argument('--database-container', default='postgresql18')
p.add_argument('--database-host', default='host.docker.internal')
p.add_argument('--database-port', default='5432')
p.add_argument('--api-port', type=int, default=19100)
args = p.parse_args()
root = pathlib.Path(args.root).resolve()
if not str(root).startswith('/opt/runtime-os/native-temporal/'):
    raise SystemExit('Use an isolated /opt/runtime-os/native-temporal/ candidate root')
root.mkdir(parents=True, exist_ok=True, mode=0o700)
private = root / 'private'
private.mkdir(exist_ok=True, mode=0o700)
os.umask(0o077)

def read(path):
    return json.loads(pathlib.Path(path).read_text())

def write(path, obj):
    pathlib.Path(path).write_text(json.dumps(obj, indent=2) + '\n')

def run(argv, **kw):
    return subprocess.run(argv, check=True, capture_output=True, text=True, **kw)

app = json.loads(run(['docker', 'compose', '--env-file', args.app_env,
                      '-f', args.app_compose, 'config', '--format', 'json']).stdout)
gateway = read(pathlib.Path(args.native_private) / 'gateway.json')
executor = read(pathlib.Path(args.native_private) / 'executor.json')
connection = read(args.connection)

# Separate database account and separate persistence/visibility databases.
# A private marker allows repeatable bootstrap without changing credentials.
dbfile = private / 'database.json'
if not dbfile.exists():
    suffix = secrets.token_hex(4)
    db = dict(user='temporal_native_' + suffix, password=secrets.token_hex(32),
              main='temporal_native_' + suffix, visibility='temporal_native_vis_' + suffix)
    sql = (f"CREATE ROLE {db['user']} LOGIN PASSWORD '{db['password']}' CONNECTION LIMIT 60;\n"
           f"CREATE DATABASE {db['main']} OWNER {db['user']};\n"
           f"CREATE DATABASE {db['visibility']} OWNER {db['user']};\n")
    run(['docker', 'exec', '-i', '-u', 'postgres', args.database_container,
         'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres'], input=sql)
    write(dbfile, db)
db = read(dbfile)
(private / 'database-password').write_text(db['password'])
os.chown(private / 'database-password', 1000, 1000)

# Private CA, individual client identities, no CA key mounted into containers.
tls = private / 'tls'
tls.mkdir(exist_ok=True)
if not (tls / 'ca.crt').exists():
    run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '90',
         '-keyout', str(tls / 'ca.key'), '-out', str(tls / 'ca.crt'),
         '-subj', '/CN=Runtime Native Temporal Candidate CA'])
for name in ['server', 'api', 'worker1', 'worker2']:
    if not (tls / (name + '.crt')).exists():
        run(['openssl', 'req', '-newkey', 'rsa:2048', '-nodes',
             '-keyout', str(tls / (name + '.key')), '-out', str(tls / (name + '.csr')),
             '-subj', '/CN=runtime-native-' + name])
        ext = tls / (name + '.ext')
        ext.write_text('subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth\n')
        run(['openssl', 'x509', '-req', '-in', str(tls / (name + '.csr')),
             '-CA', str(tls / 'ca.crt'), '-CAkey', str(tls / 'ca.key'), '-CAcreateserial',
             '-days', '30', '-out', str(tls / (name + '.crt')), '-extfile', str(ext)])
    os.chown(tls / (name + '.key'), 1000, 1000)
    os.chmod(tls / (name + '.crt'), 0o644)
os.chmod(tls / 'ca.crt', 0o644)

def tlsmount(name):
    return [f'{tls}/ca.crt:/run/temporal-tls/ca.crt:ro',
            f'{tls}/{name}.crt:/run/temporal-tls/client.crt:ro',
            f'{tls}/{name}.key:/run/temporal-tls/client.key:ro']

services = {}
baseapi = app['services']['api']
conn = copy.deepcopy(connection)
conn['endpoint'] = 'http://127.0.0.1:18461'
conn['defaultNodeId'] = 'cline-worker-1'
conn['nodes'] = {f'cline-worker-{i}': {'endpoint': f'http://127.0.0.1:{18451+i*10}',
                        'recoveryEndpoint': f'http://127.0.0.1:{18452+i*10}'} for i in [1, 2]}
write(private / 'connection.json', conn)

for i in range(3):
    name = 'api' if i == 0 else f'worker{i}'
    svc = copy.deepcopy(baseapi)
    svc.pop('depends_on', None)
    svc['image'] = args.api_image
    svc['container_name'] = 'runtime-native-temporal-' + name
    svc['mem_limit'] = '2g'
    svc['cpus'] = 2
    svc['restart'] = 'unless-stopped'
    svc['ports'] = [{'target': 8080, 'published': str(args.api_port), 'protocol': 'tcp'}] if i == 0 else []
    if not i:
        for network in svc.get('networks', {}).values():
            network['aliases'] = ['runtime-native-temporal-control']
    if i:
        svc.pop('networks', None)
        svc.pop('extra_hosts', None)
        svc['network_mode'] = 'service:temporal-control-api'
        svc['depends_on'] = {'temporal-control-api': {'condition': 'service_started', 'restart': True},
                             'temporal': {'condition': 'service_healthy'}}
    env = svc['environment']
    env['SERVER_PORT'] = str(8080 if not i else 18080+i)
    env['JAVA_TOOL_OPTIONS'] = '-Xms128m -Xmx768m -XX:MaxMetaspaceSize=384m'
    env['CHATCHAT_AGENT_TASK_DATABASE_QUEUE_ENABLED'] = 'false'
    properties = json.loads(env.get('SPRING_APPLICATION_JSON') or '{}')
    properties.update({'chatchat.runtime.coding.scheduler': 'temporal',
        'chatchat.runtime.coding.temporal.target': 'localhost:7233',
        'chatchat.runtime.coding.temporal.namespace': 'runtime-native',
        'chatchat.runtime.coding.temporal.task-queue': 'runtime-native-analysis-v1',
        'chatchat.runtime.coding.temporal.worker-enabled': bool(i),
        'chatchat.runtime.coding.temporal.max-concurrent-tasks': 1,
        'chatchat.runtime.coding.temporal.node-id': f'cline-worker-{i}' if i else '',
        'chatchat.runtime.coding.temporal.tls-enabled': True,
        'chatchat.runtime.coding.temporal.tls-ca-file': '/run/temporal-tls/ca.crt',
        'chatchat.runtime.coding.temporal.tls-certificate-file': '/run/temporal-tls/client.crt',
        'chatchat.runtime.coding.temporal.tls-key-file': '/run/temporal-tls/client.key'})
    env['SPRING_APPLICATION_JSON'] = json.dumps(properties)
    for vol in svc['volumes']:
        if vol['target'] == '/run/secrets/native-coding-connection.json':
            vol['source'] = str(private / 'connection.json')
        if vol['target'] == '/srv/app/logs':
            vol['source'] = str(root / name / 'logs')
            pathlib.Path(vol['source']).mkdir(parents=True, exist_ok=True)
        if vol['target'] == '/srv/app/data':
            vol['source'] = str(root / name / 'data')
            pathlib.Path(vol['source']).mkdir(parents=True, exist_ok=True)
    svc['volumes'].extend(tlsmount(name))
    # Compose automatically publishes the service name as a network alias.
    # Never register another `api` on the existing application's shared network.
    services['temporal-control-api' if not i else name] = svc

def shared(name):
    return dict(image=args.node_image, pull_policy='never', user='0:0',
        container_name='runtime-native-temporal-' + name, restart='unless-stopped',
        network_mode='service:temporal-control-api', mem_limit='512m', cpus=1,
        depends_on={'temporal-control-api': {'condition': 'service_started', 'restart': True}},
        volumes=[f'{root}:{root}'], security_opt=['no-new-privileges:true'])

for i in [1, 2]:
    node = root / f'n{i}'
    # Linux sockaddr_un is 108 bytes including the terminating NUL.
    if len(os.fsencode(str(node / 'w' / ('0'*36) / 'bridge' / 'broker.sock'))) > 107:
        raise SystemExit('Candidate root is too long for Native Unix sockets')
    node.mkdir(exist_ok=True)
    (node / 'template').mkdir(exist_ok=True)
    g, e = copy.deepcopy(gateway), copy.deepcopy(executor)
    g.update(host='127.0.0.1', port=18451+i*10, stateRoot=str(node / 'state'),
             workerRoot=str(node / 'w'), maxWorkers=1, maxPerTenant=1,
             signingKey=secrets.token_hex(32)) if not (private / f'gateway{i}.json').exists() else None
    if (private / f'gateway{i}.json').exists():
        g, e = read(private / f'gateway{i}.json'), read(private / f'executor{i}.json')
    else:
        e.update(instanceId='temporal-native-' + secrets.token_hex(8),
            ownershipKey=secrets.token_hex(32), apiToken=secrets.token_hex(32), signingKey=g['signingKey'],
            workersRoot=str(node / 'w'), hostWorkersRoot=str(node / 'w'),
            controlRoot=str(node / 'executor-control'), socketPath=str(node / 'executor.sock'),
            maxWorkers=1, maxPerTenant=1)
        g['executor'] = dict(socketPath=e['socketPath'], token=e['apiToken'])
        g['issuerKeys'] = {tenant: conn['issuerKey'] for tenant in g['tenants']}
        for cfg in [g, e]:
            for policy in cfg['tenants'].values():
                policy['dynamicUsers'] = True
                policy['workspaces'] = {'project': str(node / 'template')}
                if 'runtimeModelAuthority' in policy:
                    policy['runtimeModelAuthority'] = policy['runtimeModelAuthority'].replace('127.0.0.1:18180', '127.0.0.1:8080')
                if 'mcp' in policy:
                    policy['mcp']['url'] = policy['mcp']['url'].replace('127.0.0.1:18180', '127.0.0.1:8080')
        write(private / f'gateway{i}.json', g)
        write(private / f'executor{i}.json', e)
    g['workerImage'] = e['workerImage']
    write(private / f'gateway{i}.json', g)
    for kind, script in [('node', 'governance-server'), ('recovery', 'recovery-server')]:
        name = kind + str(i)
        svc = shared(name)
        svc['entrypoint'] = ['node', '/opt/runtime-os/coding-node/service/src/' + script + '.mjs']
        svc['environment'] = {'CLINE_GOVERNANCE_CONFIG': str(private / f'gateway{i}.json'),
                               'CLINE_RECOVERY_PORT': str(18452+i*10)}
        services[name] = svc
    name = 'executor' + str(i)
    svc = shared(name)
    svc['network_mode'] = 'host'
    svc.pop('depends_on')
    svc['pid'] = 'host'
    svc['cap_add'] = ['SYS_PTRACE']
    daemon_mount = pathlib.Path(e['dockerSocket']).parent.parent
    svc['volumes'].append(f'{daemon_mount}:{daemon_mount}:ro')
    svc['entrypoint'] = ['node', '/opt/runtime-os/coding-node/service/src/worker-executor.mjs']
    svc['environment'] = {'CLINE_EXECUTOR_CONFIG': str(private / f'executor{i}.json')}
    services[name] = svc

entry = root / 'temporal-entrypoint.sh'
entry.write_text('#!/bin/bash\nset -euo pipefail\nexport POSTGRES_PWD="$(cat /run/secrets/database-password)"\nexec /etc/temporal/entrypoint.sh autosetup\n')
os.chmod(entry, 0o644)
env = {'DB': 'postgres12', 'DB_PORT': args.database_port, 'POSTGRES_SEEDS': args.database_host,
       'BIND_ON_IP': '127.0.0.1', 'TEMPORAL_BROADCAST_ADDRESS': '127.0.0.1',
       'POSTGRES_USER': db['user'], 'DBNAME': db['main'], 'VISIBILITY_DBNAME': db['visibility'],
       'SKIP_DB_CREATE': 'true', 'DEFAULT_NAMESPACE': 'runtime-native', 'NUM_HISTORY_SHARDS': '32',
       'SQL_MAX_CONNS': '5', 'SQL_MAX_IDLE_CONNS': '3',
       'SQL_VIS_MAX_CONNS': '3', 'SQL_VIS_MAX_IDLE_CONNS': '2',
       'TEMPORAL_ADDRESS': '127.0.0.1:7233', 'TEMPORAL_TLS_REQUIRE_CLIENT_AUTH': 'true',
       'TEMPORAL_TLS_SERVER_CERT': '/run/temporal-tls/client.crt',
       'TEMPORAL_TLS_SERVER_KEY': '/run/temporal-tls/client.key',
       'TEMPORAL_TLS_SERVER_CA_CERT': '/run/temporal-tls/ca.crt',
       'TEMPORAL_TLS_CLIENT1_CA_CERT': '/run/temporal-tls/ca.crt',
       'TEMPORAL_TLS_FRONTEND_CERT': '/run/temporal-tls/client.crt',
       'TEMPORAL_TLS_FRONTEND_KEY': '/run/temporal-tls/client.key',
       'TEMPORAL_TLS_FRONTEND_SERVER_NAME': 'localhost',
       'TEMPORAL_TLS_INTERNODE_SERVER_NAME': 'localhost'}
for prefix in ['TEMPORAL_TLS_', 'TEMPORAL_CLI_TLS_']:
    for key, value in [('CA', 'ca.crt'), ('CERT', 'client.crt'), ('KEY', 'client.key')]:
        env[prefix+key] = '/run/temporal-tls/' + value
    env[prefix+'SERVER_NAME'] = 'localhost'
services['temporal'] = dict(image=args.temporal_image, pull_policy='never',
    container_name='runtime-native-temporal-server', restart='unless-stopped',
    network_mode='service:temporal-control-api', mem_limit='2g', cpus=2,
    depends_on={'temporal-control-api': {'condition': 'service_started', 'restart': True}},
    entrypoint=['bash', '/opt/runtime/temporal-entrypoint.sh'], environment=env,
    volumes=tlsmount('server') + [f'{entry}:/opt/runtime/temporal-entrypoint.sh:ro',
                f'{private}/database-password:/run/secrets/database-password:ro'],
    healthcheck=dict(test=['CMD', 'nc', '-z', '127.0.0.1', '7233'], interval='5s', timeout='3s', retries=60))
compose = dict(name='runtime-native-temporal-candidate', services=services,
               networks=app['networks'])
for service in services.values():
    service['logging'] = {'driver': 'json-file', 'options': {'max-size': '20m', 'max-file': '3'}}
write(root / 'compose.private.json', compose)
os.chmod(root / 'compose.private.json', 0o600)
print(json.dumps({'root': str(root), 'services': list(services), 'apiPort': args.api_port,
                  'database': db['main'], 'tls': 'mutual', 'status': 'CONFIGURED'}))
