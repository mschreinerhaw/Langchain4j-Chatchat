"""Deploy full template-capability application JARs with exact baseline checks and reversible image changes."""
import argparse
import importlib.util
import json
import os
import re
from pathlib import Path
import shutil
import zipfile
from application_image_build import build_application_image


def check_python_runtimes(stage, database):
    """Published environments are deployment dependencies even when no sandbox is active."""
    if not stage.sql(database, "select to_regclass('public.mcp_python_environment')"):
        return []
    images = stage.sql(database, "select distinct docker_image from mcp_python_environment where status='PUBLISHED' order by docker_image")
    dependencies = []
    for image in images.splitlines():
        try:
            identity = stage.run('docker', 'image', 'inspect', '--format', '{{.Id}}', image)
        except RuntimeError as failure:
            raise RuntimeError('Published Python runtime is missing on the MCP host: ' + image) from failure
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', identity):
            raise RuntimeError('Invalid published Python runtime identity: ' + image)
        dependencies.append({'image': image, 'imageId': identity})
    return dependencies


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--stage-root', type=Path, default=Path('/opt/runtime-os-docker-stage'))
    args = parser.parse_args()
    os.umask(0o077)
    release, root = args.release_dir.resolve(), args.stage_root.resolve()
    spec = importlib.util.spec_from_file_location('stage', Path(__file__).with_name('deploy-unified-management.py'))
    stage = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stage)
    stage.root = root
    manifest = json.loads((release / 'release-manifest.json').read_text())
    version = manifest['version']
    if not version.replace('.', '').replace('-', '').isalnum():
        raise ValueError('Invalid release version')
    stage.evidence = root / 'evidence' / ('template-capabilities-' + version)
    stage.evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    config = json.loads((root / 'compose.yaml').read_text())
    # Recreating a running container requires every bind source to still exist.
    # Check before stopping anything, including mounts retained from older releases.
    for name in ['api', 'mcp']:
        for volume in config['services'][name].get('volumes', []):
            if isinstance(volume, dict) and volume.get('type') == 'bind':
                source = Path(volume['source'])
                if not source.is_absolute():
                    source = root / source
                if not source.exists():
                    raise RuntimeError('Missing bind source for ' + name + ': ' + str(source))
    databases = {name: config['services'][name]['environment']['CHATCHAT_' + name.upper() + '_POSTGRESQL_URL'].rsplit('/', 1)[1].split('?', 1)[0] for name in ['api', 'mcp']}
    python_runtimes = check_python_runtimes(stage, databases['mcp'])

    def running(name):
        return stage.run('docker', 'exec', 'runtime-os-app-stage-' + name + '-1', 'sha256sum', '/srv/app/app.jar').split()[0]

    changed = []
    for name in ['api', 'mcp']:
        assert running(name) == manifest['baseline'][name], 'Running baseline changed: ' + name
        assert stage.digest(release / (name + '.jar')) == manifest['artifacts'][name]['sha256'], 'Artifact mismatch'
        if manifest['baseline'][name] != manifest['artifacts'][name]['sha256']:
            changed.append(name)
            build_application_image(name, release / (name + '.jar'), 'runtime-os-' + name + ':' + version, stage.evidence / (name + '-image'))
    if not changed:
        raise ValueError('No changed artifacts to deploy')
    assert stage.sql(databases['api'], "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") == '0', 'Active tasks prevent replacement'
    assert not stage.run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'), 'Active Python operations'
    before = stage.policy_fingerprints(databases['api'], databases['mcp'])
    existing_grants = stage.sql(databases['api'], "select coalesce(jsonb_agg(to_jsonb(t) order by id)::text,'[]') from resource_grant t")
    destination = stage.backup(config, databases)
    # Preserve the running server's environment-specific root configuration outside the application image.
    # The versioned production profile remains in the new JAR; secret key mounts remain unchanged.
    volumes = config['services']['mcp'].setdefault('volumes', [])
    if not any(isinstance(volume, dict) and volume.get('target') == '/srv/app/config/application.yml' for volume in volumes):
        with zipfile.ZipFile(destination / 'mcp-running.jar') as baseline:
            runtime_config = root / 'config/mcp-application.yml'
            content = baseline.read('BOOT-INF/classes/application.yml')
            if runtime_config.exists() and runtime_config.read_bytes() != content:
                raise RuntimeError('Persistent MCP root configuration differs from running baseline')
            runtime_config.write_bytes(content)
            runtime_config.chmod(0o600)
        volumes.append({'type': 'bind', 'source': str(runtime_config), 'target': '/srv/app/config/application.yml', 'read_only': True})
    report = {'version': version, 'sourceCommit': manifest['sourceCommit'], 'backup': str(destination), 'policyBefore': before,
              'pythonRuntimes': python_runtimes}
    print(json.dumps({'stage': 'BACKED_UP', 'backup': str(destination)}), flush=True)
    try:
        stage.compose('stop', *changed)
        for name in changed:
            config['services'][name]['image'] = 'runtime-os-' + name + ':' + version
        (root / 'compose.yaml').write_text(json.dumps(config, indent=2))
        env = root / 'config/deployment.env'
        env.write_text('\n'.join(line for line in env.read_text().splitlines() if not line.startswith(('API_IMAGE=', 'MCP_IMAGE=')))
                       + '\nAPI_IMAGE=' + config['services']['api']['image'] + '\nMCP_IMAGE=' + config['services']['mcp']['image'] + '\n')
        stage.compose('up', '-d', '--no-deps', '--force-recreate', *changed)
        stage.ready(18180)
        stage.ready(18190)
        after = stage.policy_fingerprints(databases['api'], databases['mcp'])
        for key in before:
            if not key.endswith('.resource_grant'):
                assert before[key] == after[key], 'Existing policy changed: ' + key
        original = {row['id']: row for row in json.loads(existing_grants)}
        current = json.loads(stage.sql(databases['api'], "select coalesce(jsonb_agg(to_jsonb(t) order by id)::text,'[]') from resource_grant t"))
        assert all(next((value for value in current if value['id'] == key), None) == row for key, row in original.items()), 'Existing grants changed'
        additions = [row for row in current if row['id'] not in original]
        admin_roles = set(stage.sql(databases['api'], "select id from sys_role where role_code='SUPER_ADMIN' and status='enabled'").splitlines())
        assert all(row['resource_type'] == 'TEMPLATE' and row['resource_id'] == '*' and row['principal_type'] == 'ROLE'
                   and row['principal_id'] in admin_roles and row['effect'] == 'ALLOW' for row in additions), 'Unexpected new grants'
        for name in ['api', 'mcp']:
            assert running(name) == manifest['artifacts'][name]['sha256'], 'Running artifact differs'
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        worker_rule = root / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'
        if worker_rule.exists():
            stage.run('bash', str(worker_rule))
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        report.update(status='DEPLOYED', artifacts=manifest['artifacts'], policyAfter=after, newTemplateDefaults=len(additions), existingGrantsUnchanged=True)
    except Exception:
        # Old applications do not understand the new kind. Retain immutable history, disable its live routes.
        stage.sql(databases['mcp'], "update mcp_tool_release set state='OFFLINE' where kind='TEMPLATE_CAPABILITY' and state='RUNNING'")
        for filename, target in [('compose.yaml', root / 'compose.yaml'), ('deployment.env', root / 'config/deployment.env')]:
            shutil.copy2(destination / filename, target)
        stage.compose('up', '-d', '--no-deps', '--force-recreate', *changed)
        stage.ready(18180)
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        report['status'] = 'APPLICATIONS_ROLLED_BACK_HISTORY_RETAINED'
        (stage.evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
        raise
    (stage.evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
