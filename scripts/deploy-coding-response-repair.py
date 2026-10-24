"""Deploy a hash-pinned API/worker repair while retaining unrelated production artifacts."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile

spec = importlib.util.spec_from_file_location('stage', Path(__file__).with_name('deploy-unified-management.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


def patch_zip(source, replacements):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as original, zipfile.ZipFile(output, 'w') as target:
        if not set(replacements).issubset(original.namelist()):
            raise ValueError('Missing replacement entry')
        for entry in original.infolist():
            target.writestr(entry, replacements.get(entry.filename, original.read(entry.filename)))
    with zipfile.ZipFile(io.BytesIO(source)) as original, zipfile.ZipFile(output) as result:
        for name in original.namelist():
            assert result.read(name) == replacements.get(name, original.read(name)), name
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--build-only', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    release = args.release_dir.resolve()
    manifest = json.loads((release / 'repair-manifest.json').read_text())
    version = manifest['version']
    assert version.replace('.', '').replace('-', '').isalnum()
    root = Path('/opt/runtime-os-docker-stage')
    evidence = release / 'evidence'
    evidence.mkdir(exist_ok=True, mode=0o700)
    stage.root, stage.evidence = root, evidence
    config = json.loads((root / 'compose.yaml').read_text())
    api_container = 'runtime-os-app-stage-api-1'
    node_container = 'runtime-os-app-stage-coding-agent-1'
    api_tag, node_tag = 'runtime-os-api:' + version, 'runtime-os-coding-agent:' + version

    def baseline():
        assert stage.run('docker', 'exec', api_container, 'sha256sum', '/srv/app/app.jar').split()[0] == manifest['apiBaseline']
        for name, expected in manifest['nodeBaseline'].items():
            assert stage.run('docker', 'exec', node_container, 'sha256sum', '/srv/coding-agent/dist/' + name).split()[0] == expected

    baseline()
    if not (release / 'build.json').exists():
        old = release / 'api-before.jar'
        with old.open('wb') as output:
            subprocess.run(['docker', 'exec', api_container, 'cat', '/srv/app/app.jar'], stdout=output, check=True)
        assert stage.digest(old) == manifest['apiBaseline']
        replacements = {}
        with zipfile.ZipFile(old) as jar:
            for details in manifest['classes']:
                name = details['entry']
                content = jar.read(name)
                if details.get('nested'):
                    inner = details['nested']
                    with zipfile.ZipFile(io.BytesIO(content)) as library:
                        assert hashlib.sha256(library.read(inner)).hexdigest() == details['baseline']
                    replacements[name] = patch_zip(replacements.get(name, content), {inner: (release / details['file']).read_bytes()})
                else:
                    assert hashlib.sha256(content).hexdigest() == details['baseline']
                    replacements[name] = (release / details['file']).read_bytes()
        (release / 'api.jar').write_bytes(patch_zip(old.read_bytes(), replacements))
        os.environ['CHATCHAT_API_RUNTIME_IMAGE'] = 'runtime-os-api-runtime:17-sqlglot-20261024'
        os.environ['CHATCHAT_IMAGE_TEMPLATES'] = str(release / 'templates')
        stage.build_application_image('api', release / 'api.jar', api_tag, release / 'api-image')
        context = release / 'node-image'
        context.mkdir()
        stage.run('docker', 'cp', node_container + ':/srv/coding-agent/.', str(context))
        for name, expected in manifest['nodeBaseline'].items():
            assert stage.digest(context / 'dist' / name) == expected
            shutil.copy2(release / 'dist' / name, context / 'dist' / name)
        shutil.copy2(release / 'templates/Dockerfile.coding-agent', context / 'Dockerfile')
        stage.run('docker', 'build', '--build-arg', 'BUILD_REVISION=' + manifest['sourceCommit'], '-t', node_tag, str(context))
        (release / 'build.json').write_text(json.dumps({'apiSha256': stage.digest(release / 'api.jar'), 'worker': {n: stage.digest(release / 'dist' / n) for n in manifest['nodeBaseline']}}))
    if args.build_only:
        print('BUILD_VERIFIED', flush=True)
        return
    assert (release / 'worker-tests-passed').exists(), 'Production Node tests must pass first'
    databases = {n: config['services'][n]['environment']['CHATCHAT_' + n.upper() + '_POSTGRESQL_URL'].rsplit('/', 1)[1].split('?', 1)[0] for n in ['api', 'mcp']}

    def idle():
        assert stage.sql(databases['api'], "select count(*) from agent_task_latest where canonical_state is null or canonical_state not in ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')") == '0', 'Active task'
        assert not stage.run('docker', 'ps', '--filter', 'name=chatchat-py-', '--format', '{{.ID}}'), 'Python execution active'
        observations = json.loads((root / 'coding-monitor/data/observations.json').read_text())
        assert all(t['status'] in {'COMPLETED', 'FAILED', 'CANCELLED'} for t in observations['tasks']), 'Worker session active'

    def fingerprints():
        values = stage.policy_fingerprints(databases['api'], databases['mcp'])
        values['roles'] = stage.sql(databases['api'], "select md5(coalesce(jsonb_agg(to_jsonb(t)-'updated_at'-'created_at' order by id)::text,'[]')) from sys_role_permission t")
        return values

    def refresh():
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
    with (backup / 'api.dump').open('wb') as output:
        subprocess.run(['docker', 'exec', '-u', 'postgres', 'postgres', 'pg_dump', '-Fc', '-d', databases['api']], stdout=output, check=True)
    idle()
    baseline()
    report = {'version': version, 'sourceCommit': manifest['sourceCommit'], 'backup': str(backup), 'previousImages': {n: config['services'][n]['image'] for n in ['api', 'coding-agent']}, 'policyBefore': before}
    try:
        stage.compose('stop', 'api', 'coding-agent')
        config['services']['api']['image'] = api_tag
        config['services']['coding-agent']['image'] = node_tag
        config['services']['api']['environment'].update(CHATCHAT_CODING_CLINE_MAX_OUTPUT_TOKENS='32768', CHATCHAT_CODING_CLINE_MAX_MODEL_RESPONSE_REPAIRS='2')
        config['services']['coding-agent']['environment']['RUNTIME_CODING_CALLBACK_TIMEOUT_MS'] = '180000'
        (root / 'compose.yaml').write_text(json.dumps(config, indent=2))
        env = root / 'config/deployment.env'
        env.write_text('\n'.join(line for line in env.read_text().splitlines() if not line.startswith(('API_IMAGE=', 'NODE_IMAGE='))) + '\nAPI_IMAGE=' + api_tag + '\nNODE_IMAGE=' + node_tag + '\n')
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'api', 'coding-agent')
        stage.ready(18180)
        for _ in range(30):
            if stage.run('docker', 'inspect', '--format', '{{.State.Health.Status}}', node_container) == 'healthy':
                break
            time.sleep(2)
        else:
            raise RuntimeError('Worker readiness timeout')
        assert before == fingerprints(), 'Authorization changed'
        built = json.loads((release / 'build.json').read_text())
        assert stage.run('docker', 'exec', api_container, 'sha256sum', '/srv/app/app.jar').split()[0] == built['apiSha256']
        for name, digest in built['worker'].items():
            assert stage.run('docker', 'exec', node_container, 'sha256sum', '/srv/coding-agent/dist/' + name).split()[0] == digest
        refresh()
        report.update(status='DEPLOYED', policyUnchanged=True, artifacts=built)
    except Exception:
        shutil.copy2(backup / 'compose.yaml', root / 'compose.yaml')
        shutil.copy2(backup / 'deployment.env', root / 'config/deployment.env')
        stage.compose('up', '-d', '--no-deps', '--force-recreate', 'api', 'coding-agent')
        stage.ready(18180)
        refresh()
        report['status'] = 'ROLLED_BACK'
        (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
        raise
    (evidence / 'deployment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
