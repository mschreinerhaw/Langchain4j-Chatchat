"""Prepare offline images from complete Maven JARs and the tested Worker build; do not stop services."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    root = args.release_dir.resolve()
    release = json.loads((root / 'source-artifacts.json').read_text())
    commit = release['sourceCommit']
    assert re.fullmatch(r'[0-9a-f]{40}', commit)
    tag = 'integrated-' + commit[:12] + '-20261104'
    manifest = {'image': 'runtime-os-api:' + tag, 'sourceCommit': commit,
                'sha256': release['artifacts']['api']['sha256'],
                'catalogReadinessAuthFile': 'catalog-readiness-private.json',
                'catalogReadinessExpectedTools': ['sql_schema_context_query', 'enterprise_metadata_search'],
                'financialLookupSchemaOptimization': False}
    for service, filename in [('api', 'app.jar'), ('mcp', 'mcp.jar'), ('news', 'news.jar')]:
        artifact = root / filename
        assert sha(artifact) == release['artifacts'][service]['sha256']
        with zipfile.ZipFile(artifact) as archive:
            assert archive.testzip() is None
            assert any(name.startswith('BOOT-INF/lib/') for name in archive.namelist())
        container = 'runtime-os-app-stage-' + service + '-1'
        original = json.loads(run('docker', 'inspect', container))[0]
        baseline = run('docker', 'exec', container, 'sha256sum', '/srv/app/app.jar').split()[0]
        if service == 'api':
            manifest['baselineSha256'] = baseline
            continue
        build = root / (service + '-build')
        build.mkdir(exist_ok=True)
        shutil.copy2(artifact, build / 'app.jar')
        image = 'runtime-os-' + service + ':' + tag
        (build / 'Dockerfile').write_text('FROM ' + original['Config']['Image'] + '\nCOPY app.jar /srv/app/app.jar\n')
        run('docker', 'build', '--network', 'none', '--label', 'org.opencontainers.image.revision=' + commit, '-t', image, str(build))
        manifest[service] = {'image': image, 'baselineImageId': original['Image'], 'baselineSha256': baseline,
            'sha256': sha(artifact), 'preparedImageId': run('docker', 'image', 'inspect', '-f', '{{.Id}}', image),
            'baselineApplicationReadiness': True}
    worker = json.loads(run('docker', 'inspect', 'runtime-os-app-stage-coding-agent-1'))[0]
    build = root / 'worker-build'
    build.mkdir(exist_ok=True)
    with zipfile.ZipFile(root / 'worker.zip') as archive:
        assert all('..' not in Path(name).parts and not name.startswith('/') for name in archive.namelist())
        archive.extractall(build)
    assert release['workerTestsPassed'] == 170
    for name, expected in release['workerModules'].items():
        assert sha(build / 'dist' / name) == expected
    image = 'runtime-os-coding-agent:' + tag
    (build / 'Dockerfile').write_text('FROM ' + worker['Config']['Image'] + '\nUSER root\n'
        'RUN rm -rf /srv/coding-agent/dist /srv/coding-agent/skills\n'
        'COPY --chown=node:node dist/ /srv/coding-agent/dist/\n'
        'COPY --chown=root:root skills/ /srv/coding-agent/skills/\n'
        'RUN chmod -R a+rX /srv/coding-agent/skills\nUSER node\n'
        'RUN node --check dist/server.js && node --check dist/runner.js && node dist/sdk-packages.js\n')
    run('docker', 'build', '--network', 'none', '--label', 'org.opencontainers.image.revision=' + commit, '-t', image, str(build))
    image_id = run('docker', 'image', 'inspect', '-f', '{{.Id}}', image)
    proof = {'imageId': image_id, 'offlineBuildAndTests': True,
             'testScope': '170 source tests and Linux module/SDK build smoke checks', 'sourceCommit': commit}
    (root / 'worker-build.json').write_text(json.dumps(proof, indent=2))
    manifest['worker'] = {'image': image, 'baselineImageId': worker['Image'],
                          'preparedImageId': image_id, 'modules': release['workerModules']}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps({'status': 'PREPARED', 'sourceCommit': commit,
                      'images': {key: manifest[key]['image'] for key in ['mcp', 'news', 'worker']}}), flush=True)


if __name__ == '__main__':
    main()
