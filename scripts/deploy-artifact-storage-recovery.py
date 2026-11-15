"""Server-side source compilation of the narrow storage/projection repair.

Stage Git source bytes + source-hashes.json + source-commit.txt in RELEASE.
Run python3 deploy-artifact-storage-recovery.py RELEASE [--activate].
Each distinct running API/worker baseline is patched independently, preserving
its unrelated code and frontend. No database migration or data-volume changes.
"""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.error
import zipfile


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, **kwargs).strip()


def patch_jar(source, target, replacements):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w') as result:
        if not set(replacements).issubset(original.namelist()):
            raise RuntimeError('Unexpected baseline class layout')
        for entry in original.infolist():
            result.writestr(copy.copy(entry), replacements.get(entry.filename, original.read(entry.filename)))
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target) as result:
        changed = [name for name in original.namelist() if original.read(name) != result.read(name)]
        if not changed or not set(changed).issubset(replacements):
            raise RuntimeError('Unexpected jar patch scope')
    return changed


def refresh_shared_namespace(compose, command):
    """API is the namespace owner; replacing it invalidates every follower."""
    config = json.loads(compose.read_text())
    followers = [role for role, cfg in config['services'].items()
                 if cfg.get('network_mode') == 'service:temporal-control-api' and not cfg.get('profiles')]
    if 'temporal' not in followers:
        raise RuntimeError('Expected the declared Temporal namespace dependency')
    run(*command, 'up', '-d', '--no-deps', '--force-recreate', 'temporal')
    owner = json.loads(run('docker', 'inspect', 'runtime-native-temporal-api'))[0]
    for attempt in range(60):
        try:
            run('nsenter', '--target', str(owner['State']['Pid']), '--net', 'python3', '-c',
                "import socket; socket.create_connection(('127.0.0.1',7233),timeout=1).close()")
            break
        except subprocess.CalledProcessError:
            if attempt == 59:
                raise RuntimeError('Temporal is not reachable in the new API namespace')
            time.sleep(1)
    remaining = [role for role in followers if role != 'temporal']
    run(*command, 'up', '-d', '--no-deps', '--force-recreate', *remaining)
    for role in followers:
        actual = json.loads(run('docker', 'inspect', config['services'][role]['container_name']))[0]
        if not actual['State']['Running'] or actual['HostConfig']['NetworkMode'] != 'container:' + owner['Id']:
            raise RuntimeError('Shared namespace follower is stale: ' + role)
    return dict(status='PASSED', namespaceOwner=owner['Id'], refreshedServices=followers,
                temporalTcpReachable=True)


