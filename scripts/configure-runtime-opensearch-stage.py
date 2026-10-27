"""Enable the existing MCP OpenSearch engine using the already configured API connection.
Retain the previous image/configuration and reject replacement while work is active.
"""
import argparse
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import ssl


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    root = args.release_dir.resolve()
    root.chmod(0o700)
    spec = importlib.util.spec_from_file_location('deployment', root/'deploy-runtime-template-supply.py')
    deployment = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(deployment)
    run, docker = deployment.run, deployment.docker
    name = 'runtime-os-app-stage-mcp-1'
    original = json.loads(run('docker', 'inspect', name))[0]
    api = json.loads(run('docker', 'inspect', 'runtime-os-app-stage-api-1'))[0]
    environment = dict(item.split('=', 1) for item in api['Config']['Env'])
    required = ['CHATCHAT_SEARCH_OPENSEARCH_' + field for field in ['URL', 'USERNAME', 'PASSWORD']]
    assert all(environment.get(key) for key in required), 'Existing API search connection is required'
    connection = dict(url=environment[required[0]], username=environment[required[1]], password=environment[required[2]])
    insecure = environment.get('CHATCHAT_SEARCH_OPENSEARCH_INSECURE_SSL', 'false').lower() == 'true'
    handler = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    handler.add_password(None, connection['url'], connection['username'], connection['password'])
    # The existing URL may use Docker's host.docker.internal mapping. The host-side
    # preflight uses the same cluster's published port; containers retain the URL.
    cluster_container = json.loads(run('docker', 'inspect', 'opensearch-node'))[0]
    cluster_ports = cluster_container['NetworkSettings']['Ports']['9200/tcp']
    assert cluster_ports, 'Existing OpenSearch has no published preflight port'
    parsed = urllib.parse.urlsplit(connection['url'])
    probe_url = urllib.parse.urlunsplit((parsed.scheme, '127.0.0.1:' + cluster_ports[0]['HostPort'], '/', '', ''))
    handler.add_password(None, probe_url, connection['username'], connection['password'])
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPBasicAuthHandler(handler),
        urllib.request.HTTPSHandler(context=ssl._create_unverified_context() if insecure else ssl.create_default_context()))
    with client.open(probe_url, timeout=15) as response:
        cluster = json.load(response)
    assert cluster.get('version', {}).get('number'), 'Real OpenSearch preflight failed'
    assert run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") == '0'
    assert not run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}')
    config_file = Path('/opt/runtime-os-docker-stage/config/mcp/application.yml')
    config_before = config_file.read_bytes()
    (root/'mcp-config-before.private.yml').write_bytes(config_before)
    (root/'container-before.private.json').write_text(json.dumps(original))
    compose_file = Path('/opt/runtime-os-docker-stage/compose.yaml')
    compose_before = compose_file.read_bytes()
    (root/'compose-before.private.json').write_bytes(compose_before)
    before = subprocess.check_output(['docker', 'exec', name, 'cat', '/srv/app/app.jar'])
    baseline = hashlib.sha256(before).hexdigest()
    manifest = json.loads((root/'mcp-manifest.json').read_text())
    assert baseline == manifest['baselineSha256']
    replacement = (root/'OpenSearchMcpSearchService.class').read_bytes()
    assert hashlib.sha256(replacement).hexdigest() == manifest['classSha256']
    entry = 'BOOT-INF/classes/com/chatchat/mcpserver/search/engine/OpenSearchMcpSearchService.class'
    candidate = deployment.replace_entries(before, {entry: replacement})
    (root/'before.jar').write_bytes(before)
    (root/'app.jar').write_bytes(candidate)
    candidate_hash = hashlib.sha256(candidate).hexdigest()
    baseline_tag = 'runtime-os-mcp:before-search-' + baseline[:16]
    image = 'runtime-os-mcp:search-' + candidate_hash[:16]
    run('docker', 'tag', original['Image'], baseline_tag)
    (root/'.dockerignore').write_text('*\n!Dockerfile\n!app.jar\n')
    (root/'Dockerfile').write_text('FROM ' + baseline_tag + '\nCOPY app.jar /srv/app/app.jar\n')
    subprocess.run(['docker', 'build', '-t', image, str(root)], check=True)
    search = {'enabled': True, 'engine': 'opensearch', 'open-search': {
        **connection, 'enabled': True, 'insecure-ssl': insecure, 'debug-search': False}}
    # JSON is valid YAML; append one explicit override document without rewriting other settings.
    after = config_before.rstrip() + b'\n---\n' + json.dumps({'chatchat': {'mcp': {'lucene': search}}}).encode() + b'\n'
    config = copy.deepcopy(original['Config'])
    config['Image'] = image
    endpoints = {network: {'Aliases': details.get('Aliases'), 'IPAMConfig': details.get('IPAMConfig')}
                 for network, details in original['NetworkSettings']['Networks'].items()}
    backup = name + '-before-search-' + baseline[:12]
    renamed = created = False
    disconnected = []
    report = {'baselineSha256': baseline, 'candidateSha256': candidate_hash, 'image': image,
        'backupContainer': backup, 'engineBefore': 'lucene', 'engineAfter': 'opensearch',
        'openSearchVersion': cluster['version']['number'], 'credentialsExported': False}
    try:
        run('docker', 'stop', '--time', '45', name)
        run('docker', 'rename', name, backup)
        renamed = True
        for network in endpoints:
            run('docker', 'network', 'disconnect', network, backup)
            disconnected.append(network)
        config_file.write_bytes(after)
        docker('POST', '/containers/create?name=' + urllib.parse.quote(name),
            {**config, 'HostConfig': original['HostConfig'], 'NetworkingConfig': {'EndpointsConfig': endpoints}})
        created = True
        run('docker', 'start', name)
        ports = next((bindings for bindings in original['NetworkSettings']['Ports'].values() if bindings), None)
        assert ports, 'MCP has no published HTTP port'
        port = ports[0]['HostPort']
        probe = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(90):
            try:
                probe.open('http://127.0.0.1:' + port + '/actuator/health', timeout=2).close()
                break
            except urllib.error.HTTPError as error:
                if error.code in {401, 403, 404}: break
            except OSError: pass
            time.sleep(2)
        else: raise RuntimeError('MCP readiness failed')
        current = json.loads(run('docker', 'inspect', name))[0]
        for key in ['User', 'Env', 'Entrypoint', 'Cmd', 'WorkingDir']:
            assert current['Config'].get(key) == original['Config'].get(key), key
        for key in ['ReadonlyRootfs', 'CapAdd', 'CapDrop', 'SecurityOpt', 'Memory', 'NanoCpus', 'Binds', 'NetworkMode', 'RestartPolicy', 'Tmpfs']:
            assert current['HostConfig'].get(key) == original['HostConfig'].get(key), key
        assert run('docker', 'exec', name, 'sha256sum', '/srv/app/app.jar').split()[0] == candidate_hash
        compose = json.loads(compose_before)
        compose['services']['mcp']['image'] = image
        compose_file.write_text(json.dumps(compose, indent=2))
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        report['status'] = 'DEPLOYED'
        report['runtimePolicyPreserved'] = True
    except Exception:
        config_file.write_bytes(config_before)
        compose_file.write_bytes(compose_before)
        if created: run('docker', 'rm', '-f', name)
        if renamed: run('docker', 'rename', backup, name)
        for network in disconnected:
            docker('POST', '/networks/' + urllib.parse.quote(network) + '/connect',
                {'Container': name, 'EndpointConfig': endpoints[network]})
        run('docker', 'start', name)
        report['status'] = 'ROLLED_BACK'
        raise
    finally:
        (root/'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
