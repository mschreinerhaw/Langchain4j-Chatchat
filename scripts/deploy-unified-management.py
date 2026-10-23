"""Deploy verified API/MCP artifacts to the existing stage with exact backups and policy reconciliation.

Run on the authorized Linux host. No credentials are accepted on the command line or exported in evidence.
This script leaves databases intact on rollback; additive schema and historical releases are retained.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from application_image_build import build_application_image


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def run(*args, data=None):
    result = subprocess.run(args, input=data, text=True, capture_output=True)
    if result.returncode:
        (evidence / 'command-error-private.txt').write_text(result.stdout + '\n' + result.stderr)
        raise RuntimeError('Command failed: ' + args[0] + '; details retained in private server evidence')
    return result.stdout.strip()


def compose(*args):
    return run('docker', 'compose', '--project-name', 'runtime-os-app-stage', '--env-file',
               str(root / 'config/deployment.env'), '-f', str(root / 'compose.yaml'), *args)


def sql(database, query):
    return run('docker', 'exec', '-i', '-u', 'postgres', 'postgres', 'psql', '-At',
               '-v', 'ON_ERROR_STOP=1', '-d', database, data=query)


def policy_fingerprints(api_database, mcp_database):
    result = {}
    for database, tables in [(api_database, ['mcp_tool_permission', 'resource_grant', 'sys_user_role']),
                             (mcp_database, ['mcp_template_query_binding'])]:
        for table in tables:
            if sql(database, "select to_regclass('public." + table + "')"):
                value = sql(database, "select md5(coalesce(jsonb_agg(to_jsonb(t)-'updated_at'-'created_at' order by id)::text,'[]')) from " + table + ' t')
                result[database + '.' + table] = value
    return result


def ready(port):
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 300
    stable_since = None
    while time.monotonic() < deadline:
        reachable = False
        try:
            with client.open('http://127.0.0.1:' + str(port) + '/api/v1/enterprise/auth/me', timeout=3) as response:
                reachable = response.status == 200
        except urllib.error.HTTPError as error:
            reachable = error.code in (401, 403)
        except OSError:
            pass
        # Spring opens the listener before ApplicationRunner migrations finish. A transient
        # 401 must not count as readiness when a runner subsequently terminates the process.
        if reachable:
            stable_since = stable_since or time.monotonic()
            if time.monotonic() - stable_since >= 20:
                return
        else:
            stable_since = None
        time.sleep(2)
    raise RuntimeError('Application readiness timeout on port ' + str(port))


def backup(config, databases):
    destination = evidence / ('backup-' + str(int(time.time())))
    destination.mkdir(mode=0o700)
    for source in [root / 'compose.yaml', root / 'config/deployment.env']:
        shutil.copy2(source, destination / source.name)
    for service, database in databases.items():
        with (destination / (service + '.dump')).open('wb') as output:
            subprocess.run(['docker', 'exec', '-u', 'postgres', 'postgres', 'pg_dump', '-Fc', '-d', database], stdout=output, check=True)
        container = 'runtime-os-app-stage-' + service + '-1'
        expected = run('docker', 'exec', container, 'sha256sum', '/srv/app/app.jar').split()[0]
        artifact = destination / (service + '-running.jar')
        # Read through the running container namespace: daemon archive lookup can disagree
        # with a healthy container's filesystem. Verify before any application replacement.
        with artifact.open('wb') as output:
            subprocess.run(['docker', 'exec', container, 'cat', '/srv/app/app.jar'], stdout=output, check=True)
        if digest(artifact) != expected:
            raise RuntimeError('Running application backup checksum mismatch: ' + service)
    (destination / 'workspace-registry-private.json').write_text(sql(databases['api'],
        "select coalesce(jsonb_agg(to_jsonb(t))::text,'[]') from workspace_registry t"))
    return destination


def main():
    global root, evidence
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage-root', type=Path, default=Path('/opt/runtime-os-docker-stage'))
    parser.add_argument('--release-dir', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    root = args.stage_root.resolve()
    release = args.release_dir.resolve()
    manifest = json.loads((release / 'release-manifest.json').read_text())
    version = manifest['version']
    if not version.replace('.', '').replace('-', '').isalnum():
        raise RuntimeError('Invalid release version')
    evidence = root / 'evidence' / ('unified-management-' + version)
    evidence.mkdir(mode=0o700, parents=True, exist_ok=True)
    config = json.loads((root / 'compose.yaml').read_text())
    databases = {}
    for service in ['api', 'mcp']:
        artifact = release / (service + '.jar')
        if digest(artifact) != manifest['artifacts'][service]['sha256']:
            raise RuntimeError('Artifact digest mismatch: ' + service)
        url = config['services'][service]['environment']['CHATCHAT_' + service.upper() + '_POSTGRESQL_URL']
        databases[service] = url.rsplit('/', 1)[1].split('?', 1)[0]
    # Build before interrupting existing services; images contain exactly one application layer.
    for service in ['mcp', 'api']:
        print(json.dumps({'stage': 'BUILD_IMAGE', 'service': service}), flush=True)
        build_application_image(service, release / (service + '.jar'), 'runtime-os-' + service + ':' + version,
                                evidence / (service + '-image'))
    active = sql(databases['api'], "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
    if active != '0' or run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'):
        raise RuntimeError('Active analysis tasks prevent replacement; existing services remain running')
    before = policy_fingerprints(databases['api'], databases['mcp'])
    destination = backup(config, databases)
    print(json.dumps({'stage': 'BACKED_UP', 'backup': str(destination)}), flush=True)
    report = {'version': version, 'sourceCommit': manifest['sourceCommit'], 'backup': str(destination), 'policyBefore': before}
    try:
        compose('stop', 'api', 'mcp')
        for service, migration in [('mcp', 'V20261022_03__mcp_tool_releases.sql'),
                                    ('api', 'V20261022_02__unified_management_control_plane.sql')]:
            script = release / 'database/migration/postgresql' / migration
            run('docker', 'exec', '-i', '-u', 'postgres', 'postgres', 'psql', '-1', '-v', 'ON_ERROR_STOP=1',
                '-d', databases[service], data=script.read_text())
        for service in ['api', 'mcp']:
            config['services'][service]['image'] = 'runtime-os-' + service + ':' + version
        (root / 'compose.yaml').write_text(json.dumps(config, indent=2))
        env = root / 'config/deployment.env'
        env.write_text('\n'.join(line for line in env.read_text().splitlines()
                                 if not line.startswith(('API_IMAGE=', 'MCP_IMAGE='))) +
                       '\nAPI_IMAGE=runtime-os-api:' + version + '\nMCP_IMAGE=runtime-os-mcp:' + version + '\n')
        compose('up', '-d', '--no-deps', '--force-recreate', 'mcp', 'api')
        ready(18180)
        ready(18190)
        # Application-ready seeds run after the web listener starts.
        deadline = time.monotonic() + 120
        while sql(databases['api'], "select count(*) from sys_permission where permission_code='system:unified-navigation:v1'") != '1':
            if time.monotonic() > deadline:
                raise RuntimeError('Control-plane migration did not finish')
            time.sleep(2)
        compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        worker_rule = root / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'
        if worker_rule.exists():
            run('bash', str(worker_rule))
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-t')
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        after = policy_fingerprints(databases['api'], databases['mcp'])
        report.update(policyAfter=after, policyUnchanged=before == after)
        if before != after:
            raise RuntimeError('Existing authorization or fixed-template scope changed during migration')
        report.update(status='DEPLOYED', artifacts=manifest['artifacts'])
    except Exception:
        # Restore navigation for the previous application while retaining all release/audit history.
        original = json.loads((destination / 'workspace-registry-private.json').read_text())
        literal = lambda value: "'" + str(value).replace("'", "''") + "'"
        for row in original:
            sql(databases['api'], 'update workspace_registry set manifest_json=' + literal(row['manifest_json']) +
                ',status=' + literal(row['status']) + ',access_permission_code=' + literal(row['access_permission_code']) +
                ',default_workspace=' + ('true' if row['default_workspace'] else 'false') +
                ',version=version+1 where id=' + literal(row['id']))
        existing_ids = {row['id'] for row in original}
        for workspace in ['permissions', 'mcp-publication']:
            if workspace not in existing_ids:
                sql(databases['api'], "update workspace_registry set status='disabled' where id=" + literal(workspace))
        sql(databases['api'], "delete from sys_permission where permission_code='system:unified-navigation:v1'")
        for name, target in [('compose.yaml', root / 'compose.yaml'), ('deployment.env', root / 'config/deployment.env')]:
            shutil.copy2(destination / name, target)
        compose('up', '-d', '--no-deps', '--force-recreate', 'mcp', 'api')
        ready(18180)
        compose('up', '-d', '--no-deps', '--force-recreate', 'node-proxy')
        run('docker', 'exec', 'runtime-os-app-stage-gateway-1', 'nginx', '-s', 'reload')
        report['status'] = 'APPLICATIONS_ROLLED_BACK_DATABASE_RETAINED'
        (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
        raise
    (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
