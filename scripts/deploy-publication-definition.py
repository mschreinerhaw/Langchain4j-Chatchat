"""Update API frontend assets while retaining backend classes, dependencies and previous hashed assets."""
import hashlib
import json
import tarfile
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

release = Path(sys.argv[1]).resolve()
assert release.parent == Path('/opt/chatchat-releases')
root = Path('/opt/runtime-os-docker-stage')
name = 'runtime-os-app-stage-api-1'
tag = 'runtime-os-api:' + release.name


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True)
    if result.returncode:
        (release / 'command-error.log').write_text(result.stdout + result.stderr)
        raise RuntimeError('Command failed: ' + args[0])
    return result.stdout.strip()


def compose(*args):
    return run('docker', 'compose', '--project-name', project, '--env-file',
               str(root / 'config/deployment.env'), '-f', str(root / 'compose.yaml'), *args)


def ready():
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline, stable = time.monotonic() + 240, None
    while time.monotonic() < deadline:
        available = False
        try:
            with http.open('http://127.0.0.1:18180/api/v1/enterprise/auth/me', timeout=3) as response:
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
    raise RuntimeError('API readiness timeout')


before = json.loads(run('docker', 'inspect', name))[0]
project = before['Config']['Labels']['com.docker.compose.project']
config_path = root / 'compose.yaml'
original = config_path.read_bytes()
config = json.loads(original)
assert config['services']['api']['image'] == before['Config']['Image']
(release / 'compose-before.json').write_bytes(original)
baseline = release / 'previous-app.jar'
run('docker', 'cp', name + ':/srv/app/app.jar', str(baseline))
baseline_hash = hashlib.sha256(baseline.read_bytes()).hexdigest()
assert run('docker', 'exec', name, 'sha256sum', '/srv/app/app.jar').split()[0] == baseline_hash
manifest = json.loads((release / 'release-manifest.json').read_text())
assert baseline_hash == manifest['baselineSha256'], 'Running API changed; rebuild against the current deployment'
with tarfile.open(release / 'frontend.tar.gz') as archive:
    archive.extractall(release / 'frontend', filter='data')
assets = release / 'frontend'
files = {}
for path, fingerprint in manifest['frontend'].items():
    file = (assets / path).resolve()
    assert file.is_relative_to(assets.resolve()) and file.is_file()
    assert hashlib.sha256(file.read_bytes()).hexdigest() == fingerprint
    files['BOOT-INF/classes/static/' + path] = file
assert 'BOOT-INF/classes/static/index.html' in files
context = release / 'image'
context.mkdir(exist_ok=True)
with zipfile.ZipFile(baseline) as source, zipfile.ZipFile(context / 'app.jar', 'w') as target:
    for entry in source.infolist():
        file = files.pop(entry.filename, None)
        target.writestr(entry, file.read_bytes() if file else source.read(entry.filename))
    for name_in_jar, file in files.items():
        target.write(file, name_in_jar, compress_type=zipfile.ZIP_DEFLATED)
(context / 'Dockerfile').write_text('''FROM runtime-os-api-runtime:17-sqlglot-20261024
WORKDIR /srv/app
COPY app.jar /srv/app/app.jar
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]
''')
print('Building API image with visual tool definitions', flush=True)
run('docker', 'build', '-t', tag, str(context))
assert int(run('docker', 'image', 'inspect', '--format', '{{.Size}}', tag)) < 1200 * 1024 * 1024
config['services']['api']['image'] = tag
config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n')
try:
    print('Replacing API container', flush=True)
    compose('up', '-d', '--no-deps', 'api')
    ready()
except Exception:
    config_path.write_bytes(original)
    compose('up', '-d', '--no-deps', 'api')
    raise
report = {'image': tag, 'previousImage': before['Config']['Image'], 'frontendFiles': len(manifest['frontend']), 'testResults': manifest['tests'],
          'jarSha256': hashlib.sha256((context / 'app.jar').read_bytes()).hexdigest(),
          'baselineSha256': baseline_hash, 'ready': True}
(release / 'deployment.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report), flush=True)
