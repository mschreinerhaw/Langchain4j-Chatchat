"""Deploy a complete Studio application JAR, preserving runtime policy and an exact rollback."""
import copy
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import time
import urllib.request
import urllib.error


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def apply_database_migration(root, manifest, execute=subprocess.run):
    """Apply an explicit hash-pinned additive migration after backup, before JPA validate starts."""
    migration = manifest.get('databaseMigration')
    if migration is None:
        return None
    assert migration['file'] == 'schema.sql', 'Unexpected database migration path'
    path = root / migration['file']
    assert not path.is_symlink() and path.is_file(), 'Missing regular migration file'
    content = path.read_bytes()
    assert hashlib.sha256(content).hexdigest() == migration['sha256'], 'Database migration digest mismatch'
    execute(['docker', 'exec', '-i', '-u', 'postgres', 'postgres', 'psql', '--single-transaction',
             '-v', 'ON_ERROR_STOP=1', '-d', 'runtime_os_acceptance_api'], input=content, check=True,
            stdout=subprocess.DEVNULL)
    return migration['sha256']


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


def verify_catalog_readiness(root, manifest):
    """A listening/auth socket is insufficient: require the API-side tool registry."""
    if not manifest.get('catalogReadinessAuthFile'):
        return None
    assert manifest['catalogReadinessAuthFile'] == 'catalog-readiness-private.json'
    credentials_path = root / manifest['catalogReadinessAuthFile']
    assert credentials_path.is_file() and not credentials_path.is_symlink()
    credentials = json.loads(credentials_path.read_text())
    expected = set(manifest.get('catalogReadinessExpectedTools', []))
    assert expected, 'Declare the existing published tools required for release readiness'
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path, body=None, token=None):
        headers = {'Content-Type': 'application/json'}
        if token: headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request('http://127.0.0.1:18180/api/v1' + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers)
        with client.open(req, timeout=10) as response:
            value = json.load(response)
            if value.get('code') != 200: raise RuntimeError('Catalog readiness API rejected request')
            return value['data']
    token = request('/enterprise/auth/login', credentials)['token']
    deadline = time.monotonic() + 300
    last_refresh = 0
    while time.monotonic() < deadline:
        status = request('/mcp/center/recovery/status', token=token)
        tools = request('/mcp/tools', token=token)
        present = {tool.get('remoteToolName') for tool in tools if tool.get('description')
            and tool.get('serviceId') == 'chatchat-mcp-server'}
        if status.get('state') == 'HEALTHY' and expected <= present:
            return {'status': 'API_MCP_CATALOG_READY', 'registryState': status['state'],
                'registeredToolCount': len(tools), 'requiredTools': sorted(expected),
                'capturedAt': datetime.now(timezone.utc).isoformat()}
        if time.monotonic() - last_refresh > 30:
            try:
                request('/mcp/refresh', {}, token)
            except (urllib.error.HTTPError, TimeoutError):
                # Startup license/catalog checks remain mandatory; retry within the deadline.
                pass
            last_refresh = time.monotonic()
        time.sleep(2)
    raise RuntimeError('API-side MCP catalog did not become ready; publication/listening alone is insufficient')


