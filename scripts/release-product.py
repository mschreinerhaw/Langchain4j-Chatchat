"""Build, switch, verify and clean a dated release on an existing Linux Docker host.

Keeps live configuration/keys outside the portable package. Usage is documented
in docs/product-deployment-1.2-20261022.md. Requires Python >=3.9, Docker Compose v2.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.request


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        # Compose errors may contain interpolated credentials; keep details private.
        (release / 'evidence/last-command-error.txt').write_text(result.stderr + result.stdout)
        raise RuntimeError('Command failed: ' + args[0] + '; see private evidence/last-command-error.txt')
    return result.stdout.strip()


def record(name, value):
    (release / ('evidence/' + name + '.json')).write_text(json.dumps(value, indent=2) + '\n')


def inspect(name): return json.loads(run('docker', 'inspect', name))[0]
def container(service): return project + '-' + service + '-1'
def compose(*args):
    return run('docker', 'compose', '-p', project, '--env-file', str(stage / 'config/deployment.env'),
               '-f', str(stage / 'compose.yaml'), *args)


def image_guard(image):
    data = inspect(image)
    history = run('docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', image).splitlines()
    copies = [line for line in history if re.search(r'COPY .*app\.jar', line)]
    if data['Size'] > 1200 * 1024 * 1024 or len(copies) != 1:
        raise RuntimeError('Release image size/layer guard failed: ' + image)
    return {'image': image, 'imageId': data['Id'], 'sizeBytes': data['Size'], 'jarCopyLayers': len(copies)}


def build_one(service, jar, image):
    context = release / ('build/' + image.replace(':', '-'))
    context.mkdir(parents=True, exist_ok=True)
    templates = release / 'deploy/docker/application'
    recipe = {'api': 'Dockerfile.api', 'mcp': 'Dockerfile.mcp', 'news': 'Dockerfile'}[service]
    selected_base = base
    if service == 'api':
        # The offline package includes this JAR-free runtime, so rollback does
        # not need apt/pip access or the builder's ephemeral layer cache.
        runtime = 'runtime-os-api-runtime:' + version
        if subprocess.run(['docker', 'image', 'inspect', runtime], capture_output=True).returncode == 0:
            recipe, selected_base = 'Dockerfile', runtime
    shutil.copy2(templates / recipe, context / 'Dockerfile')
    shutil.copy2(templates / '.dockerignore', context / '.dockerignore')
    shutil.copy2(jar, context / 'app.jar')
    if service == 'api':
        shutil.copy2(templates / 'sql-toolchain-requirements.txt', context / 'sql-toolchain-requirements.txt')
    if service == 'mcp': run('docker', 'cp', container('mcp') + ':/usr/local/bin/docker', str(context / 'docker'))
    if 'app.jar' in run('docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', selected_base):
        raise RuntimeError('Runtime base unexpectedly contains a business JAR')
    result = run('docker', 'build', '--build-arg', 'JAVA_RUNTIME_IMAGE=' + selected_base,
                 '--build-arg', 'APP_PORT=' + ('8091' if service == 'news' else '8080'),
                 '--label', 'org.opencontainers.image.version=' + version,
                 '--label', 'org.opencontainers.image.revision=' + (inspect(container(service))['Image']
                     if image.endswith('-rollback') else manifest['sourceCommit']),
                 '-t', image, str(context))
    (release / ('evidence/build-' + image.replace(':', '-') + '.log')).write_text(result)
    print(json.dumps(image_guard(image)), flush=True)


def build():
    if 'app.jar' in run('docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', base):
        raise RuntimeError('Java runtime base must not contain a business JAR')
    for service in ('api', 'mcp', 'news'):
        jar = release / ('jars/' + service + '.jar')
        with jar.open('rb') as stream:
            digest = hashlib.sha256()
            while chunk := stream.read(1024 * 1024): digest.update(chunk)
        if digest.hexdigest() != manifest['jars'][service]['sha256']:
            raise RuntimeError('Release JAR checksum mismatch: ' + service)
        build_one(service, jar, 'runtime-os-' + service + ':' + version)
    # The worker is unchanged in this release; record and reuse its verified content.
    worker = inspect(container('coding-agent'))['Image']
    run('docker', 'tag', worker, 'runtime-os-coding-agent:' + version)
    record('build', {'version': version, 'sourceCommit': manifest['sourceCommit'],
                    'images': [image_guard('runtime-os-' + s + ':' + version) for s in ('api', 'mcp', 'news')],
                    'worker': {'imageId': worker, 'rebuilt': False}, 'baseImageId': inspect(base)['Id']})


def wait_ready():
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for name, url, codes in [
            ('api', 'http://127.0.0.1:18180/api/v1/enterprise/auth/me', {200, 401, 403}),
            ('mcp', 'http://127.0.0.1:18190/api/v1/license/status', {200, 401, 403})]:
        deadline = time.monotonic() + 240
        while True:
            try:
                with client.open(url, timeout=5) as response: code = response.status
            except urllib.error.HTTPError as error: code = error.code
            except (OSError, urllib.error.URLError): code = None
            if code in codes: break
            if time.monotonic() >= deadline: raise RuntimeError('Readiness timeout: ' + name)
            time.sleep(2)
    run('docker', 'exec', container('api'), 'bash', '-c', 'exec 3<>/dev/tcp/mcp/9091')
    run('docker', 'exec', container('api'), '/usr/bin/python3', '-c',
        "import sqlglot; assert sqlglot.__version__=='30.23.0'; sqlglot.parse_one('select 1')")


def deploy():
    if (release / 'evidence/deployment.json').exists(): raise RuntimeError('Release already deployed; use rollback explicitly')
    active = run('docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At', '-d', 'runtime_os_acceptance_api', '-c',
                 "SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')")
    if active != '0': raise RuntimeError('Active tasks prevent replacement')
    before = {n: inspect(n) for n in run('docker', 'ps', '--format', '{{.Names}}').splitlines()}
    for service in ('api', 'mcp', 'news'):
        if before[container(service)]['Config']['Labels'].get('com.docker.compose.project') != project:
            raise RuntimeError('Unexpected Compose project')
        image_guard('runtime-os-' + service + ':' + version)
    backup = release / 'private-backup'
    backup.mkdir(mode=0o700, exist_ok=False)
    for relative in ('compose.yaml', 'config/deployment.env'):
        shutil.copy2(stage / relative, backup / Path(relative).name)
        (backup / Path(relative).name).chmod(0o600)
    # Keep original JARs and compact equivalent rollback images, so removing the
    # 33 GB chain does not remove the ability to roll back the application.
    for service in ('api', 'mcp'):
        jar = backup / (service + '.jar')
        run('docker', 'cp', container(service) + ':/srv/app/app.jar', str(jar))
        build_one(service, jar, 'runtime-os-' + service + ':' + version + '-rollback')
    for database in ('runtime_os_acceptance_api', 'runtime_os_acceptance_mcp'):
        with (backup / (database + '.dump')).open('wb') as output:
            subprocess.run(['docker', 'exec', '-u', 'postgres', 'postgres', 'pg_dump', '-Fc', database], stdout=output, check=True)
    config = json.loads((stage / 'compose.yaml').read_text())
    rollback = json.loads(json.dumps(config))
    for service in ('api', 'mcp'):
        rollback['services'][service]['image'] = 'runtime-os-' + service + ':' + version + '-rollback'
    (backup / 'rollback-compose.yaml').write_text(json.dumps(rollback, indent=2) + '\n')
    for service in ('api', 'mcp', 'news'):
        config['services'][service]['image'] = 'runtime-os-' + service + ':' + version
    # Node code is unchanged. Keeping its existing container avoids disturbing
    # the private callback network and already validated egress restriction.
    replacements = {s.upper() + '_IMAGE': 'runtime-os-' + s + ':' + version for s in ('api', 'mcp', 'news')}
    lines = (backup / 'deployment.env').read_text().splitlines()
    lines = [line for line in lines if line.split('=', 1)[0] not in replacements]
    try:
        # Close RocksDB's single writer before its physical backup. The original
        # stores remain mounted at their existing locations during the upgrade.
        compose('stop', '-t', '60', 'api')
        data_mount = next(m for m in before[container('api')]['Mounts'] if m['Destination'] == '/srv/app/data')
        for name in ('chat-rocksdb', 'ui-artifacts'):
            source = Path(data_mount['Source']) / name
            if source.is_dir(): shutil.copytree(source, backup / name)
        (stage / 'compose.yaml').write_text(json.dumps(config, indent=2) + '\n')
        (stage / 'config/deployment.env').write_text('\n'.join(lines + [k + '=' + v for k, v in replacements.items()]) + '\n')
        compose('config', '-q')
        compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'mcp', 'news', 'api')
        compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'node-proxy')
        wait_ready()
        restriction = stage / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'
        if restriction.exists(): run('bash', str(restriction))
        run('docker', 'exec', container('gateway'), 'nginx', '-t')
        run('docker', 'exec', container('gateway'), 'nginx', '-s', 'reload')
        allowed = {container(s) for s in ('api', 'mcp', 'news', 'node-proxy')}
        for name, old in before.items():
            if name not in allowed and inspect(name)['Id'] != old['Id']:
                raise RuntimeError('Unrelated container changed: ' + name)
        for service in ('api', 'mcp', 'news'):
            old = before[container(service)]
            new = inspect(container(service))
            if old['Mounts'] != new['Mounts'] or old['Config']['Env'] != new['Config']['Env']:
                # Order is immaterial; resolved values and mount semantics are not.
                if sorted(old['Config']['Env']) != sorted(new['Config']['Env']) or sorted(old['Mounts'], key=lambda x:x['Destination']) != sorted(new['Mounts'], key=lambda x:x['Destination']):
                    raise RuntimeError('Persistent mounts or configuration changed: ' + service)
        record('deployment', {'status': 'DEPLOYED', 'version': version, 'sourceCommit': manifest['sourceCommit'],
            'backup': str(backup), 'unrelatedContainersUnchanged': True, 'configurationAndMountsPreserved': True,
            'beforeImages': {s: before[container(s)]['Image'] for s in ('api','mcp','news')},
            'afterImages': {s: inspect(container(s))['Image'] for s in ('api','mcp','news')}})
    except Exception:
        rollback_release()
        raise
    finally:
        (stage / 'compose.yaml').chmod(0o600)
        (stage / 'config/deployment.env').chmod(0o600)
    print('DEPLOYED', flush=True)


def rollback_release():
    backup = release / 'private-backup'
    shutil.copy2(backup / 'rollback-compose.yaml', stage / 'compose.yaml')
    lines = (backup / 'deployment.env').read_text().splitlines()
    for service in ('api', 'mcp'):
        key = service.upper() + '_IMAGE'
        lines = [line for line in lines if not line.startswith(key + '=')]
        lines.append(key + '=runtime-os-' + service + ':' + version + '-rollback')
    (stage / 'config/deployment.env').write_text('\n'.join(lines) + '\n')
    compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'mcp', 'news', 'api')
    compose('up', '-d', '--no-build', '--no-deps', '--force-recreate', 'node-proxy')
    wait_ready()
    restriction = stage / 'evidence/cline-monitor-20261019/restrict-coding-worker.sh'
    if restriction.exists(): run('bash', str(restriction))
    run('docker', 'exec', container('gateway'), 'nginx', '-s', 'reload')
    print('ROLLED_BACK', flush=True)


def cleanup():
    if not (release / 'evidence/acceptance.json').exists():
        raise RuntimeError('Acceptance evidence is required before deleting old images')
    acceptance = json.loads((release / 'evidence/acceptance.json').read_text())
    if acceptance.get('status') != 'PASSED': raise RuntimeError('Release acceptance did not pass')
    before = run('docker', 'system', 'df')
    protected = {inspect(n)['Image'] for n in run('docker', 'ps', '-aq').splitlines()}
    removed, failures = [], []
    tags = run('docker', 'image', 'ls', '--format', '{{.Repository}}:{{.Tag}}').splitlines()
    candidates = [t for t in tags if t.startswith(('runtime-os-api:', 'runtime-os-mcp:', 'runtime-os-coding-agent:'))
                  and not t.endswith((':' + version, ':' + version + '-rollback'))]
    for tag in candidates:
        if inspect(tag)['Id'] in protected: continue
        try: run('docker', 'image', 'rm', tag); removed.append(tag)
        except RuntimeError: failures.append(tag)
    dangling_removed = []
    for image in run('docker', 'image', 'ls', '--filter', 'dangling=true', '--no-trunc', '-q').splitlines():
        if image in protected: continue
        history = run('docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', image)
        if 'app.jar' not in history and '/srv/coding-agent' not in history: continue
        try: run('docker', 'image', 'rm', image); dangling_removed.append(image)
        except RuntimeError: failures.append(image)
    cache_prune = 'Skipped because active build cache was detected'
    storage = [json.loads(line) for line in run('docker', 'system', 'df', '--format', '{{json .}}').splitlines()]
    if any(x.get('Type') == 'Build Cache' and str(x.get('Active')) == '0' for x in storage):
        # Old application layers can remain pinned by BuildKit even after every
        # old tag is deleted. Remove unused build cache only; images retain their
        # runtime layers and explicit rollback tags.
        cache_prune = run('docker', 'builder', 'prune', '--all', '--force')
    # No volume/container deletion and no global system-prune operation.
    record('cleanup', {'removedTags': removed, 'removedDanglingImages': dangling_removed, 'retainedOrFailedTags': failures,
                       'buildCachePrune': cache_prune,
                       'before': before, 'after': run('docker', 'system', 'df'),
                       'volumesDeleted': False, 'containersDeleted': False})
    print(json.dumps({'removedTags': len(removed), 'retainedOrFailedTags': failures}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'deploy', 'rollback', 'verify', 'cleanup'])
    parser.add_argument('--release-dir', required=True, type=Path)
    parser.add_argument('--stage-root', type=Path, default=Path('/opt/runtime-os-docker-stage'))
    parser.add_argument('--project', default='runtime-os-app-stage')
    parser.add_argument('--java-base', default='eclipse-temurin:17-jre-jammy')
    args = parser.parse_args()
    release, stage, project, base = args.release_dir.resolve(), args.stage_root.resolve(), args.project, args.java_base
    manifest = json.loads((release / 'release-manifest.json').read_text())
    version = manifest['version']
    if not re.fullmatch(r'1\.2\.0-\d{8}', version): raise ValueError('Invalid dated release version')
    os.umask(0o077)
    (release / 'evidence').mkdir(parents=True, exist_ok=True)
    {'build': build, 'deploy': deploy, 'rollback': rollback_release,
     'verify': wait_ready, 'cleanup': cleanup}[args.action]()
