"""Verify server-side build evidence and activate the prepared release."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

from production_compose import release_compose


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    args = parser.parse_args()
    root = Path(args.release).resolve()
    manifest = json.loads((root / 'source-manifest.json').read_text())
    for name, expected in manifest['files'].items():
        path = (root / 'source' / name).resolve()
        if not path.is_relative_to(root / 'source') or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise RuntimeError('Source manifest mismatch: ' + name)
    builds = json.loads((root / 'build-summary.json').read_text())
    if len(builds['builds']) != 5 or any(b['exitCode'] != 0 for b in builds['builds']):
        raise RuntimeError('All five applications must be compiled on this server before activation')
    for build in builds['builds']:
        actual = subprocess.check_output(['docker', 'image', 'inspect', build['image'],
                                         '--format', '{{.Id}}'], text=True).strip()
        if actual != build['imageId']:
            raise RuntimeError('Built image reference changed: ' + build['image'])
    for name in ['api-before.dump', 'mcp-before.dump']:
        if (root / 'private' / name).stat().st_size < 1024:
            raise RuntimeError('Database backup missing: ' + name)
    for filename in ['native-worker-build.exit', 'runtime-regression.exit']:
        if (root / filename).read_text().strip() != '0':
            raise RuntimeError('Runtime build/regression check failed: ' + filename)
    if not (root / 'acl-applied.json').exists():
        raise RuntimeError('Non-root data/config access was not prepared')
    config = json.loads((root / 'compose.production.json').read_text())
    expected_worker = json.loads((root / 'private/cn2-gateway.json').read_text())['workerImage']
    daemon_socket = json.loads((root / 'private/cn2-executor.json').read_text())['dockerSocket']
    actual_worker = subprocess.check_output(['docker', '-H', 'unix://' + daemon_socket,
        'image', 'inspect', config['services']['native-node']['image'], '--format', '{{.Id}}'], text=True).strip()
    if expected_worker != actual_worker:
        raise RuntimeError('Dedicated executor image differs from the pinned source build')
    mounts = config['services']['native-node']['volumes']
    if any('/var/run/docker.sock' in str(m) or 'strict-daemon' in str(m) for m in mounts):
        raise RuntimeError('Business gateway must not receive the executor Docker socket')
    compose, env = release_compose(root)
    (root / 'activation-intent.json').write_text(json.dumps({'startedAt': time.time(),
        'sourceCommit': manifest['commit'], 'verifiedSourceFiles': len(manifest['files']),
        'workerImage': expected_worker, 'existingConfiguration': '/opt/runtime-os-docker-stage/config'}, indent=2))
    command = [*compose, 'up', '-d', '--pull', 'never']
    with (root / 'logs/activation.log').open('w') as output:
        result = subprocess.run(command, env=env, stdout=output, stderr=subprocess.STDOUT)
    (root / 'activation.exit').write_text(str(result.returncode))
    print('Activation command exit code:', result.returncode)
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
