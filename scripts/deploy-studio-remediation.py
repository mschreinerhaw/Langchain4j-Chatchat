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
    assert (root / 'restrict-coding-worker.sh').is_file(), 'Missing network restriction deployment dependency'
    manifest = json.loads((root / 'manifest.json').read_text())
    stage = Path('/opt/runtime-os-docker-stage')
    api, proxy = 'runtime-os-app-stage-api-1', 'runtime-os-app-stage-node-proxy-1'
    node, mcp = 'runtime-os-app-stage-coding-agent-1', 'runtime-os-app-stage-mcp-1'
    images = {api: manifest['image']}
    if manifest.get('worker'): images[node] = manifest['worker']['image']
    if manifest.get('mcp'): images[mcp] = manifest['mcp']['image']
    order = ([mcp] if mcp in images else []) + [api] + ([node] if node in images else []) + [proxy]
    inspect = lambda name: json.loads(run('docker', 'inspect', name))[0]
    before = {name: inspect(name) for name in run('docker', 'ps', '--format', '{{.Names}}').splitlines()}
    assert run('docker', 'exec', api, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['baselineSha256']
    assert hashlib.sha256((root / 'app.jar').read_bytes()).hexdigest() == manifest['sha256']
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
    base = 'runtime-os-api-runtime:17-sqlglot-20261024'
    assert 'app.jar' not in run('docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', base)
    (root / 'Dockerfile').write_text('FROM ' + base + '\nWORKDIR /srv/app\nCOPY app.jar /srv/app/app.jar\nENTRYPOINT ["java","-jar","/srv/app/app.jar"]\n')
    (root / '.dockerignore').write_text('*\n!app.jar\n!Dockerfile\n')
    run('docker', 'build', '--network', 'none', '-t', manifest['image'], str(root))
    if mcp in images:
        entry=manifest['mcp']
        assert run('docker','exec',mcp,'sha256sum','/srv/app/app.jar').split()[0]==entry['baselineSha256']
        assert hashlib.sha256((root/'mcp.jar').read_bytes()).hexdigest()==entry['sha256']
        build=root/'mcp-build';build.mkdir(exist_ok=True)
        shutil.copy2(root/'mcp.jar',build/'app.jar')
        run('docker','cp',mcp+':/usr/local/bin/docker',str(build/'docker'))
        (build/'Dockerfile').write_text('FROM '+base+'\nCOPY docker /usr/local/bin/docker\nRUN chmod 0755 /usr/local/bin/docker && docker --version\nWORKDIR /srv/app\nCOPY app.jar /srv/app/app.jar\nENTRYPOINT ["java","-jar","/srv/app/app.jar"]\n')
        run('docker','build','--network','none','-t',images[mcp],str(build))
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
    stamp = str(int(time.time()))
    retired, created = {}, []

    def clone(name):
        old = before[name]
        config, host = copy.deepcopy(old['Config']), copy.deepcopy(old['HostConfig'])
        if name in images:
            config['Image'] = images[name]
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
            mcp_ready=mcp not in images
            if mcp in images:
                try:
                    mcp_ready=client.open('http://127.0.0.1:18190/api/v1/admin/auth/me',timeout=3).status in [200,401,403]
                except urllib.error.HTTPError as error: mcp_ready=error.code in [200,401,403]
                except (OSError,urllib.error.URLError): pass
            if status in [200, 401, 403] and mcp_ready and (node not in images or inspect(node)['State'].get('Health',{}).get('Status')=='healthy'):
                return
            time.sleep(2)
        raise RuntimeError('Application readiness timed out')

    def networking():
        subprocess.run(['bash', str(root / 'restrict-coding-worker.sh')], check=True)
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')

    try:
        run('docker', 'stop', '-t', '60', *reversed(order))
        if manifest.get('sqlMetadataCallbacks'):
            config=configs['config/node-proxy.conf'].decode()
            assert '|workspace-propose|' in config
            if '|workspace-metadata|' not in config: config=config.replace('|workspace-propose|','|workspace-propose|workspace-metadata|workspace-validate|',1)
            (stage/'config/node-proxy.conf').write_text(config)
        for name in order:
            retired[name] = name + '-before-studio-' + stamp
            run('docker', 'rename', name, retired[name])
            clone(name)
        ready()
        networking()
        config = json.loads(configs['compose.yaml'])
        config['services']['api']['image'] = manifest['image']
        if mcp in images: config['services']['mcp']['image']=images[mcp]
        if node in images: config['services']['coding-agent']['image']=images[node]
        (stage / 'compose.yaml').write_text(json.dumps(config, indent=2))
        replacements={'API_IMAGE':manifest['image']}
        if mcp in images: replacements['MCP_IMAGE']=images[mcp]
        lines = [line for line in configs['config/deployment.env'].decode().splitlines() if line.split('=',1)[0] not in replacements]
        (stage / 'config/deployment.env').write_text('\n'.join(lines + [k+'='+v for k,v in replacements.items()]) + '\n')
        assert run('docker', 'exec', api, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['sha256']
        assert all(inspect(name)['Id'] == old['Id'] for name, old in before.items() if name not in order)
        if mcp in images: assert run('docker','exec',mcp,'sha256sum','/srv/app/app.jar').split()[0]==manifest['mcp']['sha256']
        if node in images:
            for module,expected in manifest['worker']['modules'].items(): assert run('docker','exec',node,'sha256sum','/srv/coding-agent/dist/'+module).split()[0]==expected
        assert fingerprint == sql("select md5(coalesce(jsonb_agg(to_jsonb(t)-'source_channel' order by session_id)::text,'[]')) from chat_session t"), 'Conversation content changed during migration'
        assert messages == sql('select count(*) from chat_message_index'), 'Message index count changed during migration'
        report = {'status': 'DEPLOYED', 'image': manifest['image'], 'sha256': manifest['sha256'], 'backup': str(backup), 'retired': retired,
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
