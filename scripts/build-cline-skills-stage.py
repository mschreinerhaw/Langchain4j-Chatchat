"""Build the hash-pinned Skill worker and run its complete tests offline before deployment."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile


def run(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except subprocess.CalledProcessError as failure:
        print(failure.output, flush=True)
        raise


def main():
    root = Path(sys.argv[1]).resolve()
    archive = root / 'worker.tar.gz'
    expected = sys.argv[2]
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected
    worker = json.loads(run('docker', 'inspect', 'runtime-os-app-stage-coding-agent-1'))[0]
    image = sys.argv[3] if len(sys.argv)>3 else 'runtime-os-coding-agent:agent-skills-20261030-r1'
    assert image.startswith('runtime-os-coding-agent:agent-skills-') and all(c.isalnum() or c in '-:.' for c in image)
    context = root / 'worker-build'
    context.mkdir(exist_ok=True)
    with tarfile.open(archive) as source:
        for item in source.getmembers():
            path = Path(item.name)
            assert not path.is_absolute() and '..' not in path.parts and not item.issym() and not item.islnk()
            assert path.parts[0] in {'dist', 'src', 'test', 'fixtures', 'scripts', 'skills', 'package.json'}
        source.extractall(context, filter='data')
    baseline = 'runtime-os-coding-agent:skills-baseline-' + worker['Image'].split(':')[-1][:16]
    run('docker', 'tag', worker['Image'], baseline)
    (context / 'Dockerfile').write_text(
        'FROM ' + baseline + '\nUSER root\n'
        'COPY --chown=node:node dist/ /srv/coding-agent/dist/\n'
        'COPY --chown=root:root skills/ /srv/coding-agent/skills/\n'
        'RUN mkdir -p /srv/coding-agent/.cline && ln -sfnT ../skills /srv/coding-agent/.cline/skills && chmod -R a+rX /srv/coding-agent/skills\n'
        'USER node\n')
    run('docker', 'build', '--network', 'none', '-t', image, str(context))
    context.chmod(0o755)
    for path in context.rglob('*'):
        if path.is_symlink(): continue
        path.chmod(0o755 if path.is_dir() else 0o644)
    if not (context / 'node_modules').is_symlink():
        (context / 'node_modules').symlink_to('/srv/coding-agent/node_modules')
    with (root / 'worker-tests.log').open('w') as log:
        subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--read-only',
            '--tmpfs', '/tmp:rw,nosuid,nodev', '--entrypoint', 'node',
            '-v', str(context) + ':/verify:ro',
            '-v', str(root / 'python-project-adapter.py') + ':/chatchat-common/src/main/resources/runtime/coding/python-project-adapter.py:ro',
            '-w', '/verify', image,
            '--test', *['test/' + p.name for p in sorted((context / 'test').glob('*.test.js'))]],
            stdout=log, stderr=subprocess.STDOUT, check=True)
    report = {'image': image, 'imageId': run('docker', 'image', 'inspect', '-f', '{{.Id}}', image),
        'baselineImageId': worker['Image'], 'archiveSha256': expected,
        'offlineBuildAndTests': True, 'tests': (root / 'worker-tests.log').read_text().splitlines()[-9:]}
    (root / 'worker-build.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
