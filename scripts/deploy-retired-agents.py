"""Deploy the professional-Agent retirement release with an explicit metadata/table rollback."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import time

spec = importlib.util.spec_from_file_location('stage_deployment', Path(__file__).with_name('deploy-unified-management.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


def legacy_snapshot(database, schema):
    result = {}
    for table in ['agent_compute_definition', 'agent_provider_health', 'agent_a2a_task_link']:
        relation = schema + '.' + table
        if stage.sql(database, "select to_regclass('" + relation + "')"):
            result[table] = stage.sql(database, "select count(*)||':'||md5(coalesce(jsonb_agg(to_jsonb(t) order by id)::text,'[]')) from " + relation + ' t')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage-root', type=Path, default=Path('/opt/runtime-os-docker-stage'))
    parser.add_argument('--release-dir', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    root, release = args.stage_root.resolve(), args.release_dir.resolve()
    manifest = json.loads((release / 'release-manifest.json').read_text())
    version = manifest['version']
    if not version.replace('.', '').replace('-', '').isalnum():
        raise ValueError('Invalid version')
    evidence = root / 'evidence' / ('retired-agents-' + version)
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    stage.root, stage.evidence = root, evidence
    config = json.loads((root / 'compose.yaml').read_text())
    databases = {}
    for service in ['api', 'mcp']:
        if stage.digest(release / (service + '.jar')) != manifest['artifacts'][service]['sha256']:
            raise RuntimeError('Artifact digest mismatch: ' + service)
        url = config['services'][service]['environment']['CHATCHAT_' + service.upper() + '_POSTGRESQL_URL']
        databases[service] = url.rsplit('/', 1)[1].split('?', 1)[0]
        print(json.dumps({'stage': 'BUILD_IMAGE', 'service': service}), flush=True)
        stage.build_application_image(service, release / (service + '.jar'), 'runtime-os-' + service + ':' + version, evidence / (service + '-image'))
    api = databases['api']
    active = stage.sql(api, "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
    if active != '0' or stage.run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'):
        raise RuntimeError('Active tasks prevent deployment')
    original = legacy_snapshot(api, 'public')
    before = stage.policy_fingerprints(api, databases['mcp'])
    destination = stage.backup(config, databases)
    report = dict(version=version, sourceCommit=manifest['sourceCommit'], backup=str(destination), archivedBefore=original, policyBefore=before)
    print(json.dumps({'stage': 'BACKED_UP', 'backup': str(destination)}), flush=True)
    migrated = False
    try:
        stage.compose('stop', 'api', 'mcp')
        stage.sql(api, (release / 'V20261022_04__retire_professional_agents.sql').read_text())
        migrated = True
        for service in ['api', 'mcp']:
            config['services'][service]['image'] = 'runtime-os-' + service + ':' + version
        (root / 'compose.yaml').write_text(json.dumps(config, indent=2))
        env = root / 'config/deployment.env'
        env.write_text('\n'.join(line for line in env.read_text().splitlines() if not line.startswith(('API_IMAGE=', 'MCP_IMAGE='))) + '\nAPI_IMAGE=runtime-os-api:' + version + '\nMCP_IMAGE=runtime-os-mcp:' + version + '\n')
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'mcp', 'api')
        stage.ready(18180)
        stage.ready(18190)
        if stage.sql(api, "select count(*) from runtime_product_retirement_archive where record_type='MARKER' and record_id='professional-agents-v1'") != '1':
            raise RuntimeError('Product metadata migration did not complete')
        remaining = stage.sql(api, "select (select count(*) from sys_menu where menu_code='domainAnalysis')+(select count(*) from sys_permission where permission_code='workspace:analysis:api')+(select count(*) from workspace_registry where manifest_json::jsonb @? '$.pages[*] ? (@.componentKey == \"domainAnalysis\")')")
        if remaining != '0' or legacy_snapshot(api, 'public') or legacy_snapshot(api, 'runtime_os_archive') != original:
            raise RuntimeError('Legacy metadata remains or archive differs')
        after = stage.policy_fingerprints(api, databases['mcp'])
        if before != after:
            raise RuntimeError('Authorization or fixed-template scope changed')
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        worker_rule = root / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'
        if worker_rule.exists():
            stage.run('bash', str(worker_rule))
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        report.update(status='DEPLOYED', policyAfter=after, policyUnchanged=True, archiveExact=True, artifacts=manifest['artifacts'])
    except Exception:
        stage.compose('stop', 'api', 'mcp')
        if migrated:
            stage.sql(api, (release / 'R20261022_04__restore_professional_agents.sql').read_text())
        for name, target in [('compose.yaml', root / 'compose.yaml'), ('deployment.env', root / 'config/deployment.env')]:
            shutil.copy2(destination / name, target)
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'mcp', 'api')
        stage.ready(18180)
        stage.ready(18190)
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        stage.run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        report['status'] = 'ROLLED_BACK'
        (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
        raise
    (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
