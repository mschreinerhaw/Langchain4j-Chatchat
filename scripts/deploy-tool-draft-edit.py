"""Deploy API/MCP draft editing without data migrations, preserving exact rollback images."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil


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
    assert re.fullmatch(r'[a-zA-Z0-9.-]+', version)
    stage.evidence = root / 'evidence' / ('tool-draft-edit-' + version)
    stage.evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    config = json.loads((root / 'compose.yaml').read_text())
    databases = {name: config['services'][name]['environment']['CHATCHAT_' + name.upper() + '_POSTGRESQL_URL'].rsplit('/', 1)[1].split('?', 1)[0] for name in ['api', 'mcp']}

    def baseline():
        for name in ['api', 'mcp']:
            actual = stage.run('docker', 'exec', 'runtime-os-app-stage-' + name + '-1', 'sha256sum', '/srv/app/app.jar').split()[0]
            assert actual == manifest['baseline'][name], 'Running baseline changed: ' + name

    def idle():
        assert stage.sql(databases['api'], "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") == '0', 'Active analysis prevents deployment'
        assert not stage.run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'), 'Active Python operations'

    def fingerprints():
        values = stage.policy_fingerprints(databases['api'], databases['mcp'])
        for name, table in [('api', 'sys_role_permission'), ('mcp', 'mcp_tool_release')]:
            values[databases[name] + '.' + table] = stage.sql(databases[name], "select md5(coalesce(jsonb_agg(to_jsonb(t) order by id)::text,'[]')) from " + table + ' t')
        return values

    def routes():
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        rule = root / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'
        if rule.exists():
            stage.run('bash', str(rule))
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')

    baseline()
    for name in ['api', 'mcp']:
        for volume in config['services'][name].get('volumes', []):
            if isinstance(volume, dict) and volume.get('type') == 'bind':
                source = Path(volume['source'])
                assert (source if source.is_absolute() else root / source).exists(), 'Missing bind source: ' + name
        assert stage.digest(release / (name + '.jar')) == manifest['artifacts'][name]['sha256']
        stage.build_application_image(name, release / (name + '.jar'), 'runtime-os-' + name + ':' + version, stage.evidence / (name + '-image'))
    idle()
    before = fingerprints()
    backup = stage.backup(config, databases)
    report = {'version': version, 'sourceCommit': manifest['sourceCommit'], 'backup': str(backup), 'policyBefore': before, 'artifacts': manifest['artifacts'], 'databaseMigration': False}
    print(json.dumps({'stage': 'BACKED_UP', 'backup': str(backup)}), flush=True)
    baseline()
    idle()
    try:
        stage.compose('stop', 'api', 'mcp')
        for name in ['api', 'mcp']:
            config['services'][name]['image'] = 'runtime-os-' + name + ':' + version
        (root / 'compose.yaml').write_text(json.dumps(config, indent=2))
        env = root / 'config/deployment.env'
        env.write_text('\n'.join(line for line in env.read_text().splitlines() if not line.startswith(('API_IMAGE=', 'MCP_IMAGE=')))
                       + '\nAPI_IMAGE=' + config['services']['api']['image'] + '\nMCP_IMAGE=' + config['services']['mcp']['image'] + '\n')
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'api', 'mcp')
        stage.ready(18180)
        stage.ready(18190)
        after = fingerprints()
        assert before == after, 'Existing releases or authorization changed'
        for name in ['api', 'mcp']:
            actual = stage.run('docker', 'exec', 'runtime-os-app-stage-' + name + '-1', 'sha256sum', '/srv/app/app.jar').split()[0]
            assert actual == manifest['artifacts'][name]['sha256'], 'Running artifact differs: ' + name
        routes()
        report.update(status='DEPLOYED', policyAfter=after, existingReleasesAndPermissionsUnchanged=True)
    except Exception:
        for name, target in [('compose.yaml', root / 'compose.yaml'), ('deployment.env', root / 'config/deployment.env')]:
            shutil.copy2(backup / name, target)
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'api', 'mcp')
        stage.ready(18180)
        stage.ready(18190)
        routes()
        report['status'] = 'ROLLED_BACK'
        (stage.evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
        raise
    (stage.evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