def main():
    os.umask(0o077)
    root = Path(__file__).resolve().parent
    assert (root / 'restrict-coding-worker.sh').is_file(), 'Missing network restriction deployment dependency'
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('worker'):
        assert manifest['worker'].get('modules'), 'Worker module hashes are required before stopping services'
        assert all(module.endswith('.js') and '/' not in module and len(digest)==64
            for module,digest in manifest['worker']['modules'].items()), 'Invalid worker module manifest'
    stage = Path('/opt/runtime-os-docker-stage')
    api, proxy = 'runtime-os-app-stage-api-1', 'runtime-os-app-stage-node-proxy-1'
    node, mcp = 'runtime-os-app-stage-coding-agent-1', 'runtime-os-app-stage-mcp-1'
    news='runtime-os-app-stage-news-1'
    images = {} if manifest.get('apiUnchanged') is True else {api: manifest['image']}
    if manifest.get('worker'): images[node] = manifest['worker']['image']
    if manifest.get('mcp'): images[mcp] = manifest['mcp']['image']
    if manifest.get('news'): images[news]=manifest['news']['image']
    order = ([mcp] if mcp in images else []) + ([api] if api in images else []) + ([node] if node in images else []) + ([proxy] if api in images else []) + ([news] if news in images else [])
    inspect = lambda name: json.loads(run('docker', 'inspect', name))[0]
    before = {name: inspect(name) for name in run('docker', 'ps', '--format', '{{.Names}}').splitlines()}
    assert run('docker', 'exec', api, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['baselineSha256']
    assert hashlib.sha256((root / 'app.jar').read_bytes()).hexdigest() == manifest['sha256']
    if api not in images:
        assert manifest['sha256']==manifest['baselineSha256'], 'An API-only byte change cannot skip API deployment'
    active=run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "select task_id from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')").splitlines()
    # A timed-out metadata-only acceptance fixture can retain unknown provider
    # usage. Preserve that reservation and evidence; never fabricate a stop proof.
    for task in active:
        assert task in manifest.get('expiredReadOnlyTaskIds',[]), 'Active Runtime task: '+task
        assert task.startswith('sql-coding-') and all(c.isalnum() or c=='-' for c in task)
        raw=run('docker','exec','-u','postgres','postgres','psql','-At','-d','runtime_os_acceptance_api','-c',
            "select record_json from runtime_evidence_entry where entry_kind='EXEC_CHECKPOINT' and entry_id='coding:workspace-contract:"+task+"'")
        envelope=json.loads(raw);assert envelope['schemaVersion']=='runtime_execution_checkpoint.v1'
        contract=json.loads(envelope['value'])
        assert contract['taskId']==task and contract['scope']['userId']==manifest['acceptanceOwnerId']
        assert 'runtime-coding:sql-metadata-tools:v1' in contract['metadataRefs']
        assert set(contract['authorization']['allowedTools'])=={'workspace_read','workspace_propose','workspace_metadata','workspace_validate'}
        assert contract['authorization']['networkPolicy']=='NONE' and contract['authorization']['allowedSideEffects']==['WORKSPACE_WRITE']
        assert contract['budget']['maxCodeExecutions']==0
        assert datetime.fromisoformat(contract['budget']['deadlineAt'].replace('Z','+00:00'))<datetime.now(timezone.utc)
    probe = "const f=require('fs');fetch('http://127.0.0.1:3100/readyz',{headers:{authorization:'Bearer '+f.readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim()}}).then(r=>r.json()).then(r=>console.log(r.activeSessions))"
    assert run('docker', 'exec', 'runtime-os-app-stage-coding-agent-1', 'node', '-e', probe) == '0', 'Active Coding sessions'
    assert not run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'), 'Active Python sandbox'
    for name in order:
        for mount in before[name]['Mounts']:
            if mount['Type'] == 'bind' and not Path(mount['Source']).exists():
                raise RuntimeError('Required bind source is missing: ' + mount['Source'])
    # Never layer an application on a previous business image: old JARs remain
    # in its ancestry even when COPY overwrites the visible file.
    base = 'runtime-os-api-runtime:17-sqlglot-20261024'
    assert 'app.jar' not in run('docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', base), 'Use a clean runtime base'
    run('docker', 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true', '--entrypoint', '/usr/bin/python3', base,
        '-c', "import sqlglot; assert sqlglot.__version__ == '30.23.0'")
    (root / 'Dockerfile').write_text('FROM ' + base + '\nWORKDIR /srv/app\nRUN mkdir -p /srv/app/config /srv/app/data /srv/app/logs\nCOPY app.jar /srv/app/app.jar\nENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai\nEXPOSE 8080\nENTRYPOINT ["java","-jar","/srv/app/app.jar"]\n')
    (root / '.dockerignore').write_text('*\n!app.jar\n!Dockerfile\n')
    if api in images:
        with (root / 'api-build.log').open('w') as log:
            subprocess.run(['docker', 'build', '--network', 'none', '--label',
                'org.opencontainers.image.revision=' + manifest['sourceCommit'], '-t', manifest['image'], str(root)],
                stdout=log, stderr=subprocess.STDOUT, check=True)
        assert int(run('docker', 'image', 'inspect', '--format', '{{.Size}}', manifest['image'])) <= 1200 * 1024 * 1024, 'Application image exceeds 1200 MiB'
        history = run('docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', manifest['image'])
        assert sum('COPY ' in line and 'app.jar' in line for line in history.splitlines()) == 1, 'Application must contain one business JAR copy layer'
    for service,key in [(mcp,'mcp'),(news,'news')]:
        if service not in images:continue
        entry=manifest[key]
        assert before[service]['Image']==entry['baselineImageId']
        assert run('docker','exec',service,'sha256sum','/srv/app/app.jar').split()[0]==entry['baselineSha256']
        assert hashlib.sha256((root/(key+'.jar')).read_bytes()).hexdigest()==entry['sha256']
        assert run('docker','image','inspect','-f','{{.Id}}',entry['image'])==entry['preparedImageId']
    if node in images:
        entry=manifest['worker'];build=root/'worker-build';build.mkdir(exist_ok=True)
        if entry.get('preparedImageId'):
            # Skill packages are built and tested offline by build-cline-skills-stage.py.
            assert before[node]['Image']==entry['baselineImageId']
            assert run('docker','image','inspect','-f','{{.Id}}',images[node])==entry['preparedImageId']
            proof=json.loads((root/'worker-build.json').read_text())
            assert proof['imageId']==entry['preparedImageId'] and proof['offlineBuildAndTests']
        else:
            (build/'patched').mkdir(exist_ok=True)
            for module,expected in entry['baselineModules'].items():
                assert module in ['runner.js','server.js']
                assert run('docker','exec',node,'sha256sum','/srv/coding-agent/dist/'+module).split()[0]==expected
                assert hashlib.sha256((root/'worker'/module).read_bytes()).hexdigest()==entry['modules'][module]
                shutil.copy2(root/'worker'/module,build/'patched'/module)
            baseline_tag='runtime-os-coding-agent:sql-metadata-baseline-'+before[node]['Image'].split(':')[-1][:16]
            run('docker','tag',before[node]['Image'],baseline_tag)
            (build/'Dockerfile').write_text('FROM '+baseline_tag+'\nCOPY patched/ /srv/coding-agent/dist/\n')
            run('docker','build','--network','none','-t',images[node],str(build))
    backup = root / 'backup'
    backup.mkdir(mode=0o700)
    (backup / 'containers.json').write_text(json.dumps(before))
    configs = {name: (stage / name).read_bytes() for name in ['compose.yaml', 'config/deployment.env','config/node-proxy.conf']}
    for name, data in configs.items():
        (backup / Path(name).name).write_bytes(data)
    sql = lambda query: run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c', query)
    channels = json.loads(sql("select coalesce(json_agg(json_build_object('id',session_id,'tenant',tenant_id,'channel',source_channel))::text,'[]') from chat_session"))
    (backup / 'channels.json').write_text(json.dumps(channels))
    fingerprint = sql("select md5(coalesce(jsonb_agg(to_jsonb(t)-'source_channel' order by session_id)::text,'[]')) from chat_session t")
    messages = sql('select count(*) from chat_message_index')
    with (backup / 'api.dump').open('wb') as stream:
        subprocess.run(['docker', 'exec', '-u', 'postgres', 'postgres', 'pg_dump', '-Fc', 'runtime_os_acceptance_api'], stdout=stream, check=True)
    if mcp in images:
        with (backup/'mcp.dump').open('wb') as stream: subprocess.run(['docker','exec','-u','postgres','postgres','pg_dump','-Fc','runtime_os_acceptance_mcp'],stdout=stream,check=True)
    applied_migration = apply_database_migration(root, manifest)
    stamp = str(int(time.time()))
    retired, created = {}, []

    def clone(name):
        old = before[name]
        config, host = copy.deepcopy(old['Config']), copy.deepcopy(old['HostConfig'])
        if name in images:
            config['Image'] = images[name]
            if manifest.get('removeInheritedRuntimeBaseLabels'):
                for key in ('com.chatchat.cleanup.protect', 'com.chatchat.runtime.clean'):
                    config.get('Labels', {}).pop(key, None)
        if name==api and not manifest.get('preserveRuntimeConfiguration'):
            config['Env']=[v for v in config['Env'] if not v.startswith('CHATCHAT_SEARCH_OPENSEARCH_KNOWLEDGE')]+['CHATCHAT_SEARCH_OPENSEARCH_KNOWLEDGEINDEXNAMES=runtime_os_stage_documents,chatchat_documents_dev']
        if name==api and not manifest.get('preserveRuntimeConfiguration'):
            config['Cmd']=[v for v in (config.get('Cmd') or []) if not v.startswith('--chatchat.search.open-search.knowledge-index-names=')]+['--chatchat.search.open-search.knowledge-index-names=runtime_os_stage_documents,chatchat_documents_dev']
        if name==node:
            config['Env']=[v for v in config['Env'] if not v.startswith('RUNTIME_CODING_BUILD=')]+['RUNTIME_CODING_BUILD='+manifest['sourceCommit']]
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

    def mcp_ready_now(client):
        if mcp not in images:return True
        try:
            current=inspect(mcp)['Image']
            if current==manifest['mcp']['preparedImageId'] or current==before[mcp]['Image'] and manifest['mcp'].get('baselineApplicationReadiness'):
                with client.open('http://127.0.0.1:18190/readyz',timeout=3) as result:
                    return result.status==200 and json.load(result).get('status')=='READY'
            # Rollback may restore an older image without the new infrastructure endpoint.
            if inspect(mcp)['Image']!=before[mcp]['Image']:return False
            return client.open('http://127.0.0.1:18190/api/v1/admin/auth/me',timeout=3).status in [200,401,403]
        except urllib.error.HTTPError as error:
            return inspect(mcp)['Image']==before[mcp]['Image'] and not manifest['mcp'].get('baselineApplicationReadiness') and error.code in [200,401,403]
        except (OSError,urllib.error.URLError,ValueError):return False

    def backup_financial_storage():
        if not (manifest.get('financialLookupSchemaOptimization') or manifest.get('backupFinancialStorage')):return
        database=Path('/srv/app/data/h2/financial-market.mv.db')
        candidates=[mount for mount in before[mcp]['Mounts'] if database==Path(mount['Destination']) or Path(mount['Destination']) in database.parents]
        assert candidates,'Financial writer mount must be identified before schema optimization'
        mount=max(candidates,key=lambda value:len(value['Destination']))
        owner=Path(mount['Source']).resolve()
        source=(owner/database.relative_to(mount['Destination'])).resolve()
        assert source.is_relative_to(owner) and source.is_file(),'Financial writer database backup source unavailable'
        assert shutil.disk_usage(root).free>source.stat().st_size+1024**3,'Insufficient space for financial storage backup'
        destination=root/'backup'/'financial-h2';destination.mkdir(mode=0o700)
        target=destination/database.name;shutil.copy2(source,target);target.chmod(0o600)
        with target.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
        (root/'financial-storage-backup.json').write_text(json.dumps({'database':database.name,'bytes':target.stat().st_size,'sha256':digest,'copiedAfterWriterStopped':True},indent=2))

    def wait_mcp_before_producer():
        client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline=time.monotonic()+(600 if manifest.get('financialLookupSchemaOptimization') else 240)
        while time.monotonic()<deadline:
            if mcp_ready_now(client):return
            time.sleep(2)
        raise RuntimeError('MCP application readiness timed out before news producer startup')

    def backup_news_storage():
        if not manifest.get('backupNewsStorage'): return
        assert news in images, 'News backup requires stopping its writer in this deployment'
        # The H2 writer is stopped before copying; additive Internet tables must
        # never make an existing collection database impossible to restore.
        source = Path('/opt/chatchat-runtime-news-1.0.0-SNAPSHOT/data').resolve()
        assert any(Path(m['Source']).resolve() == source and m['Destination'] == '/srv/app/data'
                   for m in before[news]['Mounts']), 'News database mount changed'
        destination = backup / 'news-h2'
        destination.mkdir(mode=0o700)
        files = list(source.glob('*.mv.db'))
        assert files, 'Existing News database backup is required'
        assert shutil.disk_usage(backup).free > sum(p.stat().st_size for p in files) + 1024**3
        for path in files:
            assert path.is_file() and not path.is_symlink()
            shutil.copy2(path, destination / path.name)
        shutil.copytree(stage / 'config/news', backup / 'news-config')

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
            mcp_ready=mcp_ready_now(client)
            news_ready=news not in images
            if news in images:
                try:
                    address=next(value['IPAddress'] for value in inspect(news)['NetworkSettings']['Networks'].values() if value.get('IPAddress'))
                    news_ready=client.open('http://'+address+':8091/internal/v1/news/health',timeout=3).status in [200,401,403]
                except urllib.error.HTTPError as error:news_ready=error.code in [200,401,403]
                except (OSError,urllib.error.URLError,StopIteration):pass
            if status in [200, 401, 403] and mcp_ready and news_ready and (node not in images or inspect(node)['State'].get('Health',{}).get('Status')=='healthy'):
                return
            time.sleep(2)
        raise RuntimeError('Application readiness timed out')

    def networking():
        subprocess.run(['bash', str(root / 'restrict-coding-worker.sh')], check=True)
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')

    try:
        run('docker', 'stop', '-t', '60', *reversed(order))
        backup_financial_storage()
        backup_news_storage()
        if manifest.get('sqlMetadataCallbacks') or manifest.get('knowledgeCallbacks'):
            config=configs['config/node-proxy.conf'].decode()
            if manifest.get('knowledgeCallbacks') and '|knowledge|' not in config: config=config.replace('|submit-report|','|submit-report|knowledge|',1)
            assert '|workspace-propose|' in config
            if '|workspace-metadata|' not in config: config=config.replace('|workspace-propose|','|workspace-propose|workspace-metadata|workspace-validate|',1)
            (stage/'config/node-proxy.conf').write_text(config)
        for name in order:
            if name==news:wait_mcp_before_producer()
            retired[name] = name + '-before-studio-' + stamp
            run('docker', 'rename', name, retired[name])
            clone(name)
        ready()
        networking()
        catalog_readiness = verify_catalog_readiness(root, manifest)
        config = json.loads(configs['compose.yaml'])
        if api in images: config['services']['api']['image'] = manifest['image']
        if not manifest.get('preserveRuntimeConfiguration'):
            config['services']['api'].setdefault('environment',{}).pop('CHATCHAT_SEARCH_OPENSEARCH_KNOWLEDGE_INDEX_NAMES',None)
            config['services']['api']['environment']['CHATCHAT_SEARCH_OPENSEARCH_KNOWLEDGEINDEXNAMES']='runtime_os_stage_documents,chatchat_documents_dev'
        if mcp in images: config['services']['mcp']['image']=images[mcp]
        if news in images:config['services']['news']['image']=images[news]
        if node in images: config['services']['coding-agent']['image']=images[node]
        (stage / 'compose.yaml').write_text(json.dumps(config, indent=2))
        if not manifest.get('preserveRuntimeConfiguration'):
            config['services']['api']['command']=[v for v in (before[api]['Config'].get('Cmd') or []) if not v.startswith('--chatchat.search.open-search.knowledge-index-names=')]+['--chatchat.search.open-search.knowledge-index-names=runtime_os_stage_documents,chatchat_documents_dev']
        (stage / 'compose.yaml').write_text(json.dumps(config, indent=2))
        replacements={'API_IMAGE':manifest['image']} if api in images else {}
        if mcp in images: replacements['MCP_IMAGE']=images[mcp]
        if news in images:replacements['NEWS_IMAGE']=images[news]
        lines = [line for line in configs['config/deployment.env'].decode().splitlines() if line.split('=',1)[0] not in replacements]
        (stage / 'config/deployment.env').write_text('\n'.join(lines + [k+'='+v for k,v in replacements.items()]) + '\n')
        assert run('docker', 'exec', api, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['sha256']
        assert all(inspect(name)['Id'] == old['Id'] for name, old in before.items() if name not in order)
        if mcp in images: assert run('docker','exec',mcp,'sha256sum','/srv/app/app.jar').split()[0]==manifest['mcp']['sha256']
        if news in images:assert run('docker','exec',news,'sha256sum','/srv/app/app.jar').split()[0]==manifest['news']['sha256']
        if node in images:
            for module,expected in manifest['worker']['modules'].items(): assert run('docker','exec',node,'sha256sum','/srv/coding-agent/dist/'+module).split()[0]==expected
        assert fingerprint == sql("select md5(coalesce(jsonb_agg(to_jsonb(t)-'source_channel' order by session_id)::text,'[]')) from chat_session t"), 'Conversation content changed during migration'
        assert messages == sql('select count(*) from chat_message_index'), 'Message index count changed during migration'
        report = {'status': 'DEPLOYED', 'sourceCommit': manifest['sourceCommit'], 'image': manifest['image'] if api in images else before[api]['Config']['Image'], 'sha256': manifest['sha256'], 'backup': str(backup), 'retired': retired,
            'databaseMigrationSha256': applied_migration, 'catalogReadiness': catalog_readiness,
            'preservedExpiredReadOnlyTaskIds':active,
            'unrelatedContainersUnchanged': True, 'conversationContentUnchanged': True, 'messageIndexCountUnchanged': True,
            'images':images,'channelCounts': sql('select source_channel,count(*) from chat_session group by source_channel')}
        (root / 'deployment.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report), flush=True)
    except Exception:
        for name, data in configs.items():
            (stage / name).write_bytes(data)
        for name in reversed(created):
            run('docker', 'stop', '-t', '30', name)
            run('docker', 'rename', name, name + '-failed-studio-' + stamp)
        for name in order:
            if name in retired:
                run('docker', 'rename', retired[name], name)
            if name==news:wait_mcp_before_producer()
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
