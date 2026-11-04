"""Verify complete report release artifacts and test its worker offline without stopping services."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    root = Path(sys.argv[1]).resolve()
    release = json.loads((root / 'source-artifacts.json').read_text())
    commit = release['sourceCommit']
    assert re.fullmatch(r'[0-9a-f]{40}', commit)
    assert sha(root / 'app.jar') == release['apiSha256']
    with zipfile.ZipFile(root / 'app.jar') as archive:
        assert archive.testzip() is None
        assert any(name.startswith('BOOT-INF/lib/') for name in archive.namelist())
    worker_image = 'runtime-os-coding-agent:agent-skills-report-' + commit[:12]
    if not (root / 'worker-build.json').exists():
        subprocess.run([sys.executable, str(root / 'build-cline-skills-stage.py'), str(root),
                        release['workerArchiveSha256'], worker_image], check=True)
    proof = json.loads((root / 'worker-build.json').read_text())
    assert proof['archiveSha256'] == release['workerArchiveSha256'] == sha(root / 'worker.tar.gz')
    assert proof['offlineBuildAndTests'] is True
    worker_image = proof['image']
    assert run('docker', 'image', 'inspect', '-f', '{{.Id}}', worker_image) == proof['imageId']
    for name, expected in release['workerModules'].items():
        assert '/' not in name and name.endswith('.js')
        assert sha(root / 'worker-build' / 'dist' / name) == expected
    manifest = {
        'image': 'runtime-os-api:report-skills-' + commit[:12], 'sourceCommit': commit,
        'sha256': release['apiSha256'],
        'baselineSha256': run('docker', 'exec', 'runtime-os-app-stage-api-1',
                              'sha256sum', '/srv/app/app.jar').split()[0],
        'preserveRuntimeConfiguration': True,
        'catalogReadinessAuthFile': 'catalog-readiness-private.json',
        'catalogReadinessExpectedTools': ['sql_schema_context_query', 'enterprise_metadata_search'],
        'worker': {'image': worker_image, 'baselineImageId': proof['baselineImageId'],
                   'preparedImageId': proof['imageId'], 'modules': release['workerModules']}}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps({'status': 'PREPARED', 'sourceCommit': commit, 'worker': proof['imageId']}))


if __name__ == '__main__':
    main()