def main():
    release = Path(sys.argv[1]).resolve()
    source = release / 'source'
    expected = json.loads((release / 'source-hashes.json').read_text())
    revision = (release / 'source-commit.txt').read_text().strip()
    if len(revision) != 40 or any(c not in '0123456789abcdef' for c in revision):
        raise RuntimeError('Expected a Git source revision')
    for name, digest in expected.items():
        path = (source / name).resolve()
        if not path.is_relative_to(source) or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Source hash mismatch')
    if shutil.disk_usage(release).free < 8 * 1024**3:
        raise RuntimeError('At least 8 GiB free is required before building')
    names = {'temporal-control-api': 'runtime-native-temporal-api',
             'worker1': 'runtime-native-temporal-worker1', 'worker2': 'runtime-native-temporal-worker2'}
    containers = {role: json.loads(run('docker', 'inspect', name))[0] for role, name in names.items()}
    baseline = release / 'baseline.jar'
    run('docker', 'cp', names['temporal-control-api'] + ':/srv/app/app.jar', str(baseline))
    dependencies = release / 'dependencies'
    dependencies.mkdir(exist_ok=True)
    with zipfile.ZipFile(baseline) as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') or name.startswith('BOOT-INF/classes/'):
                if not (dependencies / name).resolve().is_relative_to(dependencies):
                    raise RuntimeError('Unsafe baseline jar entry')
                jar.extract(name, dependencies)
    classes = release / 'classes'
    classes.mkdir(exist_ok=True)
    javac = '/opt/jdk-17.0.5/bin/javac'
    compile_log = run(javac, '--release', '17', '-encoding', 'UTF-8', '-cp',
                      str(dependencies / 'BOOT-INF/classes') + ':' + str(dependencies / 'BOOT-INF/lib/*'),
                      '-d', str(classes), *[str(source / name) for name in expected if name.endswith('.java')])
    (release / 'compile.log').write_text(run(javac, '-version') + '\n' + compile_log)
    chat_replacements = {p.relative_to(classes).as_posix(): p.read_bytes()
                         for p in (classes / 'com/chatchat/chat/uiartifact').glob('*.class')}
    api_replacements = {'BOOT-INF/classes/' + p.relative_to(classes).as_posix(): p.read_bytes()
                        for p in (classes / 'com/chatchat/api/runtime/coding/monitor').glob('*.class')}
    deployments = {}
    for image in set(d['Image'] for d in containers.values()):
        folder = release / image.split(':')[1][:12]
        folder.mkdir(exist_ok=True)
        name = next(d['Name'].lstrip('/') for d in containers.values() if d['Image'] == image)
        original = folder / 'baseline.jar'
        run('docker', 'cp', name + ':/srv/app/app.jar', str(original))
        with zipfile.ZipFile(original) as jar:
            chat_name = next(n for n in jar.namelist() if n.startswith('BOOT-INF/lib/chatchat-chat-'))
            (folder / 'chat-original.jar').write_bytes(jar.read(chat_name))
        changed_chat = patch_jar(folder / 'chat-original.jar', folder / 'chat.jar', chat_replacements)
        changes = dict(api_replacements)
        changes[chat_name] = (folder / 'chat.jar').read_bytes()
        changed_app = patch_jar(original, folder / 'app.jar', changes)
        base_tag = 'runtime-api:storage-recovery-baseline-' + image.split(':')[1][:12]
        run('docker', 'tag', image, base_tag)
        (folder / 'Dockerfile').write_text('FROM ' + base_tag + '\nLABEL org.opencontainers.image.revision="' + revision + '"\nCOPY app.jar /srv/app/app.jar\n')
        (folder / '.dockerignore').write_text('**\n!app.jar\n!Dockerfile\n')
        tag = 'runtime-api:storage-recovery-' + image.split(':')[1][:12] + '-20261115'
        try:
            (folder / 'build.log').write_text(run('docker', 'build', '-t', tag, str(folder)))
        except subprocess.CalledProcessError as error:
            (folder / 'build.log').write_text(error.output)
            raise
        deployments[image] = dict(image=json.loads(run('docker', 'inspect', tag))[0]['Id'], tag=tag,
                                  changedAppEntries=changed_app, changedChatEntries=changed_chat)
    receipt = dict(buildMode='server-javac-source', sourceCommit=revision,
                   sourceHashes=expected, baselines=deployments, activated=False)
    (release / 'deployment.json').write_text(json.dumps(receipt, indent=2))
    if '--activate' not in sys.argv:
        print(json.dumps(receipt));return
    for node, port in [('runtime-native-temporal-node1', 18461), ('runtime-native-temporal-node2', 18471)]:
        health = json.loads(run('docker', 'exec', node, 'node', '-e',
            "fetch('http://127.0.0.1:" + str(port) + "/health/live').then(r=>r.text()).then(console.log)"))
        if health['capacity']['activeWorkers']:
            raise RuntimeError('Active Coding workers prevent service replacement')
    compose = Path('/opt/runtime-os/native-temporal/rc-20261112/compose.private.json')
    original = compose.read_bytes()
    backup = release / 'compose.rollback.private.json'
    with backup.open('xb') as f:
        f.write(original)
    backup.chmod(0o600)
    config = json.loads(original)
    for role, info in containers.items():
        config['services'][role]['image'] = deployments[info['Image']]['image']
        config['services'][role]['logging'] = dict(driver='json-file', options={'max-size': '100m', 'max-file': '3'})
    compose.write_text(json.dumps(config, indent=2));compose.chmod(0o600)
    command = ['docker', 'compose', '-p', 'runtime-native-temporal-candidate', '-f', str(compose)]
    try:
        run(*command, 'up', '-d', '--no-deps', *names)
        receipt['networkNamespace'] = refresh_shared_namespace(compose, command)
        ready = False
        for _ in range(120):
            try:
                urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:18180/api/v1/coding-monitor/tasks', timeout=2)
            except urllib.error.HTTPError as e:
                if e.code in [401, 403]:
                    ready = True;break
            except (OSError, TimeoutError):
                pass
            time.sleep(1)
        if not ready:
            raise RuntimeError('API readiness deadline')
        for role, name in names.items():
            actual = json.loads(run('docker', 'inspect', name))[0]
            if not actual['State']['Running'] or actual['Image'] != deployments[containers[role]['Image']]['image']:
                raise RuntimeError('Running service differs from compiled image')
    except Exception:
        compose.write_bytes(original)
        run(*command, 'up', '-d', '--no-deps', *names)
        refresh_shared_namespace(compose, command)
        raise
    receipt.update(activated=True, readiness='authenticated route rejects anonymous access', dataVolumesChanged=False)
    (release / 'deployment.json').write_text(json.dumps(receipt, indent=2))
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
