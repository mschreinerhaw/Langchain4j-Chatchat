"""Prepare complete API/MCP/News JARs and the offline-tested Cline image before stopping services."""
import hashlib
import json
from pathlib import Path
import re
import shutil
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
    assert re.fullmatch('[0-9a-f]{40}', commit)
    assert release['workerTestsPassed'] >= 183
    manifest = {'sourceCommit': commit, 'preserveRuntimeConfiguration': True,
                'backupNewsStorage': True, 'catalogReadinessAuthFile': 'catalog-readiness-private.json',
                'catalogReadinessExpectedTools': ['sql_schema_context_query', 'enterprise_metadata_search']}
    for service, filename in [('api', 'app.jar'), ('mcp', 'mcp.jar'), ('news', 'news.jar')]:
        artifact = root / filename
        assert sha(artifact) == release['artifacts'][service]['sha256']
        with zipfile.ZipFile(artifact) as archive:
            assert archive.testzip() is None
            assert any(p.startswith('BOOT-INF/lib/') for p in archive.namelist())
        name = 'runtime-os-app-stage-' + service + '-1'
        current = json.loads(run('docker', 'inspect', name))[0]
        baseline = run('docker', 'exec', name, 'sha256sum', '/srv/app/app.jar').split()[0]
        image = 'runtime-os-' + service + ':cline-internet-' + commit[:12]
        if service == 'api':
            manifest.update(image=image, sha256=sha(artifact), baselineSha256=baseline, apiUnchanged=sha(artifact)==baseline)
            continue
        if sha(artifact) == baseline and (service != 'news' or 'mcp' not in manifest):
            continue
        build = root / (service + '-build')
        build.mkdir(exist_ok=True)
        shutil.copy2(artifact, build / 'app.jar')
        (build / 'Dockerfile').write_text('FROM ' + current['Config']['Image'] + '\nCOPY app.jar /srv/app/app.jar\n')
        run('docker', 'build', '--network', 'none', '--label', 'org.opencontainers.image.revision=' + commit, '-t', image, str(build))
        manifest[service] = {'image': image, 'baselineImageId': current['Image'], 'baselineSha256': baseline,
                             'sha256': sha(artifact), 'preparedImageId': run('docker', 'image', 'inspect', '-f', '{{.Id}}', image),
                             'baselineApplicationReadiness': True}
    proof_file = root / 'worker-build.json'
    if proof_file.exists():
        previous = json.loads(proof_file.read_text())
        assert previous['archiveSha256'] == release['workerArchiveSha256'] == sha(root / 'worker.tar.gz')
        assert run('docker', 'image', 'inspect', '-f', '{{.Id}}', previous['image']) == previous['imageId']
    else:
        subprocess.run([sys.executable, str(root / 'build-cline-skills-stage.py'), str(root), release['workerArchiveSha256'],
                        'runtime-os-coding-agent:agent-skills-internet-' + commit[:12]], check=True)
    proof = json.loads((root / 'worker-build.json').read_text())
    assert proof['offlineBuildAndTests'] and proof['archiveSha256'] == release['workerArchiveSha256']
    for module, digest in release['workerModules'].items():
        assert sha(root / 'worker-build' / 'dist' / module) == digest
    current_worker = json.loads(run('docker', 'inspect', 'runtime-os-app-stage-coding-agent-1'))[0]
    if current_worker['Image'] != proof['imageId']:
        assert current_worker['Image'] == proof['baselineImageId']
        manifest['worker'] = {'image': proof['image'], 'baselineImageId': proof['baselineImageId'],
                              'preparedImageId': proof['imageId'], 'modules': release['workerModules']}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps({'status': 'PREPARED', 'sourceCommit': commit, 'services': ['api', 'mcp', 'news', 'worker']}))

if __name__ == '__main__':
    main()
