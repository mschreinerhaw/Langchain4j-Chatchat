"""Prepare an offline worker candidate and run its isolated regression gates.

Only prepares a candidate image. Does not stop services or switch traffic.
The bundle uses repository-relative coding-agent-node/ and chatchat-common/ paths.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
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
    parser.add_argument('--profile', choices=['sdk-reuse', 'coding-progress', 'native-skills'], default='sdk-reuse')
    parser.add_argument('--image-tag')
    args = parser.parse_args()
    root = args.release_dir.resolve()
    if not (root / 'test-cline-sdk-sandbox.py').is_file():
        raise FileNotFoundError('Distribute test-cline-sdk-sandbox.py before preparing the worker candidate')
    archive = root / {'sdk-reuse': 'cline-sdk-reuse-worker.tar.gz', 'coding-progress': 'coding-progress-worker.tar.gz',
        'native-skills': 'native-skills-worker.tar.gz'}[args.profile]
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
    tag = args.image_tag or ('sdk-reuse' if args.profile == 'sdk-reuse' else 'coding-progress') + '-20261105-r1'
    if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}', tag):
        raise ValueError('Invalid candidate image tag')
    image = 'runtime-os-coding-agent:' + tag
    patched = root / 'patched'
    patched.mkdir(exist_ok=True)
    before, after = {}, {}
    modules_to_copy = MODULES if args.profile == 'sdk-reuse' else (
        'runner.js', 'coding-progress.js', 'tool-catalog.js', 'runtime-model.js', 'monitor.js', 'monitor-types.js', 'public/app.js',
        'skill-disclosure.js','request-context.js','server.js','sdk-adapter.js',
        '../skills/catalog.json', '../skills/python-data-analysis/SKILL.md',
        '../skills/python-data-analysis/references/financial-reporting.md')
    if args.profile == 'native-skills':
        modules_to_copy = tuple(str(p.relative_to(node / 'dist')) for p in sorted((node / 'dist').rglob('*')) if p.is_file())
        modules_to_copy += tuple('../skills/' + str(p.relative_to(node / 'skills')) for p in sorted((node / 'skills').rglob('*')) if p.is_file())
    for module in modules_to_copy:
        exists = subprocess.run(['docker', 'exec', live['Id'], 'test', '-e', '/srv/coding-agent/dist/' + module], check=False)
        if exists.returncode not in [0, 1]:
            raise RuntimeError('Cannot inspect baseline module')
        if exists.returncode == 0:
            before[module] = run('docker', 'exec', live['Id'], 'sha256sum', '/srv/coding-agent/dist/' + module).split()[0]
        content = (node / 'dist' / module).read_bytes()
        target = patched / 'dist' / module
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        after[module] = hashlib.sha256(content).hexdigest()
    native_cleanup = ('USER root\nRUN rm -f /srv/coding-agent/dist/skills.js /srv/coding-agent/dist/skills.d.ts '
        '/srv/coding-agent/dist/skill-disclosure.js /srv/coding-agent/dist/skill-disclosure.d.ts '
        '/srv/coding-agent/src/skills.js /srv/coding-agent/src/skill-disclosure.js '
        '&& if [ -L /srv/coding-agent/.cline/skills ]; then rm /srv/coding-agent/.cline/skills; fi\nUSER node\n') if args.profile == 'native-skills' else ''
    (root / 'Dockerfile').write_text('FROM ' + baseline + '\nCOPY --chown=node:node patched/ /srv/coding-agent/\n'
        + native_cleanup + 'ENV RUNTIME_CODING_BUILD=' + args.source_commit + '\n')
    (root / '.dockerignore').write_text('*\n!Dockerfile\n!patched\n!patched/**\n')
    with (root / 'image-build.log').open('w') as log:
        subprocess.run(['docker', 'build', '--network', 'none', '-t', image, str(root)],
            stdout=log, stderr=subprocess.STDOUT, check=True)
    version = json.loads(run('docker', 'run', '--rm', '--network', 'none', '--entrypoint', 'node', image, 'dist/sdk-packages.js'))
    native = None
    if args.profile == 'native-skills':
        probe = "import fs from 'node:fs';import {prepareNativeSkills} from './dist/native-skills.js';for(const p of ['dist/skills.js','dist/skill-disclosure.js'])if(fs.existsSync(p))throw Error('Retired loader remains');const r=await prepareNativeSkills({taskId:'image-gate',scope:{tenantId:'image',userId:'image',runId:'image'}},{},{sessionId:'image-gate'});try{if(r.records.length!==15)throw Error('Native discovery incomplete');console.log(JSON.stringify({discovery:'loadAgentPluginPackages',loader:'createUserInstructionConfigService',skillCount:r.records.length,tools:r.tools.map(t=>t.name),retiredLoadersAbsent:true}));}finally{await r.dispose();}"
        native = json.loads(run('docker', 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m',
            '--entrypoint', 'node', image, '--input-type=module', '-e', probe))
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
    if native is not None:
        report['nativeSkills'] = native
    (root / 'candidate.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
