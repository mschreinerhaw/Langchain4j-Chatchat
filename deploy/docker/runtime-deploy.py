#!/usr/bin/env python3
"""Unified offline orchestration of the existing Compose definitions; Python stdlib only.

No database creation/migrations in existing mode. No image pulls, builds, down -v,
index cleanup, data copying, or automatic replacement of an installation's paths.
Rendered Compose and raw command output are private: they may contain legacy env secrets.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import stat
import subprocess
import urllib.parse
import urllib.request
import uuid

APPLICATIONS = ('api', 'mcp', 'news', 'coding-agent', 'node-proxy', 'gateway')
ROOT = Path(__file__).resolve().parents[2]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    if os.name != 'nt': os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w', encoding='utf8') as stream:
        json.dump(value, stream, indent=2)


def command(args, output, env=None, stdin=None):
    result = subprocess.run(args, input=stdin, capture_output=True, text=True, env=env)
    if result.returncode:
        private_json(output / 'last-error.private.json', {'command': args[:2], 'stdout': result.stdout, 'stderr': result.stderr})
        raise RuntimeError('Command failed; inspect private last-error.private.json')
    return result.stdout.strip()


def read_env(path):
    values = {}
    for line in Path(path).read_text(encoding='utf8').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def validate_spec(spec):
    require(spec.get('mode') in ('full', 'existing'), 'mode must be full or existing')
    require(re.fullmatch(r'[a-z0-9][a-z0-9_-]+', spec.get('project', '')), 'An explicit Compose project is required')
    require(spec.get('databases') and all(k in spec['databases'] for k in ('api', 'mcp', 'news')), 'Specify API, MCP and News databases independently')
    for service, db in spec['databases'].items():
        require(db['engine'] in ('postgresql', 'mysql', 'h2'), 'Unknown database engine: ' + service)
        if db['engine'] == 'h2':
            require(service == 'news', 'API/MCP cannot silently fall back to H2')
            require(spec['mode'] == 'existing', 'New full installations use an explicit server database for News')
            require(db.get('existing_file'), 'An existing News H2 file must be named explicitly')
        else:
            prefix = 'jdbc:' + db['engine'] + '://'
            require(db['url'].startswith(prefix), service + ' JDBC URL does not match engine ' + db['engine'])
            require('createDatabaseIfNotExist' not in db['url'], 'Automatic database substitution/creation is forbidden')
            require(db.get('username') and db.get('password_file'), service + ' database credentials are incomplete')
            require(db.get('expected_tables'), service + ' schema contract is required')
    if spec['mode'] == 'full':
        engines = {db['engine'] for db in spec['databases'].values()}
        require(len(engines) == 1, 'Full bootstrap uses one explicit database engine; existing mode supports mixed engines')
        require(spec.get('new_installation') is True, 'Full mode requires new_installation=true; never use it to adopt existing data')
        require(spec.get('bootstrap_admin_password_file'), 'Full mode requires an independent database bootstrap password file')
        for service, db in spec['databases'].items():
            parsed = urllib.parse.urlsplit(db['url'][5:])
            require(parsed.hostname == ('postgresql' if db['engine'] == 'postgresql' else 'mysql'),
                    'Full mode must use its managed database service; external databases require existing mode')
            expected = {'api': 'live_runtime_api', 'mcp': 'live_runtime_mcp', 'news': 'chatchat_news'}[service]
            require(parsed.path.lstrip('/') == expected, 'Full bootstrap schema name differs from its reviewed initialization scripts')
            require(db['username'] == 'chatchat_' + service, 'Full bootstrap user differs from its initialization script')
    search = spec['opensearch']
    require(search['endpoint'].startswith('https://'), 'OpenSearch requires authenticated TLS')
    require(search.get('username') and search.get('password_file') and search.get('ca_file') and search.get('truststore_file'), 'OpenSearch authentication/CA/truststore are required')
    require(spec['news'].get('index_prefix') and '*' not in spec['news']['index_prefix'], 'News index prefix must be explicit, without wildcards')
    require(spec['news'].get('retention_days', 0) > 0, 'Preserve an explicit positive News retention policy')
    require(spec['financial'].get('storage') in ('LOCAL_H2', 'PRIMARY'), 'Choose the existing financial storage explicitly')
    require(spec['temporal'].get('mode') in ('external', 'embedded'), 'Select Temporal persistence explicitly')
    require(spec['temporal']['mode'] != 'embedded' or spec.get('allow_ephemeral_temporal') is True,
            'Embedded Temporal is memory-only; explicitly acknowledge it or configure durable external Temporal')


def normalize(spec, output):
    """Docker Compose is the YAML parser; no pip/YAML package is needed on an offline host."""
    env = dict(os.environ)
    env.update(read_env(spec['compose_env_file']))
    # Normalization must expand the original full Compose even when infrastructure
    # is removed below. These aliases are file references, never generated passwords.
    for service in ('api', 'mcp', 'news'):
        db = spec['databases'][service]
        if db.get('password_file'):
            env['POSTGRESQL_' + service.upper() + '_PASSWORD_FILE'] = db['password_file']
            env['MYSQL_' + service.upper() + '_PASSWORD_FILE'] = db['password_file']
    if spec.get('bootstrap_admin_password_file'):
        env['MYSQL_ROOT_PASSWORD_FILE'] = spec['bootstrap_admin_password_file']
    env['NEWS_DATABASE_PASSWORD_FILE'] = spec['databases']['news'].get('password_file', spec['secrets']['news_internal_key'])
    env['POSTGRESQL_ADMIN_PASSWORD_FILE'] = spec.get('bootstrap_admin_password_file', env.get('POSTGRESQL_ADMIN_PASSWORD_FILE', spec['secrets']['api_internal_key']))
    env['OPENSEARCH_ADMIN_PASSWORD_FILE'] = spec['opensearch']['password_file']
    aliases = {'NEWS_INTERNAL_KEY_FILE': 'news_internal_key', 'INTERNAL_CREDENTIAL_ENCRYPTED_FILE': 'internal_credential_encrypted',
               'WSA_SECRET_ID_FILE': 'wsa_secret_id', 'WSA_SECRET_KEY_FILE': 'wsa_secret_key'}
    for key, name in aliases.items():
        if name in spec.get('secrets', {}): env[key] = spec['secrets'][name]
    base = spec.get('application_compose', str(ROOT / 'deploy/docker/application/compose.yaml'))
    data = json.loads(command(['docker', 'compose', '--profile', '*', '-p', spec['project'], '--env-file', spec['compose_env_file'], '-f', base, 'config', '--format', 'json'], output, env))
    private_json(output / 'original.compose.private.json', data)
    services = {name: copy.deepcopy(data['services'][name]) for name in APPLICATIONS}
    for service in services.values():
        service['depends_on'] = {k: v for k, v in service.get('depends_on', {}).items() if k in services}
        service['pull_policy'] = 'never'
    infra = None
    if spec['mode'] == 'full':
        if spec['databases']['api']['engine'] == 'mysql':
            infra = json.loads(command(['docker', 'compose', '--profile', '*', '-p', spec['project'], '--env-file', spec['compose_env_file'], '-f',
                str(ROOT / 'deploy/docker/docker-compose.yaml'), 'config', '--format', 'json'], output, env))
            for item in infra['services'].values(): item['networks'] = {'control': {}}
            infra['networks'] = {'control': data['networks']['control']}
        else:
            infra = {**data, 'services': {k: v for k, v in data['services'].items() if k not in APPLICATIONS}}
        for item in infra['services'].values(): item['pull_policy'] = 'never'
        # Applications start only after the independent infrastructure command succeeds.
        private_json(output / 'infrastructure.compose.json', infra)
    else:
        data['networks']['control'] = {'external': True, 'name': spec['control_network']}
    if spec['temporal'].get('provision'):
        require(spec['temporal']['mode'] == 'external', 'Managed durable Temporal is external to the News process')
        env['TEMPORAL_DATABASE_PASSWORD_FILE'] = spec['temporal']['password_file']
        temporal = json.loads(command(['docker', 'compose', '-p', spec['project'] + '-temporal', '-f',
            str(ROOT / 'deploy/temporal/docker-compose.yml'), 'config', '--format', 'json'], output, env))
        temporal.setdefault('networks', {})['runtime-control'] = {'external': True, 'name': spec['control_network']}
        temporal['services']['temporal'].setdefault('networks', {})['runtime-control'] = {}
        private_json(output / 'temporal.compose.json', temporal)
    data['services'] = services
    for name in ('api', 'mcp', 'news'):
        db = spec['databases'][name]
        envs = services[name].setdefault('environment', {})
        if db['engine'] != 'h2':
            for key in ('SPRING_DATASOURCE_PASSWORD', 'CHATCHAT_' + name.upper() + '_POSTGRESQL_PASSWORD',
                        'CHATCHAT_' + name.upper() + '_MYSQL_PASSWORD'):
                envs.pop(key, None)
            envs.update(SPRING_DATASOURCE_URL=db['url'], SPRING_DATASOURCE_USERNAME=db['username'],
                SPRING_DATASOURCE_DRIVER_CLASS_NAME='org.postgresql.Driver' if db['engine'] == 'postgresql' else 'com.mysql.cj.jdbc.Driver',
                SPRING_JPA_HIBERNATE_DDL_AUTO='validate')
            if name == 'news': envs['SPRING_PROFILES_ACTIVE'] = db['engine']
            else:
                envs['CHATCHAT_DATASOURCE_CONFIG'] = 'datasource-' + db['engine'] + '.yml'
                envs['SPRING_CONFIG_IMPORT'] = 'classpath:datasource-' + db['engine'] + '.yml,optional:configtree:/run/secrets/'
            data.setdefault('secrets', {})['unified_' + name + '_db'] = {'file': db['password_file']}
            services[name].setdefault('secrets', []).append({'source': 'unified_' + name + '_db', 'target': 'spring.datasource.password'})
    search = spec['opensearch']
    news_env = services['news'].setdefault('environment', {})
    news_env.update(SPRING_CONFIG_IMPORT='optional:configtree:/run/secrets/',
        CHATCHAT_RUNTIME_NEWS_OPENSEARCH_ENABLED='true', CHATCHAT_RUNTIME_NEWS_OPENSEARCH_ENDPOINT=search['endpoint'],
        CHATCHAT_RUNTIME_NEWS_OPENSEARCH_USERNAME=search['username'], CHATCHAT_RUNTIME_NEWS_OPENSEARCH_INSECURESSL='false',
        CHATCHAT_RUNTIME_NEWS_OPENSEARCH_INDEXNAME=spec['news']['index_prefix'],
        CHATCHAT_RUNTIME_NEWS_OPENSEARCH_RETENTIONDAYS=str(spec['news']['retention_days']),
        CHATCHAT_RUNTIME_NEWS_WEBSEARCH_ENABLED='true', CHATCHAT_RUNTIME_NEWS_WEBSEARCH_CACHE_ENABLED='true',
        CHATCHAT_RUNTIME_NEWS_WEBSEARCH_CACHE_INDEXNAME=spec['news']['cache_prefix'],
        CHATCHAT_RUNTIME_NEWS_TEMPORAL_ENABLED='true', CHATCHAT_RUNTIME_NEWS_TEMPORAL_SERVERMODE=spec['temporal']['mode'],
        CHATCHAT_RUNTIME_NEWS_TEMPORAL_TARGET=spec['temporal']['target'],
        CHATCHAT_RUNTIME_NEWS_TEMPORAL_NAMESPACE=spec['temporal'].get('namespace', 'default'),
        CHATCHAT_RUNTIME_NEWS_TEMPORAL_TASKQUEUE=spec['temporal'].get('task_queue', 'chatchat-news-collection'),
        CHATCHAT_RUNTIME_NEWS_TEMPORAL_TLSENABLED=str(spec['temporal'].get('tls_enabled', False)).lower(),
        CHATCHAT_RUNTIME_NEWS_MCP_SERVER_BASE_URL='http://mcp:8090',
        CHATCHAT_INTERNALCREDENTIAL_CRYPTOKEYFILE='/run/secrets/news_internal_key')
    for name, target in [('news_internal_key', 'news_internal_key'),
                         ('internal_credential_encrypted', 'chatchat.internal-credential.encrypted-secret'),
                         ('wsa_secret_id', 'chatchat.runtime.news.web-search.secret-id'),
                         ('wsa_secret_key', 'chatchat.runtime.news.web-search.secret-key')]:
        if name in spec.get('secrets', {}):
            source = 'unified_' + name
            data.setdefault('secrets', {})[source] = {'file': spec['secrets'][name]}
            services['news']['secrets'] = [s for s in services['news'].get('secrets', []) if s.get('target') != target]
            services['news']['secrets'].append({'source': source, 'target': target})
    for name in ('api', 'mcp', 'news'):
        environment = services[name].setdefault('environment', {})
        prefix = {'api': 'CHATCHAT_SEARCH_OPENSEARCH_', 'mcp': 'CHATCHAT_MCP_LUCENE_OPENSEARCH_', 'news': 'CHATCHAT_RUNTIME_NEWS_OPENSEARCH_'}[name]
        environment[prefix + ('ENDPOINT' if name == 'news' else 'URL')] = search['endpoint']
        environment[prefix + 'USERNAME'] = search['username']
        environment.pop(prefix + 'PASSWORD', None)
        environment[prefix + ('INSECURE_SSL' if name == 'api' else 'INSECURESSL')] = 'false'
        target = {'api': 'CHATCHAT_SEARCH_OPENSEARCH_PASSWORD', 'mcp': 'chatchat.mcp.lucene.open-search.password', 'news': 'chatchat.runtime.news.open-search.password'}[name]
        data.setdefault('secrets', {})['unified_search_password'] = {'file': search['password_file']}
        services[name]['secrets'] = [s for s in services[name].get('secrets', []) if s.get('target') != target]
        services[name]['secrets'].append({'source': 'unified_search_password', 'target': target})
        services[name]['volumes'] = [mount for mount in services[name].get('volumes', [])
                                    if mount.get('target') != '/run/runtime-truststore.p12']
        services[name]['volumes'].append({'type': 'bind', 'source': search['truststore_file'],
            'target': '/run/runtime-truststore.p12', 'read_only': True, 'bind': {'create_host_path': False}})
        options = environment.get('JAVA_TOOL_OPTIONS', '')
        options = re.sub(r'-Djavax.net.ssl.trustStore(?:Password|Type)?=\S+\s*', '', options)
        environment['JAVA_TOOL_OPTIONS'] = options + ' -Djavax.net.ssl.trustStore=/run/runtime-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit'
    services['mcp']['environment'].update(CHATCHAT_MCP_NEWS_RUNTIME_BASE_URL='http://news:8091',
        CHATCHAT_MARKET_STORAGE=spec['financial']['storage'])
    services['coding-agent']['environment']['CLINE_NATIVE_SDK_ENABLED'] = 'true'
    for name, hosts in spec.get('extra_hosts', {}).items():
        require(name in services, 'Unknown service in extra_hosts')
        existing = services[name].get('extra_hosts', {})
        if isinstance(existing, list):
            existing = dict(entry.split('=' if '=' in entry else ':', 1) for entry in existing)
        services[name]['extra_hosts'] = {**existing, **hosts}
    for name, limits in spec.get('resources', {}).items():
        require(name in services, 'Unknown resource service: ' + name)
        require(set(limits).issubset({'mem_limit', 'cpus', 'pids_limit'}), 'Unsupported resource setting')
        services[name].update(limits)
    used = {secret['source'] for service in services.values() for secret in service.get('secrets', [])}
    data['secrets'] = {k: v for k, v in data.get('secrets', {}).items() if k in used}
    private_json(output / 'applications.compose.json', data)
    return data


def prepare_new_installation(spec):
    require(spec['mode'] == 'full' and spec.get('new_installation') is True, 'prepare is only for an explicitly new full installation')
    env = read_env(spec['compose_env_file'])
    stage = Path(env['STAGE_ROOT']).resolve()
    stage.mkdir(parents=True, exist_ok=True)
    created = []
    for key in ('API_DATA_DIR', 'MCP_DATA_DIR', 'NEWS_DATA_DIR', 'NEWS_CONFIG_DIR'):
        path = Path(env[key]).resolve()
        require(path.is_relative_to(stage), 'New-install paths must stay under the explicit STAGE_ROOT')
        if key.endswith('DATA_DIR') and path.exists():
            require(not any(path.iterdir()), 'Nonempty application data found; select existing mode instead')
        path.mkdir(parents=True, exist_ok=True);created.append(str(path))
    config = stage / 'config';config.mkdir(exist_ok=True)
    for service in ('api', 'mcp', 'news'):
        (stage / service / 'logs').mkdir(parents=True, exist_ok=True)
        path = config / (service + '.env')
        if not path.exists(): path.write_text('# Ordinary environment only; credentials belong in Secret files.\n')
    for name in ('node-proxy.conf', 'public-gateway.conf'):
        path = config / name
        if not path.exists(): shutil.copy2(ROOT / 'deploy/docker/application' / name, path)
    for path in (Path(env['MODEL_CONFIG_FILE']), Path(env['NEWS_CONFIG_DIR']) / 'application.yml'):
        require(path.resolve().is_relative_to(stage), 'Configuration destination outside STAGE_ROOT')
        if not path.exists(): path.write_text('{}\n')
    return {'createdDirectories': created, 'credentialsCreated': False,
            'next': 'Supply approved Secret files, TLS certificates/truststore, License and configured models before check/up'}


def local_checks(spec, compose, output):
    failures, warnings = [], []
    for service, definition in compose['services'].items():
        image = definition['image']
        command(['docker', 'image', 'inspect', image], output)
        expected = spec.get('image_ids', {}).get(service)
        if expected:
            actual = json.loads(command(['docker', 'image', 'inspect', image], output))[0]['Id']
            if actual != expected: failures.append(service + ' image identity differs from offline manifest')
        elif spec.get('require_image_ids', True): failures.append(service + ' image ID missing from offline manifest')
        for mount in definition.get('volumes', []):
            if mount['type'] == 'bind' and not Path(mount['source']).exists(): failures.append(service + ' missing bind: ' + mount['source'])
            elif mount['type'] == 'bind' and Path(mount['source']).is_dir() and not mount.get('read_only'):
                args = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none']
                if definition.get('user'): args += ['--user', definition['user']]
                command(args + ['--mount', 'type=bind,source=' + mount['source'] + ',target=/run/data-permission-probe',
                    '--entrypoint', 'sh', image, '-c', 'test -w /run/data-permission-probe'], output)
        # Check as the configured container user, not just as host root.
        for secret in definition.get('secrets', []):
            path = compose['secrets'][secret['source']]['file']
            if not Path(path).is_file(): continue
            args = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none']
            if definition.get('user'): args += ['--user', definition['user']]
            command(args + ['-v', str(Path(path).resolve()) + ':/run/readability-probe:ro',
                '--entrypoint', 'sh', image, '-c', 'test -r /run/readability-probe'], output)
    for name, value in compose.get('secrets', {}).items():
        path = Path(value['file'])
        if not path.is_file(): failures.append('Missing secret: ' + name); continue
        if os.name != 'nt' and path.stat().st_mode & stat.S_IRWXO: failures.append('World-accessible secret: ' + name)
        if name not in ('unified_wsa_secret_id', 'unified_wsa_secret_key') and not path.read_bytes().strip(): failures.append('Empty secret: ' + name)
    for db in spec['databases'].values():
        if db['engine'] == 'h2' and not Path(db['existing_file']).is_file(): failures.append('Existing News H2 file absent; no replacement will be created')
    for directory in spec.get('persistent_directories', []):
        if not Path(directory).is_dir(): failures.append('Missing persistent directory: ' + directory)
    financial = spec['financial']
    if financial['storage'] == 'LOCAL_H2' and spec['mode'] == 'existing':
        if not Path(financial['existing_file']).is_file(): failures.append('Existing financial database absent; no empty substitute allowed')
    for name in ('wsa_secret_id', 'wsa_secret_key'):
        path = spec.get('secrets', {}).get(name)
        if not path or not Path(path).read_bytes().strip(): warnings.append('External WSA acceptance blocked: missing ' + name)
    if spec['temporal']['mode'] == 'embedded': warnings.append('Temporal workflow history is memory-only; durable deployment not accepted')
    private_json(output / 'preflight.json', {'failures': failures, 'warnings': warnings})
    require(not failures, 'Preflight failed: ' + '; '.join(failures))
    command(['docker', 'compose', '-p', spec['project'], '-f', str(output / 'applications.compose.json'), 'config', '-q'], output)
    return warnings


def business_probes(spec, output):
    """Explicit HTTP/process contracts; never equates an empty HTTP 200 with business success.

    Request headers and bodies can live in private files. Commands are argv arrays,
    with no shell evaluation, and must be installation-owned acceptance programs.
    Missing probes remain BLOCKED and make verify fail.
    """
    required = ('runtime_start', 'mcp_registration', 'mcp_news', 'news_collection', 'news_search',
                'financial_search', 'news_opensearch', 'external_web_search', 'local_fallback',
                'runtime_web_search', 'cline_model', 'data_preservation')
    results = {}
    for name in required:
        probe = spec.get('acceptance', {}).get(name)
        if not probe:
            results[name] = {'status': 'BLOCKED', 'reason': 'Acceptance probe not configured'};continue
        try:
            if 'argv' in probe:
                require(probe.get('expect'), 'A business result contract is required')
                result = json.loads(command(probe['argv'], output))
            else:
                headers = json.loads(Path(probe['headers_file']).read_text()) if probe.get('headers_file') else {}
                headers.setdefault('Content-Type', 'application/json')
                body = Path(probe['body_file']).read_bytes() if probe.get('body_file') else None
                client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                with client.open(urllib.request.Request(probe['url'], data=body, headers=headers,
                        method=probe.get('method', 'GET')), timeout=probe.get('timeout_seconds', 60)) as response:
                    result = json.load(response)
            require(probe.get('expect'), 'HTTP success alone is not an acceptance contract')
            for dotted, expected in probe['expect'].items():
                value = result
                for part in dotted.split('.'): value = value[int(part)] if isinstance(value, list) else value[part]
                if isinstance(expected, dict) and set(expected) == {'minimum'}:
                    require(value >= expected['minimum'], name + ' returned insufficient evidence')
                else: require(value == expected, name + ' business contract differs')
            results[name] = {'status': 'PASSED'}
        except Exception as error:
            results[name] = {'status': 'FAILED', 'reason': type(error).__name__}
    private_json(output / 'business-acceptance.json', results)
    require(all(row['status'] == 'PASSED' for row in results.values()), 'Business acceptance incomplete; see business-acceptance.json')
    return results


def database_probe(db, network, output):
    """Read-only schema contract. Never CREATE DATABASE or run schema initialization here."""
    if db['engine'] == 'h2':
        return {'status': 'FILE_PRESENT', 'schema': 'Must also pass the running News/JPA business probes'}
    uri = urllib.parse.urlsplit(db['url'][5:])
    database = uri.path.lstrip('/')
    require(re.fullmatch(r'[A-Za-z0-9_]+', database), 'Explicit database name required')
    sql = "SELECT table_name FROM information_schema.tables WHERE table_schema=" + ("'public'" if db['engine'] == 'postgresql' else 'DATABASE()')
    password_mount = str(Path(db['password_file']).resolve()) + ':/run/db-password:ro'
    if db['engine'] == 'postgresql':
        script = 'export PGPASSWORD="$(cat /run/db-password)"; exec psql -w "$@"'
        client = ['psql', '-h', uri.hostname, '-p', str(uri.port or 5432), '-U', db['username'], '-d', database, '-At', '-c', sql]
    else:
        script = 'export MYSQL_PWD="$(cat /run/db-password)"; exec mysql "$@"'
        client = ['mysql', '-h', uri.hostname, '-P', str(uri.port or 3306), '-u', db['username'], '-D', database, '-N', '-e', sql]
    image = db['client_image']
    command(['docker', 'image', 'inspect', image], output)
    probe_args = ['docker', 'run', '--rm', '--pull', 'never', '--network', network,
        '--add-host', 'host.docker.internal:host-gateway', '-v', password_mount,
        '--entrypoint', 'sh', image, '-c', script]
    rows = command(probe_args + client, output).splitlines()
    missing = sorted(set(db['expected_tables']) - set(rows))
    require(not missing, 'Existing schema is incomplete; refusing to replace it: ' + ','.join(missing))
    migration = 'No global migration ledger configured; table contract only'
    if db.get('migration_ledger'):
        queries = {'flyway': 'SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank DESC LIMIT 1',
                   'liquibase': 'SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1'}
        require(db['migration_ledger'] in queries and db.get('migration_version'), 'Specify a supported migration ledger and expected version')
        version_client = [*client[:-1], queries[db['migration_ledger']]]
        migration = command(probe_args + version_client, output).strip()
        require(migration == db['migration_version'], 'Database migration version differs from the approved release')
    return {'status': 'SCHEMA_CONTRACT_VERIFIED', 'tables': len(rows), 'expectedTables': db['expected_tables'],
            'migrationVersion': migration}


def search_probe(spec, output, write=False):
    conf = spec['opensearch']
    context = ssl.create_default_context(cafile=conf['ca_file'])
    manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    endpoint = conf.get('probe_endpoint', conf['endpoint'])
    require(endpoint.startswith('https://'), 'Host-side probe also requires authenticated TLS')
    manager.add_password(None, endpoint, conf['username'], Path(conf['password_file']).read_text().strip())
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context), urllib.request.HTTPBasicAuthHandler(manager))
    def request(path, method='GET', body=None):
        req = urllib.request.Request(endpoint.rstrip('/') + path, method=method,
            data=json.dumps(body).encode() if body is not None else None, headers={'Content-Type': 'application/json'})
        with client.open(req, timeout=20) as response: return json.load(response)
    health = request('/_cluster/health')
    require(health['status'] != 'red', 'OpenSearch cluster is red')
    indices = request('/_cat/indices?format=json&h=index,uuid,docs.count')
    private_json(output / 'indices.json', indices)
    if write:
        # A new dedicated, uniquely named probe index. No existing index is modified or deleted.
        index = 'runtime-deployment-probe-' + uuid.uuid4().hex
        request('/' + index, 'PUT', {'settings': {'number_of_shards': 1, 'number_of_replicas': 0}})
        request('/' + index + '/_doc/probe?refresh=wait_for', 'PUT', {'probe': index})
        require(request('/' + index + '/_doc/probe')['_source']['probe'] == index, 'OpenSearch write/read failed')
    return {'status': 'AUTHENTICATED_TLS_VERIFIED', 'clusterStatus': health['status'], 'indices': len(indices), 'writeRead': write}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'plan', 'check', 'up', 'verify', 'snapshot'))
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--services', nargs='+', choices=APPLICATIONS)
    parser.add_argument('--write-probe', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    output = args.output.resolve();output.mkdir(parents=True, exist_ok=True)
    spec = json.loads(args.manifest.read_text(encoding='utf8'));validate_spec(spec)
    if args.action == 'prepare':
        print(json.dumps(prepare_new_installation(spec)));return
    compose = normalize(spec, output)
    if args.action == 'plan':
        print(json.dumps({'mode': spec['mode'], 'services': list(compose['services']), 'output': str(output)}));return
    warnings = local_checks(spec, compose, output)
    if args.action in ('check', 'snapshot', 'verify', 'up'):
        checks = {}
        if spec['mode'] == 'existing' or args.action in ('snapshot', 'verify'):
            checks['databases'] = {k: database_probe(v, spec['control_network'], output) for k, v in spec['databases'].items()}
            checks['opensearch'] = search_probe(spec, output, args.write_probe)
        if args.action == 'up':
            require(spec.get('backup_receipt') and Path(spec['backup_receipt']).is_file(), 'A completed backup receipt is required before container changes')
            backup = json.loads(Path(spec['backup_receipt']).read_text())
            require(backup.get('status') == 'VERIFIED' and backup.get('restoreVerified') is True,
                    'Backup receipt must document a verified restore, not just an existing filename')
            if spec['mode'] == 'existing':
                probe = spec.get('quiescence_probe')
                require(probe and probe.get('argv'), 'Existing installation needs an active-task/collection quiescence probe')
                state = json.loads(command(probe['argv'], output))
                require(state.get('activeTasks') == 0 and state.get('activeCollections') == 0,
                        'Running Runtime tasks or News collections prevent replacement')
            selected = args.services or list(APPLICATIONS)
            if spec['mode'] == 'full':
                require(not args.services, 'Full installation must start its complete topology')
                infra = json.loads((output / 'infrastructure.compose.json').read_text())
                for volume in infra.get('volumes', {}).values():
                    name = volume.get('name')
                    if name:
                        exists = subprocess.run(['docker', 'volume', 'inspect', name], capture_output=True).returncode == 0
                        require(not exists, 'Infrastructure volume already exists; use existing mode instead of initializing another database')
                for service in infra['services'].values(): command(['docker', 'image', 'inspect', service['image']], output)
                for name, service in infra['services'].items():
                    actual = json.loads(command(['docker', 'image', 'inspect', service['image']], output))[0]['Id']
                    if spec.get('require_image_ids', True):
                        require(actual == spec.get('infrastructure_image_ids', {}).get(name), 'Offline infrastructure image identity missing or different: ' + name)
                    for secret in service.get('secrets', []):
                        path = infra['secrets'][secret['source']]['file']
                        readability_args = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none']
                        if name in ('postgresql', 'mysql'): readability_args += ['--user', 'postgres' if name == 'postgresql' else 'mysql']
                        command(readability_args + ['-v', path + ':/run/readability-probe:ro', '--entrypoint', 'sh', service['image'],
                            '-c', 'test -r /run/readability-probe'], output)
                command(['docker', 'compose', '-p', spec['project'], '-f', str(output / 'infrastructure.compose.json'),
                    'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '600'], output)
            if spec['temporal'].get('provision'):
                temporal = json.loads((output / 'temporal.compose.json').read_text())
                for name, service in temporal['services'].items():
                    actual = json.loads(command(['docker', 'image', 'inspect', service['image']], output))[0]['Id']
                    if spec.get('require_image_ids', True):
                        require(actual == spec['temporal'].get('image_ids', {}).get(name),
                                'Offline Temporal image identity missing or different: ' + name)
                require(spec['temporal'].get('new_installation') is True, 'Use an existing durable Temporal target for upgrades; automatic schema changes are forbidden')
                for volume in temporal.get('volumes', {}).values():
                    if volume.get('name'):
                        exists = subprocess.run(['docker', 'volume', 'inspect', volume['name']], capture_output=True).returncode == 0
                        require(not exists, 'Existing Temporal volume found; reuse the service without auto-setup')
                command(['docker', 'compose', '-p', spec['project'] + '-temporal', '-f', str(output / 'temporal.compose.json'),
                    'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '300'], output)
            before = command(['docker', 'compose', '-p', spec['project'], '-f', str(output / 'applications.compose.json'), 'ps', '--all', '--format', 'json'], output)
            private_json(output / 'containers-before.private.json', before)
            command(['docker', 'compose', '-p', spec['project'], '-f', str(output / 'applications.compose.json'),
                'up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '600', *selected], output)
            if 'api' in selected:
                # The sidecar shares API's network namespace; restart alone cannot rebind it.
                command(['docker', 'compose', '-p', spec['project'], '-f', str(output / 'applications.compose.json'),
                    'up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', 'node-proxy'], output)
                command(['docker', 'compose', '-p', spec['project'], '-f', str(output / 'applications.compose.json'),
                    'exec', '-T', 'gateway', 'nginx', '-s', 'reload'], output)
            if spec['mode'] == 'full':
                checks['databases'] = {k: database_probe(v, spec['control_network'], output) for k, v in spec['databases'].items()}
                checks['opensearch'] = search_probe(spec, output, args.write_probe)
        if args.action in ('up', 'verify'): checks['business'] = business_probes(spec, output)
        private_json(output / 'checks.json', {'checks': checks, 'warnings': warnings,
            'businessAcceptance': 'PASSED' if 'business' in checks else 'NOT_RUN'})
        print(json.dumps({'status': 'TECHNICAL_CHECKS_COMPLETED', 'warnings': warnings,
            'businessAcceptance': 'PASSED' if 'business' in checks else 'NOT_RUN', 'evidence': str(output)}))


if __name__ == '__main__':
    main()
