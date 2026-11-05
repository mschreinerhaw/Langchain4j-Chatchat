"""Prepare an offline worker candidate and run its isolated regression gates.

Only prepares a candidate image. Does not stop services or switch traffic.
The bundle uses repository-relative coding-agent-node/ and chatchat-common/ paths.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile

MODULES = ('code-repair.js', 'python-project.js', 'runner.js', 'runtime-model.js', 'sdk-adapter.js')


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', required=True, type=Path)
    parser.add_argument('--archive-sha256', required=True)
    parser.add_argument('--source-commit', required=True)
    args = parser.parse_args()
    root = args.release_dir.resolve()
    archive = root / 'cline-sdk-reuse-worker.tar.gz'
    if hashlib.sha256(archive.read_bytes()).hexdigest() != args.archive_sha256:
        raise ValueError('Worker archive checksum mismatch')
    verify = root / 'verify'
    verify.mkdir(exist_ok=True, mode=0o755)
    with tarfile.open(archive) as bundle:
        for member in bundle.getmembers():
            path = Path(member.name)
            if path.is_absolute() or '..' in path.parts or member.issym() or member.islnk():
                raise ValueError('Unsafe archive entry')
        bundle.extractall(verify, filter='data')
    node = verify / 'coding-agent-node'
    modules = node / 'node_modules'
    if not modules.exists() and not modules.is_symlink():
        modules.symlink_to('/srv/coding-agent/node_modules')
    live = json.loads(run('docker', 'inspect', 'runtime-os-app-stage-coding-agent-1'))[0]
    baseline = 'runtime-os-coding-agent:sdk-reuse-baseline-' + live['Image'].split(':')[-1][:12]
    run('docker', 'tag', live['Image'], baseline)
    image = 'runtime-os-coding-agent:sdk-reuse-20261105-r1'
    patched = root / 'patched'
    patched.mkdir(exist_ok=True)
    before, after = {}, {}
    for module in MODULES:
        before[module] = run('docker', 'exec', live['Id'], 'sha256sum', '/srv/coding-agent/dist/' + module).split()[0]
        content = (node / 'dist' / module).read_bytes()
        (patched / module).write_bytes(content)
        after[module] = hashlib.sha256(content).hexdigest()
    (root / 'Dockerfile').write_text('FROM ' + baseline + '\nCOPY --chown=node:node patched/ /srv/coding-agent/dist/\n'
        + 'ENV RUNTIME_CODING_BUILD=' + args.source_commit + '\n')
    (root / '.dockerignore').write_text('*\n!Dockerfile\n!patched\n!patched/**\n')
    with (root / 'image-build.log').open('w') as log:
        subprocess.run(['docker', 'build', '--network', 'none', '-t', image, str(root)],
            stdout=log, stderr=subprocess.STDOUT, check=True)
    version = json.loads(run('docker', 'run', '--rm', '--network', 'none', '--entrypoint', 'node', image, 'dist/sdk-packages.js'))
    with (root / 'worker-tests.log').open('w') as log:
        subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m', '--entrypoint', 'node',
            '-v', str(verify) + ':/verify:ro', '-w', '/verify/coding-agent-node', image,
            '--test', '--test-concurrency=4', *['test/' + p.name for p in sorted((node / 'test').glob('*.test.js'))]],
            stdout=log, stderr=subprocess.STDOUT, check=True)
    with (root / 'sandbox-tests.log').open('w') as log:
        subprocess.run(['python3', str(root / 'test-cline-sdk-sandbox.py'), '--source-dir', str(verify), '--node-image', image],
            stdout=log, stderr=subprocess.STDOUT, check=True)
    report = {'sourceCommit': args.source_commit, 'image': image, 'imageId': run('docker', 'image', 'inspect', '--format', '{{.Id}}', image),
        'baselineImageId': live['Image'], 'baselineContainerId': live['Id'], 'modulesBefore': before, 'modulesAfter': after,
        'archiveSha256': args.archive_sha256, 'sdk': version, 'isolatedGatesPassed': True, 'trafficSwitched': False}
    (root / 'candidate.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
