"""Prepare a tested resource-loop worker release for deploy-integrated-runtime.py.

The API JAR is copied byte-for-byte from the running release. No data or policy is changed.
Run on the deployment host with --release-dir pointing at the uploaded worker.zip/source.json.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    root = args.release_dir.resolve()
    source = json.loads((root / 'source.json').read_text())
    commit = source['sourceCommit']
    assert re.fullmatch(r'[0-9a-f]{40}', commit)
    assert sha(root / 'worker.zip') == source['workerZipSha256']
    assert source['tests'] == 172
    api = 'runtime-os-app-stage-api-1'
    worker = 'runtime-os-app-stage-coding-agent-1'
    baseline = json.loads(run('docker', 'inspect', worker))[0]
    build = root / 'worker-build'
    build.mkdir(exist_ok=True)
    with zipfile.ZipFile(root / 'worker.zip') as archive:
        assert all(not name.startswith('/') and '\\' not in name and '..' not in Path(name).parts for name in archive.namelist())
        archive.extractall(build)
    changed = []
    for name, expected in source['modules'].items():
        assert re.fullmatch(r'[A-Za-z0-9_-]+\.js', name)
        assert sha(build / 'dist' / name) == expected
        actual = run('docker', 'exec', worker, 'sha256sum', '/srv/coding-agent/dist/' + name).split()[0]
        if actual != expected:
            changed.append(name)
    assert changed == ['runner.js'], 'Unexpected worker changes: ' + str(changed)
    image = 'runtime-os-coding-agent:resource-completion-' + commit[:12]
    # Verify against the exact production Node/SDK dependencies, then ship compiled modules only.
    (build / 'Dockerfile').write_text(
        'FROM ' + baseline['Config']['Image'] + ' AS verify\nUSER root\n'
        'COPY src/ ./src/\nCOPY test/ ./test/\nCOPY fixtures/ ./fixtures/\nCOPY scripts/ ./scripts/\n'
        'COPY skills/ ./skills/\n'
        'COPY adapter.py /srv/chatchat-common/src/main/resources/runtime/coding/python-project-adapter.py\n'
        'RUN node --test test/*.test.js\n'
        'FROM ' + baseline['Config']['Image'] + ' AS runtime\nUSER root\n'
        'COPY --from=verify /srv/coding-agent/package.json ./package.json\n'
        'COPY --chown=node:node dist/ ./dist/\n'
        'RUN node --check dist/runner.js && node dist/sdk-packages.js\nUSER node\n')
    try:
        output = run('docker', 'build', '--network', 'none', '--label',
                     'org.opencontainers.image.revision=' + commit, '-t', image, str(build))
    except subprocess.CalledProcessError as error:
        (root / 'worker-build.log').write_text(error.output)
        raise
    (root / 'worker-build.log').write_text(output)
    image_id = run('docker', 'image', 'inspect', '-f', '{{.Id}}', image)
    run('docker', 'cp', api + ':/srv/app/app.jar', str(root / 'app.jar'))
    digest = sha(root / 'app.jar')
    assert digest == run('docker', 'exec', api, 'sha256sum', '/srv/app/app.jar').split()[0]
    manifest = {'sourceCommit': commit, 'image': 'runtime-os-api:resource-completion-' + commit[:12],
                'sha256': digest, 'baselineSha256': digest,
                'catalogReadinessAuthFile': 'catalog-readiness-private.json',
                'catalogReadinessExpectedTools': ['sql_schema_context_query', 'enterprise_metadata_search'],
                'worker': {'image': image, 'baselineImageId': baseline['Image'],
                           'preparedImageId': image_id, 'modules': source['modules']}}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    (root / 'worker-build.json').write_text(json.dumps({'imageId': image_id, 'offlineBuildAndTests': True,
        'sourceCommit': commit, 'tests': 172, 'changedModules': changed, 'apiJarUnchanged': True}, indent=2))
    credentials = json.loads((root / 'fixture.private.json').read_text())
    (root / 'catalog-readiness-private.json').write_text(json.dumps({key: credentials[key] for key in ['username', 'password']}))
    print(json.dumps({'status': 'PREPARED', 'sourceCommit': commit, 'changedModules': changed, 'apiJarUnchanged': True}))


if __name__ == '__main__':
    main()
