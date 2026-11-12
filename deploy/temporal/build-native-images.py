"""Source-only API and Native Host build; never activates or replaces services."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--source', type=pathlib.Path, required=True)
p.add_argument('--evidence', type=pathlib.Path, required=True)
p.add_argument('--commit', required=True)
p.add_argument('--tag', required=True)
p.add_argument('--coding-base', required=True, help='Verified immutable sha256 image ID')
p.add_argument('--maven-home', type=pathlib.Path, required=True)
a = p.parse_args()
if not re.fullmatch('[0-9a-f]{40}', a.commit):
    raise SystemExit('An exact committed source revision is required')
if not re.fullmatch('[A-Za-z0-9_.-]+', a.tag) or a.tag == 'latest':
    raise SystemExit('An explicit version tag is required')
if not re.fullmatch('sha256:[0-9a-f]{64}', a.coding_base):
    raise SystemExit('Use the inspected, verified Native Core/Python base image ID')
source = a.source.resolve()
evidence = a.evidence.resolve()
evidence.mkdir(parents=True, exist_ok=False)
files = []
for path in sorted(source.rglob('*')):
    relative = path.relative_to(source)
    if any(v in {'.git', 'target', 'node_modules', 'tools'} for v in relative.parts):
        continue
    if path.is_symlink():
        raise SystemExit('Source symlinks require explicit review: ' + str(relative))
    if path.is_file():
        files.append({'path': relative.as_posix(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
(evidence / 'source-manifest.json').write_text(json.dumps({'commit': a.commit, 'files': files}, indent=2))
tools = source / 'tools'
tools.mkdir(exist_ok=True)
shutil.copytree(a.maven_home, tools / 'maven', dirs_exist_ok=True)
launcher = tools / 'maven/bin/mvn'
launcher.write_text('\n'.join(v for v in launcher.read_text().splitlines()
                              if not v.startswith('export JAVA_HOME=')) + '\n')
(tools / 'maven/conf/settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>')
shutil.copy2('/usr/bin/docker', tools / 'docker')
refs = {
 'NODE_BUILD_IMAGE': 'node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9',
 'JAVA_BUILD_IMAGE': 'eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717',
 'JAVA_RUNTIME_IMAGE': 'eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717',
 'CODING_BASE_IMAGE': a.coding_base}
resolved = {}
for name, ref in refs.items():
    info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', ref]))[0]
    local = 'runtime-native-build-base:' + info['Id'].split(':')[1][:20]
    subprocess.run(['docker', 'tag', info['Id'], local], check=True)
    resolved[name] = dict(imageId=info['Id'], repoDigests=info.get('RepoDigests', []), local=local)
record = dict(commit=a.commit, sourceManifestSha256=hashlib.sha256((evidence / 'source-manifest.json').read_bytes()).hexdigest(),
              bases=resolved, images={})
for role in ['api', 'coding-node']:
    tag = 'runtime-os-' + role + ':' + a.tag
    if role == 'api':
        argv = ['-f', str(source / 'deploy/docker/source-stack/Dockerfile'), '--target', 'api']
        buildargs = {key: value['local'] for key, value in resolved.items()}
    else:
        argv = ['-f', str(source / 'cline-agent-server/docker/Dockerfile.temporal')]
        buildargs = {'CODING_NODE_IMAGE': resolved['CODING_BASE_IMAGE']['local']}
    buildargs['GIT_COMMIT'] = a.commit
    args = [v for key, value in buildargs.items() for v in ['--build-arg', key+'='+value]]
    with (evidence / ('build-'+role+'.log')).open('wb') as log:
        completed = subprocess.run(['docker', 'build', '--pull=false', '--network=host',
            '--progress=plain', *argv, *args, '--label', 'org.opencontainers.image.revision='+a.commit,
            '-t', tag, str(source)], stdout=log, stderr=subprocess.STDOUT)
    if completed.returncode:
        raise SystemExit('Source build failed: '+role+'; log retained')
    info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', tag]))[0]
    record['images'][role] = {key: info.get(key) for key in ['Id', 'RepoTags', 'RepoDigests', 'Size', 'Architecture']}
    (evidence / 'build-result.json').write_text(json.dumps(record, indent=2))
    print(role, info['Id'], flush=True)
