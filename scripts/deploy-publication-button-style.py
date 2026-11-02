"""Patch only SQL publication button styles in the currently deployed API frontend."""
import hashlib
import json
import re
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
baseline_hash = hashlib.sha256(baseline.read_bytes()).hexdigest()
assert run('docker', 'exec', name, 'sha256sum', '/srv/app/app.jar').split()[0] == baseline_hash
css = (release / 'primary-button.css').read_bytes()
asset = 'assets/sql-publication-primary-' + hashlib.sha256(css).hexdigest()[:12] + '.css'
context = release / 'image'
context.mkdir(exist_ok=True)
index = 'BOOT-INF/classes/static/index.html'
with zipfile.ZipFile(baseline) as source, zipfile.ZipFile(context / 'app.jar', 'w') as target:
    for entry in source.infolist():
        if entry.filename.startswith('BOOT-INF/classes/static/assets/sql-publication-primary-'):
            continue
        data = source.read(entry.filename)
        if entry.filename == index:
            html = data.decode('utf-8')
            link = '<link rel="stylesheet" href="/' + asset + '" data-publication-button-fix>'
            if 'data-publication-button-fix' in html:
                html = re.sub(r'<link\b[^>]*data-publication-button-fix[^>]*>', link, html)
            else:
                html = html.replace('</head>', link + '\n</head>')
            data = html.encode('utf-8')
        target.writestr(entry, data)
    target.writestr('BOOT-INF/classes/static/' + asset, css, compress_type=zipfile.ZIP_DEFLATED)
(context / 'Dockerfile').write_text('''FROM runtime-os-api-runtime:17-sqlglot-20261024
WORKDIR /srv/app
COPY app.jar /srv/app/app.jar
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]
''')
print('Building API image with publication button stylesheet', flush=True)
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
report = {'image': tag, 'previousImage': before['Config']['Image'], 'asset': asset,
          'jarSha256': hashlib.sha256((context / 'app.jar').read_bytes()).hexdigest(),
          'baselineSha256': baseline_hash, 'ready': True}
(release / 'deployment.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report), flush=True)
