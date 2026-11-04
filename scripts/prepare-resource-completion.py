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


def prepare_mcp_recovery(root, source, commit):
    if not source.get('mcpRecoveryClassSha256'):
        return {}
    patch = root / 'SnapshotFinancialReadOperations.class'
    assert sha(patch) == source['mcpRecoveryClassSha256'] and source['javaRecoveryTests'] == 7
    content = patch.read_bytes()
    assert content[:4] == bytes.fromhex('cafebabe') and int.from_bytes(content[6:8], 'big') <= 61
    container = 'runtime-os-app-stage-mcp-1'
    before = json.loads(run('docker', 'inspect', container))[0]
    baseline = root / 'mcp-before.jar'
    run('docker', 'cp', container + ':/srv/app/app.jar', str(baseline))
    baseline_sha = sha(baseline)
    assert run('docker', 'exec', container, 'sha256sum', '/srv/app/app.jar').split()[0] == baseline_sha
    entry = 'BOOT-INF/classes/com/chatchat/mcpserver/market/SnapshotFinancialReadOperations.class'
    artifact = root / 'mcp.jar'
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(artifact, 'w') as new:
        assert entry in old.namelist()
        class_changed = old.read(entry) != content
        new.comment = old.comment
        for item in old.infolist():
            new.writestr(item, content if item.filename == entry else old.read(item))
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(artifact) as new:
        assert new.testzip() is None and new.namelist() == old.namelist()
        assert new.read(entry) == content
        assert all(old.read(name) == new.read(name) for name in old.namelist() if name != entry)
        unchanged = len(old.namelist()) - (1 if class_changed else 0)
    build = root / 'mcp-build'
    build.mkdir(exist_ok=True)
    import shutil
    shutil.copy2(artifact, build / 'app.jar')
    image = 'runtime-os-mcp:resource-completion-' + commit[:12]
    (build / 'Dockerfile').write_text('FROM ' + before['Config']['Image'] + '\nCOPY app.jar /srv/app/app.jar\n')
    run('docker', 'build', '--network', 'none', '--label', 'org.opencontainers.image.revision=' + commit,
        '-t', image, str(build))
    mcp = {'image': image, 'baselineImageId': before['Image'], 'baselineSha256': baseline_sha,
           'sha256': sha(artifact), 'preparedImageId': run('docker', 'image', 'inspect', '-f', '{{.Id}}', image),
           'baselineApplicationReadiness': True}
    # Stop the producer while MCP is replaced, retaining its exact existing image and JAR.
    news_container = 'runtime-os-app-stage-news-1'
    news_before = json.loads(run('docker', 'inspect', news_container))[0]
    run('docker', 'cp', news_container + ':/srv/app/app.jar', str(root / 'news.jar'))
    news_sha = sha(root / 'news.jar')
    assert run('docker', 'exec', news_container, 'sha256sum', '/srv/app/app.jar').split()[0] == news_sha
    news = {'image': news_before['Config']['Image'], 'baselineImageId': news_before['Image'],
            'preparedImageId': news_before['Image'], 'baselineSha256': news_sha, 'sha256': news_sha}
    (root / 'mcp-recovery-artifact.json').write_text(json.dumps({'changedEntries': [entry] if class_changed else [],
        'unchangedEntries': unchanged, 'classSha256': sha(patch), 'baselineSha256': baseline_sha,
        'sha256': mcp['sha256'], 'javaRecoveryTests': 7, 'sourceCommit': commit}, indent=2))
    return {'mcp': mcp, 'news': news, 'backupFinancialStorage': True}


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
    assert source['tests'] == 177
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
    assert set(changed) <= {'runner.js', 'knowledge-tools.js'}, 'Unexpected worker changes: ' + str(changed)
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
    manifest.update(prepare_mcp_recovery(root, source, commit))
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    (root / 'worker-build.json').write_text(json.dumps({'imageId': image_id, 'offlineBuildAndTests': True,
        'sourceCommit': commit, 'tests': 177, 'changedModules': changed, 'apiJarUnchanged': True}, indent=2))
    credentials = json.loads((root / 'fixture.private.json').read_text())
    (root / 'catalog-readiness-private.json').write_text(json.dumps({key: credentials[key] for key in ['username', 'password']}))
    print(json.dumps({'status': 'PREPARED', 'sourceCommit': commit, 'changedModules': changed, 'apiJarUnchanged': True}))


if __name__ == '__main__':
    main()
