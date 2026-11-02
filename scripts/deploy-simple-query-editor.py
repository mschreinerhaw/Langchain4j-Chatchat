"""Run on the deployment host; replace only packaged MCP admin assets and retain rollback files."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile

release = Path(sys.argv[1]).resolve()
assert release.parent == Path('/opt/chatchat-releases')
root = Path('/opt/runtime-os-docker-stage')
container = 'runtime-os-app-stage-mcp-1'
tag = 'runtime-os-mcp:' + release.name


def run(*args):
    value = subprocess.run(args, capture_output=True, text=True)
    if value.returncode:
        (release / 'command-error.log').write_text(value.stdout + value.stderr)
        raise RuntimeError('Failed command: ' + args[0])
    return value.stdout.strip()


def compose(*args):
    return run('docker', 'compose', '--project-name', project_name, '--env-file',
               str(root / 'config/deployment.env'), '-f', str(root / 'compose.yaml'), *args)


def ready():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 240
    stable = None
    while time.monotonic() < deadline:
        available = False
        try:
            with opener.open('http://127.0.0.1:18190/api/v1/admin/auth/me', timeout=3) as response:
                available = response.status == 200
        except urllib.error.HTTPError as error:
            available = error.code in (401, 403)
        except OSError:
            pass
        if available:
            stable = stable or time.monotonic()
            if time.monotonic() - stable > 8:
                return
        else:
            stable = None
        time.sleep(2)
    raise RuntimeError('MCP readiness check timed out')


release.mkdir(exist_ok=True)
config_path = root / 'compose.yaml'
original_bytes = config_path.read_bytes()
config = json.loads(original_bytes)
before = json.loads(run('docker', 'inspect', container))[0]
project_name = before['Config']['Labels']['com.docker.compose.project']
assert before['Config']['Image'] == config['services']['mcp']['image']
(release / 'compose-before.json').write_bytes(original_bytes)
previous_image = before['Config']['Image']
context = release / 'image'
context.mkdir(exist_ok=True)
run('docker', 'cp', container + ':/srv/app/app.jar', str(release / 'previous-app.jar'))
run('docker', 'cp', container + ':/usr/local/bin/docker', str(context / 'docker'))
with tarfile.open(release / 'frontend.tar.gz') as archive:
    archive.extractall(release / 'frontend', filter='data')
assets = release / 'frontend'
assert (assets / 'index.html').is_file()
with zipfile.ZipFile(release / 'previous-app.jar') as source, zipfile.ZipFile(context / 'app.jar', 'w') as target:
    for entry in source.infolist():
        if not entry.filename.startswith('BOOT-INF/classes/static/admin/'):
            target.writestr(entry, source.read(entry.filename))
    for file in assets.rglob('*'):
        if file.is_file():
            target.write(file, 'BOOT-INF/classes/static/admin/' + file.relative_to(assets).as_posix(), compress_type=zipfile.ZIP_DEFLATED)

# Reuse the clean runtime from the existing compact image; copy the business JAR exactly once.
(context / 'Dockerfile').write_text('''FROM runtime-os-api-runtime:17-sqlglot-20261024
COPY --chmod=0755 docker /usr/local/bin/docker
WORKDIR /srv/app
RUN mkdir -p /srv/app/config /srv/app/data /srv/app/logs
COPY app.jar /srv/app/app.jar
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai
EXPOSE 8090 9091
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]
''')
print('Building MCP image with updated admin assets', flush=True)
run('docker', 'build', '-t', tag, str(context))
size = int(run('docker', 'image', 'inspect', '--format', '{{.Size}}', tag))
assert size < 700 * 1024 * 1024, 'MCP compact image size budget'
config['services']['mcp']['image'] = tag
config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n')
try:
    print('Replacing MCP container', flush=True)
    compose('up', '-d', '--no-deps', 'mcp')
    ready()
except Exception:
    config_path.write_bytes(original_bytes)
    compose('up', '-d', '--no-deps', 'mcp')
    raise
verification = {
    'previousImage': previous_image, 'image': tag, 'imageBytes': size,
    'jarSha256': hashlib.sha256((context / 'app.jar').read_bytes()).hexdigest(),
    'frontendSha256': hashlib.sha256((assets / 'index.html').read_bytes()).hexdigest(),
    'ready': True, 'composeBackup': str(release / 'compose-before.json'),
}
(release / 'deployment.json').write_text(json.dumps(verification, indent=2) + '\n')
print(json.dumps(verification), flush=True)
