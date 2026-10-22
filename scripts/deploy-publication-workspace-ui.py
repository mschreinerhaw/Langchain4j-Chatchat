"""API-only UI deployment; preserve policy data and immutable previous images, without changing MCP or navigation."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

spec = importlib.util.spec_from_file_location('stage', Path(__file__).with_name('deploy-unified-management.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--stage-root', type=Path, default=Path('/opt/runtime-os-docker-stage'))
    args = parser.parse_args()
    os.umask(0o077)
    root, release = args.stage_root.resolve(), args.release_dir.resolve()
    manifest = json.loads((release / 'release-manifest.json').read_text())
    version = manifest['version']
    if not version.replace('.', '').replace('-', '').isalnum():
        raise ValueError('Invalid release version')
    evidence = root / 'evidence' / ('publication-ui-' + version)
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    stage.root, stage.evidence = root, evidence
    config = json.loads((root / 'compose.yaml').read_text())
    databases = {name: config['services'][name]['environment']['CHATCHAT_' + name.upper() + '_POSTGRESQL_URL'].rsplit('/', 1)[1].split('?', 1)[0] for name in ['api', 'mcp']}
    if stage.digest(release / 'api.jar') != manifest['artifacts']['api']['sha256']:
        raise RuntimeError('Release artifact hash mismatch')
    if shutil.disk_usage(root).free < 1100 * 1024 * 1024:
        raise RuntimeError('Insufficient space to build safely; current service remains running')
    context = evidence / 'api-image'
    stage.build_application_image('api', release / 'api.jar', 'runtime-os-api:' + version, context)
    # The built image and release retain the JAR; this is only a duplicate Docker build input.
    if stage.digest(context / 'app.jar') == manifest['artifacts']['api']['sha256']:
        (context / 'app.jar').unlink()

    def idle():
        if stage.sql(databases['api'], "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") != '0' or stage.run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'):
            raise RuntimeError('An analysis task is active; current service remains running')

    def fingerprints():
        result = stage.policy_fingerprints(databases['api'], databases['mcp'])
        result[databases['api'] + '.sys_role_permission'] = stage.sql(databases['api'], "select md5(coalesce(jsonb_agg(to_jsonb(t)-'updated_at'-'created_at' order by id)::text,'[]')) from sys_role_permission t")
        return result

    def refresh_proxy():
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        stage.run('bash', str(root / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'))
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')

    idle()
    before = fingerprints()
    backup = evidence / ('backup-' + str(int(time.time())))
    backup.mkdir(mode=0o700)
    for source in [root / 'compose.yaml', root / 'config/deployment.env']:
        shutil.copy2(source, backup / source.name)
    previous = {'image': config['services']['api']['image'], 'imageId': stage.run('docker', 'image', 'inspect', '--format', '{{.Id}}', config['services']['api']['image']), 'sha256': stage.run('docker', 'exec', 'runtime-os-app-stage-api-1', 'sha256sum', '/srv/app/app.jar').split()[0]}
    (backup / 'previous-api-artifact.json').write_text(json.dumps(previous, indent=2))
    # Previous image/JAR and the earlier full backups remain intact. This upgrade changes no MCP or DB schema.
    with (backup / 'api.dump').open('wb') as output:
        subprocess.run(['docker', 'exec', '-u', 'postgres', 'postgres', 'pg_dump', '-Fc', '-d', databases['api']], stdout=output, check=True)
    idle()
    print(json.dumps({'stage': 'BACKED_UP', 'backup': str(backup)}), flush=True)
    report = {'version': version, 'sourceCommit': manifest['sourceCommit'], 'backup': str(backup), 'previousApi': previous, 'mcpImage': config['services']['mcp']['image'], 'policyBefore': before, 'artifacts': manifest['artifacts'], 'databaseMigration': False}
    try:
        stage.compose('stop', 'api')
        config['services']['api']['image'] = 'runtime-os-api:' + version
        (root / 'compose.yaml').write_text(json.dumps(config, indent=2))
        env = root / 'config/deployment.env'
        env.write_text('\n'.join(line for line in env.read_text().splitlines() if not line.startswith('API_IMAGE=')) + '\nAPI_IMAGE=runtime-os-api:' + version + '\n')
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'api')
        stage.ready(18180)
        if before != fingerprints():
            raise RuntimeError('Business authorization or MCP template bindings changed')
        for service in ['api', 'mcp']:
            actual = stage.run('docker', 'exec', 'runtime-os-app-stage-' + service + '-1', 'sha256sum', '/srv/app/app.jar').split()[0]
            if actual != manifest['artifacts'][service]['sha256']:
                raise RuntimeError('Running artifact mismatch: ' + service)
        refresh_proxy()
        report.update(status='DEPLOYED', policyAfter=fingerprints(), policyUnchanged=True, artifactHashesMatch=True)
    except Exception:
        for name, target in [('compose.yaml', root / 'compose.yaml'), ('deployment.env', root / 'config/deployment.env')]:
            shutil.copy2(backup / name, target)
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'api')
        stage.ready(18180)
        refresh_proxy()
        report['status'] = 'ROLLED_BACK'
        (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
        raise
    (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
