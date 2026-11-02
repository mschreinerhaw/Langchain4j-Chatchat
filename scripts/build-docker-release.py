#!/usr/bin/env python3
"""Build a complete Linux/amd64 release from source and export an offline deployment bundle."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / 'deploy/docker/application'
APPLICATIONS = ('api', 'mcp', 'news')
MODULES = ('chatchat-api', 'chatchat-mcp-server', 'chatchat-runtime-news')
SENSITIVE = re.compile(r'(?i)(?:\.key$|(?:^|/)license\.dat$|(?:^|/)[^/]*(?:private|signing)[^/]*\.pem$|(?:^|/)\.env[^/]*$)')


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def executable(name):
    found = shutil.which(name + '.cmd' if os.name == 'nt' and name in ('npm', 'mvn') else name)
    if not found:
        raise RuntimeError('Required build tool missing: ' + name)
    return found


class Runner:
    def __init__(self, output):
        self.log = output / 'build.log'

    def run(self, argv, cwd=ROOT, capture=False, env=None):
        argv = [str(item) for item in argv]
        # Windows batch launchers are interpreted by cmd even with shell=False.
        if os.name == 'nt' and argv[0].lower().endswith(('.cmd', '.bat')):
            if any(re.search(r'[&|<>^%\r\n]', value) for value in argv):
                raise ValueError('Unsupported shell metacharacter in a Windows batch argument')
        print('+ ' + subprocess.list2cmdline(argv), flush=True)
        with self.log.open('a', encoding='utf-8') as log:
            log.write('\n+ ' + subprocess.list2cmdline(argv) + '\n'); log.flush()
            if capture:
                result = subprocess.run(argv, cwd=cwd, env=env, capture_output=True, text=True,
                                        encoding='utf-8', errors='replace')
                log.write(result.stdout + result.stderr)
                if result.returncode:
                    raise RuntimeError('Command failed (exit %s): %s; see %s' % (result.returncode, argv[0], self.log))
                return result.stdout.strip()
            process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
            lines = []
            try:
                for line in process.stdout:
                    log.write(line); log.flush()
                    if capture:
                        lines.append(line)
                    else:
                        print(line, end='', flush=True)
                status = process.wait()
            except BaseException:
                process.terminate(); process.wait()
                raise
            finally:
                process.stdout.close()
            if status:
                raise RuntimeError('Command failed (exit %s): %s; see %s' % (status, argv[0], self.log))
            return ''.join(lines).strip()


def verify_jar(path, static_prefix=None):
    """Check Boot layout, local HTML assets and nested first-party secret filenames."""
    def check_secrets(archive, location):
        for name in archive.namelist():
            if SENSITIVE.search(name):
                raise ValueError('Deployment secret file in JAR: ' + location + '/' + name)
            if re.search(r'(?:^|/)chatchat-[^/]+\.jar$', name):
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                    check_secrets(nested, location + '/' + name)
    with zipfile.ZipFile(path) as jar:
        if not any(name.startswith('BOOT-INF/lib/') for name in jar.namelist()):
            raise ValueError('Expected an executable Spring Boot JAR: ' + str(path))
        check_secrets(jar, Path(path).name)
        if static_prefix:
            html = jar.read(static_prefix + 'index.html').decode('utf-8')
            script_count = 0
            for tag in re.findall(r'<(?:script|link)\b[^>]*>', html, re.I):
                for url in re.findall(r'(?:src|href)\s*=\s*["\']([^"\']+)["\']', tag, re.I):
                    if re.match(r'^(?:[a-z][a-z0-9+.-]*:|//|#)', url, re.I):
                        continue
                    relative = url.split('?', 1)[0].split('#', 1)[0].lstrip('/')
                    relative = relative.removeprefix('./')
                    if not relative or '..' in PurePosixPath(relative).parts:
                        raise ValueError('Invalid frontend resource: ' + url)
                    name = static_prefix + relative
                    if name not in jar.namelist() or jar.getinfo(name).file_size == 0:
                        raise ValueError('Missing frontend resource in JAR: ' + name)
                    if re.match(r'<script\b', tag, re.I): script_count += 1
            if not script_count:
                raise ValueError('Frontend index has no packaged local JavaScript')


def validate_linux_cli(path):
    with Path(path).open('rb') as stream:
        header = stream.read(64)
    if len(header) < 64 or header[:6] != b'\x7fELF\x02\x01' or struct.unpack('<H', header[18:20])[0] != 62:
        raise ValueError('MCP requires a Linux amd64 ELF Docker CLI, not docker.exe: ' + str(path))


def image_names(tag, args):
    images = {
        'api': 'runtime-os-api:' + tag, 'mcp': 'runtime-os-mcp:' + tag,
        'news': 'runtime-os-news:' + tag, 'coding-agent': 'runtime-os-coding-agent:' + tag,
        'api-runtime': 'runtime-os-api-runtime:' + tag,
        'python': args.python_image or 'chatchat-python-runtime:3.11-' + tag,
        'gateway': args.nginx_image, 'postgresql': args.postgresql_image,
        'redis': args.redis_image, 'opensearch': args.opensearch_image,
        'temporal': args.temporal_image,
    }
    for index, image in enumerate(args.extra_image, 1):
        if image not in images.values(): images['extra-' + str(index)] = image
    return images


def clean_runtime(runner, image):
    history = runner.run(['docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', image], capture=True)
    if 'app.jar' in history:
        raise ValueError('Runtime base contains an application JAR; use a clean runtime image: ' + image)


def snapshot_source(runner, output):
    source = output / 'work/source'
    names = runner.run(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], capture=True).split('\0')
    source.mkdir(parents=True)
    deployment = runner.run(['git', 'ls-files', '-z', '--cached', '--',
                             'deploy/docker', 'deploy/temporal', 'database/init'], capture=True).split('\0')
    write_json(output / 'work/deployment-assets.json', list(filter(None, deployment)))
    fingerprint = hashlib.sha256()
    for name in sorted(set(filter(None, names))):
        origin = ROOT / name
        if origin.resolve().is_relative_to(output) or not origin.is_file(): continue
        if origin.is_symlink() or not origin.resolve().is_relative_to(ROOT):
            raise ValueError('Source input escapes the repository: ' + name)
        destination = source / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        data = origin.read_bytes()
        destination.write_bytes(data)
        fingerprint.update(name.encode('utf-8') + b'\0' + hashlib.sha256(data).digest())
    return source, fingerprint.hexdigest()


def prepare_sources(runner, output, args, source):
    if source.resolve() != (output / 'work/source').resolve() or source.resolve() == ROOT.resolve():
        raise ValueError('Source builds must run in the isolated release work/source directory')
    npm, maven = executable('npm'), executable('mvn')
    executable('java'); executable('javac')
    frontend = output / 'work/frontend'
    for name, directory in [('api', 'chatchat-api/web-app'), ('mcp', 'chatchat-mcp-server/web-app-mcp')]:
        cwd = source / directory
        runner.run([npm, 'ci', '--no-audit', '--no-fund'], cwd)
        runner.run([npm, 'run', 'check:vue-structure'], cwd)
        # Direct Vite invocation avoids MCP's postbuild hook rewriting tracked static sources.
        runner.run([executable('node'), cwd / 'node_modules/vite/bin/vite.js', 'build',
                    '--outDir', frontend / name], cwd)
    command = [maven, '-B', '-pl', ','.join(MODULES), '-am',
               '-Dfrontend.skip=true', '-Dfrontend.install.skip=true', '-Dcontainer.image=true',
               '-Dweb.static.directory=' + (frontend / 'api').as_posix(),
               '-Dmcp.web.static.directory=' + (frontend / 'mcp').as_posix()]
    if not args.run_tests: command.append('-Dmaven.test.skip=true')
    if args.maven_offline: command.append('-o')
    runner.run(command + ['clean', 'package'], cwd=source)
    ns = {'p': 'http://maven.apache.org/POM/4.0.0'}
    version = ET.parse(source / 'pom.xml').getroot().find('p:version', ns).text
    jars = {name: source / module / 'target' / (module + '-' + version + '.jar')
            for name, module in zip(APPLICATIONS, MODULES)}
    for name, jar in jars.items():
        verify_jar(jar, {'api': 'BOOT-INF/classes/static/', 'mcp': 'BOOT-INF/classes/static/admin/'}.get(name))
    return jars


def build_images(runner, output, args, images, revision, jars, source):
    templates = source / 'deploy/docker/application'
    runner.run(['docker', 'pull', '--platform', 'linux/amd64', args.java_image])
    clean_runtime(runner, args.java_image)
    for name in APPLICATIONS:
        context = output / 'work' / name
        context.mkdir(parents=True)
        shutil.copy2(jars[name], context / 'app.jar')
        template = {'api': 'Dockerfile.api', 'mcp': 'Dockerfile.mcp', 'news': 'Dockerfile'}[name]
        shutil.copy2(templates / template, context / 'Dockerfile')
        shutil.copy2(templates / '.dockerignore', context / '.dockerignore')
        if name == 'api':
            shutil.copy2(templates / 'sql-toolchain-requirements.txt', context / 'sql-toolchain-requirements.txt')
            runner.run(['docker', 'build', '--platform', 'linux/amd64', '--target', 'api-runtime',
                        '--build-arg', 'JAVA_RUNTIME_IMAGE=' + args.java_image, '-t', images['api-runtime'], context])
        if name == 'mcp':
            cli = context / 'docker'
            if args.docker_cli:
                shutil.copy2(args.docker_cli, cli)
            else:
                runner.run(['docker', 'pull', '--platform', 'linux/amd64', args.docker_cli_image])
                container = runner.run(['docker', 'create', '--platform', 'linux/amd64', args.docker_cli_image], capture=True)
                try:
                    runner.run(['docker', 'cp', container + ':/usr/local/bin/docker', cli])
                finally:
                    runner.run(['docker', 'rm', container])
            validate_linux_cli(cli)
            write_json(output / 'docker-cli.json', {'sha256': sha256(cli), 'source': str(args.docker_cli or args.docker_cli_image)})
        runner.run(['docker', 'build', '--platform', 'linux/amd64', '--label',
                    'org.opencontainers.image.revision=' + revision, '--build-arg', 'JAVA_RUNTIME_IMAGE=' + args.java_image,
                    '--build-arg', 'APP_PORT=' + ('8091' if name == 'news' else '8080'), '-t', images[name], context])
        record = inspect_image(runner, images[name])
        limit = (700 if name == 'mcp' else 1200) * 1024 * 1024
        if record['sizeBytes'] > limit: raise ValueError(name + ' image exceeds its release size budget')
        history = runner.run(['docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', images[name]], capture=True)
        if sum('COPY ' in line and 'app.jar' in line for line in history.splitlines()) != 1:
            raise ValueError('Application image must contain exactly one JAR COPY layer: ' + name)
    runner.run(['docker', 'build', '--platform', 'linux/amd64', '--build-arg', 'BUILD_REVISION=' + revision,
                '-t', images['coding-agent'], source / 'coding-agent-node'])
    if args.reuse_python_image:
        inspect_image(runner, images['python'])
    else:
        runner.run(['docker', 'build', '--platform', 'linux/amd64', '-t', images['python'], source / 'deploy/docker/python-runtime'])
    runner.run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--user', '10001:10001',
                '--tmpfs', '/tmp:size=256m,uid=10001,gid=10001', '--cap-drop', 'ALL',
                '--env', 'XDG_CACHE_HOME=/tmp/.cache',
                '--security-opt', 'no-new-privileges', '--entrypoint', 'python', images['python'],
                '-c', "import sys,numpy,pandas,matplotlib,runpy; assert sys.version_info[:2] == (3,11); "
                      "runpy.run_path('/opt/chatchat/verify-fonts.py',run_name='__main__')"])
    for name in ('gateway', 'postgresql', 'redis', 'opensearch', 'temporal'):
        runner.run(['docker', 'pull', '--platform', 'linux/amd64', images[name]])
    for role, image in images.items():
        if role.startswith('extra-'): runner.run(['docker', 'pull', '--platform', 'linux/amd64', image])
    return {name: inspect_image(runner, image) for name, image in images.items()}


def inspect_image(runner, image):
    value = json.loads(runner.run(['docker', 'image', 'inspect', image], capture=True))[0]
    if value['Os'] != 'linux' or value['Architecture'] != 'amd64':
        raise ValueError('Expected a linux/amd64 image: ' + image)
    return {'tag': image, 'imageId': value['Id'], 'sizeBytes': value['Size']}


def package_assets(runner, output, images, source):
    package = output / 'release'
    package.mkdir()
    assets = json.loads((output / 'work/deployment-assets.json').read_text(encoding='utf-8'))
    assets += ['deploy/docker/release.py', 'deploy/docker/LOCAL-BUILD.zh-CN.md']
    for relative in sorted(set(assets)):
        origin = source / relative
        path = PurePosixPath(relative)
        if path.is_absolute() or '..' in path.parts or SENSITIVE.search(relative) or path.suffix.lower() in ('.env', '.pem', '.p12', '.pfx', '.jks'):
            continue
        if origin.is_symlink(): raise ValueError('Deployment assets must not be symlinks: ' + relative)
        target = package / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        # Normalize executable/config text for Linux, even with a Windows CRLF checkout.
        target.write_bytes(origin.read_bytes().replace(b'\r\n', b'\n'))
    # New entrypoints must also work before their first Git commit.
    shutil.copy2(source / 'deploy/docker/release.py', package / 'deploy.py')
    shutil.copy2(source / 'deploy/docker/LOCAL-BUILD.zh-CN.md', package / 'README.zh-CN.md')
    archive = package / 'images.tar'
    runner.run(['docker', 'save', '-o', str(archive) + '.partial', *dict.fromkeys(images.values())])
    Path(str(archive) + '.partial').replace(archive)
    return package


def parser():
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument('--tag', help='Image release tag, defaults to the Git revision')
    value.add_argument('--output-dir', type=Path)
    value.add_argument('--docker-cli', type=Path, help='Optional trusted Linux/amd64 CLI; otherwise extracted from docker CLI image')
    value.add_argument('--docker-cli-image', default='docker:27.5.1-cli')
    value.add_argument('--java-image', default='eclipse-temurin:17-jre-jammy')
    value.add_argument('--python-image')
    value.add_argument('--reuse-python-image', action='store_true', help='Use the explicitly named prebuilt Python runtime')
    value.add_argument('--nginx-image', default='nginx:1.27.5-alpine')
    value.add_argument('--postgresql-image', default='postgres:16.9')
    value.add_argument('--redis-image', default='redis:7.4-alpine')
    value.add_argument('--opensearch-image', default='opensearchproject/opensearch:2.12.0')
    value.add_argument('--temporal-image', default='temporalio/auto-setup:1.29.7')
    value.add_argument('--extra-image', action='append', default=[], help='Additional offline image, e.g. mysql:8.4; repeat as needed')
    value.add_argument('--run-tests', action='store_true', help='Run Maven reactor tests in addition to the default packaging checks')
    value.add_argument('--maven-offline', action='store_true', help='Maven only: require cached Maven dependencies')
    value.add_argument('--plan', action='store_true', help='Print the build plan without building or creating files')
    return value


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'): stream.reconfigure(encoding='utf-8', errors='replace')
    args = parser().parse_args()
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    tag = args.tag or revision[:12]
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', tag): raise ValueError('Invalid Docker tag')
    if args.reuse_python_image and not args.python_image: raise ValueError('--reuse-python-image requires --python-image')
    if args.docker_cli: validate_linux_cli(args.docker_cli)
    images = image_names(tag, args)
    output = (args.output_dir or ROOT / 'dist/docker-release' / tag).resolve()
    clean_targets = [ROOT / 'target', *(path / 'target' for path in ROOT.iterdir() if path.is_dir() and (path / 'pom.xml').is_file())]
    if any(output.is_relative_to(path.resolve()) for path in clean_targets):
        raise ValueError('Output must be outside Maven target directories, which clean removes; use dist/docker-release')
    plan = {'tag': tag, 'revision': revision, 'output': str(output), 'platform': 'linux/amd64', 'images': images,
            'steps': ['preflight', 'isolated-source-snapshot', 'api-and-mcp-frontends', 'maven-reactor-clean-package', 'verify-jars',
                      'build-application-and-python-images', 'collect-infrastructure-images', 'export-offline-release'],
            'mavenTests': 'RUN' if args.run_tests else 'SKIPPED', 'deploysRunningServices': False}
    if args.plan:
        print(json.dumps(plan, ensure_ascii=False, indent=2)); return
    if output.exists(): raise ValueError('Output already exists; choose a new release directory: ' + str(output))
    output.mkdir(parents=True)
    runner = Runner(output)
    write_json(output / 'build-plan.json', plan)
    try:
        if runner.run([executable('docker'), 'info', '--format', '{{.OSType}}'], capture=True) != 'linux':
            raise ValueError('Switch Docker Desktop to Linux containers before building')
        runner.run(['docker', 'compose', 'version'])
        source, source_digest = snapshot_source(runner, output)
        jars = prepare_sources(runner, output, args, source)
        records = build_images(runner, output, args, images, revision, jars, source)
        package = package_assets(runner, output, images, source)
        manifest = {**plan, 'status': 'BUILT', 'builtAtUtc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                    'dirtySource': bool(runner.run(['git', 'status', '--porcelain', '--untracked-files=all'], capture=True)),
                    'images': records, 'sourceTreeSha256': source_digest,
                    'jars': {name: sha256(path) for name, path in jars.items()},
                    'businessAcceptance': 'NOT_RUN'}
        write_json(package / 'release-manifest.json', manifest)
        files = sorted(path for path in package.rglob('*') if path.is_file())
        (package / 'SHA256SUMS').write_text(''.join(sha256(path) + '  ' + path.relative_to(package).as_posix() + '\n' for path in files), encoding='utf-8')
        bundle = output / ('runtime-os-' + tag + '-linux-amd64.tar.gz')
        with tarfile.open(str(bundle) + '.partial', 'w:gz', compresslevel=1) as archive:
            archive.add(package, arcname='runtime-os-' + tag)
        Path(str(bundle) + '.partial').replace(bundle)
        bundle.with_suffix(bundle.suffix + '.sha256').write_text(sha256(bundle) + '  ' + bundle.name + '\n', encoding='utf-8')
        write_json(output / 'build-result.json', {'status': 'BUILT', 'bundle': str(bundle), 'bundleSha256': sha256(bundle)})
        print('\nOffline release ready: ' + str(bundle), flush=True)
    except BaseException as error:
        write_json(output / 'build-result.json', {'status': 'FAILED', 'error': str(error)})
        raise


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print('BUILD FAILED: ' + str(error), file=sys.stderr)
        sys.exit(1)
