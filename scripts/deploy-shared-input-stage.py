"""Run only on the authorized isolated test host. Requires sibling deploy-cline-monitor-stage.py.
deploy-shared-input-stage.py ARCHIVE SHA256 TAG [--format PARQUET|CSV]
deploy-shared-input-stage.py --rollback /exact/evidence/backup.json
Rollback disables new-format creation and retains compatible readers so new task history remains readable.
Backups contain private effective Compose configuration and must remain root-only.
"""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import re
import shutil
import time
import urllib.request
import urllib.error

ROOT = pathlib.Path('/opt/runtime-os-docker-stage')
EVIDENCE = ROOT / 'evidence/shared-input-20261019'
spec = importlib.util.spec_from_file_location('stage_base', pathlib.Path(__file__).with_name('deploy-cline-monitor-stage.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def environment(service):
    values = service.get('environment', {})
    return dict(line.split('=', 1) for line in values) if isinstance(values, list) else dict(values)


def await_mcp():
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        listening=False
        try:
            with client.open('http://127.0.0.1:18190/api/v1/python/input-capabilities',timeout=3) as response:
                listening=response.status==200
        except urllib.error.HTTPError as response:
            listening=response.code in (401,403)
        except (OSError,urllib.error.URLError):pass
        if listening:
            try:
                base.run('docker','exec',f'{base.PROJECT}-api-1','bash','-c','exec 3<>/dev/tcp/mcp/9091')
                return
            except Exception:pass
        time.sleep(2)
    raise RuntimeError('MCP HTTP/gRPC did not become ready; schema validation or startup failed')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', nargs='?')
    parser.add_argument('sha256', nargs='?')
    parser.add_argument('tag', nargs='?')
    parser.add_argument('--format', choices=['PARQUET', 'CSV'], default='PARQUET')
    parser.add_argument('--rollback')
    parser.add_argument('--migration-sha256',help='Required reviewed PostgreSQL no-start migration hash when deploying')
    args = parser.parse_args()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    EVIDENCE.chmod(0o700)
    if base.run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
        "SELECT count(*) FROM agent_task_latest WHERE canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED') OR canonical_state IS NULL").strip() != '0':
        raise RuntimeError('Active isolated tasks prevent replacement')
    if base.run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}').strip():
        raise RuntimeError('Running Python containers prevent control-plane replacement')
    before = base.inspect()
    for name in ('api', 'mcp', 'coding-agent'):
        if before[f'{base.PROJECT}-{name}-1']['Config']['Labels'].get('com.docker.compose.project') != base.PROJECT:
            raise RuntimeError('Refusing a service outside the isolated test project')
    stamp = str(int(time.time()))
    compose = ROOT / 'compose.yaml'
    backup = {'compose': str(EVIDENCE / ('compose-before-' + stamp + '.json')),
              'environment': str(EVIDENCE / ('environment-before-' + stamp + '.env'))}
    for source, target in ((compose, pathlib.Path(backup['compose'])), (base.ENV, pathlib.Path(backup['environment']))):
        shutil.copy2(source, target)
        target.chmod(0o600)
    backup_file = EVIDENCE / ('backup-' + stamp + '.json')
    backup_file.write_text(json.dumps(backup))
    backup_file.chmod(0o600)
    if args.rollback:
        path = pathlib.Path(args.rollback).resolve()
        if not path.is_relative_to(EVIDENCE.resolve()) or not re.fullmatch(r'backup-[0-9]+\.json', path.name):
            raise RuntimeError('Rollback requires an exact private test backup')
        restore = json.loads(path.read_text())
        for key in ('compose', 'environment'):
            if not pathlib.Path(restore[key]).resolve().is_relative_to(EVIDENCE.resolve()):
                raise RuntimeError('Rollback path escapes test evidence')
        restored=json.loads(pathlib.Path(restore['compose']).read_text())
        # Older binaries reject inputFiles in already-persisted task contracts. Never downgrade
        # readers after new-format tasks exist; restore configuration with current image bytes.
        for name in ('api','mcp','coding-agent'):
            restored['services'][name]['image']=before[f'{base.PROJECT}-{name}-1']['Config']['Image']
        restored['services']['api']['environment']=environment(restored['services']['api'])
        restored['services']['api']['environment']['CHATCHAT_CODING_INPUT_FILES_ENABLED']='false'
        compose.write_text(json.dumps(restored,indent=2)+'\n')
        shutil.copy2(restore['environment'], base.ENV)
    else:
        if not args.archive or not args.sha256 or not args.tag or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', args.tag):
            raise RuntimeError('Archive, checksum and explicit image tag are required')
        if not args.migration_sha256 or not re.fullmatch('[0-9a-f]{64}',args.migration_sha256):
            raise RuntimeError('Reviewed migration hash and sibling migration files are required before deployment')
        archive = pathlib.Path(args.archive).resolve()
        if not archive.is_relative_to((ROOT / 'images').resolve()):
            raise RuntimeError('Archive must be inside the isolated release image directory')
        with archive.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != args.sha256:
                raise RuntimeError('Archive checksum mismatch')
        base.run('docker', 'load', '-i', str(archive))
        base.run('python3',str(pathlib.Path(__file__).with_name('migrate-shared-input-stage.py')),
                 '--sha256',args.migration_sha256)
        config = json.loads(compose.read_text())
        shared = ROOT / 'mcp/data/python-user-data'
        if not shared.is_dir() or shared.resolve() != shared:
            raise RuntimeError('Expected isolated Python shared directory is unavailable')
        mcp = config['services']['mcp']
        mcp_env = environment(mcp)
        if mcp_env.get('CHATCHAT_MCP_PYTHON_DATA_ROOT') != str(shared):
            raise RuntimeError('MCP data root does not match the isolated shared volume')
        api = config['services']['api']
        api_env = environment(api)
        api_env.update(CHATCHAT_CODING_INPUT_FILES_ENABLED='true', CHATCHAT_CODING_INPUT_FILES_FORMAT=args.format,
                       CHATCHAT_CODING_INPUT_FILES_SHARED_ROOT='/srv/shared-coding-input')
        api['environment'] = api_env
        mounts = [m for m in api['volumes'] if m.get('target') != '/srv/shared-coding-input']
        api['volumes'] = mounts + [{'type': 'bind', 'source': str(shared), 'target': '/srv/shared-coding-input',
                                   'bind': {'create_host_path': False}}]
        mcp_env['CHATCHAT_MCP_PYTHON_SHARED_INPUTS_ENABLED'] = 'true'
        mcp['environment'] = mcp_env
        mcp['image'] = '${MCP_IMAGE}'
        compose.write_text(json.dumps(config, indent=2) + '\n')
        values = {'API_IMAGE': 'runtime-os-api:' + args.tag, 'NODE_IMAGE': 'runtime-os-coding-agent:' + args.tag,
                  'MCP_IMAGE': 'runtime-os-mcp:' + args.tag}
        lines = [line for line in base.ENV.read_text().splitlines() if line.split('=', 1)[0] not in values]
        base.ENV.write_text('\n'.join(lines + [key + '=' + value for key, value in values.items()]) + '\n')
    compose.chmod(0o600)
    base.ENV.chmod(0o600)
    try:
        base.compose('config', '-q')
        base.compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'mcp', 'api', 'coding-agent')
        docker_version=json.loads(base.run('docker','exec',f'{base.PROJECT}-mcp-1','docker','version','--format','{{json .Server.Version}}'))
        if not isinstance(docker_version,str) or not docker_version:
            raise RuntimeError('MCP Docker control-plane preflight failed')
        await_mcp()
        base.compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'node-proxy', 'gateway')
    except Exception:
        shutil.copy2(backup['compose'], compose)
        shutil.copy2(backup['environment'], base.ENV)
        base.compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'mcp', 'api', 'coding-agent', 'node-proxy', 'gateway')
        raise
    after = base.inspect()
    changed = {f'{base.PROJECT}-{name}-1' for name in ('api', 'mcp', 'coding-agent', 'node-proxy', 'gateway')}
    for name, value in before.items():
        if name not in changed and base.fingerprint(value) != base.fingerprint(after[name]):
            raise RuntimeError('Unrelated container changed: ' + name)
    worker = after[f'{base.PROJECT}-coding-agent-1']
    if any(m['Source'] == str(ROOT / 'mcp/data/python-user-data') or 'docker.sock' in m['Source'] for m in worker['Mounts']):
        raise RuntimeError('Node worker unexpectedly received a shared root or Docker socket')
    effective=environment(json.loads(compose.read_text())['services']['api'])
    active_format=effective.get('CHATCHAT_CODING_INPUT_FILES_FORMAT','PARQUET') if effective.get('CHATCHAT_CODING_INPUT_FILES_ENABLED')=='true' else 'JSON_LINES'
    report = {'status': 'DEPLOYED_PENDING_ACCEPTANCE', 'tag': args.tag, 'format': active_format,
              'mcpDockerServerVersion':docker_version,
              'mcpHttpGrpcReady':True,'migrationSha256':args.migration_sha256,
              'action':'DISABLE_NEW_FORMAT_KEEP_READERS' if args.rollback else 'DEPLOY',
              'backup': str(backup_file), 'productionSwitched': False, 'unrelatedContainersUnchanged': True,
              'images': {name: after[f'{base.PROJECT}-{name}-1']['Image'] for name in ('api', 'mcp', 'coding-agent')}}
    (EVIDENCE / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
