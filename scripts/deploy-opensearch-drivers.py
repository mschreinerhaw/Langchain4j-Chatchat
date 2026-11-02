"""Deploy scoped search CA trust, index discovery, and persistent external driver mounts."""
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
manifest = json.loads((release / 'patch-manifest.json').read_text())
assert hashlib.sha256((release / 'previous-app.jar').read_bytes()).hexdigest() == manifest['baselineSha256']
allowed = set(manifest['classes'])
assert all(name.startswith('com/chatchat/mcpserver/datacapability/connection/NativeDatabaseClient')
           or name.startswith('com/chatchat/mcpserver/datacapability/connection/NativeSearchTlsProperties')
           or name == 'com/chatchat/mcpserver/sql/datasource/NativeQueryDatasourceProbe.class' for name in allowed)
patches = {}
with tarfile.open(release / 'backend.tar.gz') as archive:
    for item in archive.getmembers():
        assert item.isfile() and item.name in allowed
        data = archive.extractfile(item).read()
        assert hashlib.sha256(data).hexdigest() == manifest['classes'][item.name]
        patches['BOOT-INF/classes/' + item.name] = data
assert len(patches) == len(allowed)
changed = []
with zipfile.ZipFile(release / 'previous-app.jar') as source, zipfile.ZipFile(context / 'app.jar', 'w') as target:
    deployed_dependencies = {n: hashlib.sha256(source.read(n)).hexdigest() for n in sorted(source.namelist()) if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')}
    assert deployed_dependencies == manifest['dependencies'], 'Deployment dependencies changed; rebuild the patch against this version'
    for entry in source.infolist():
        data = source.read(entry.filename)
        if entry.filename in patches:
            data = patches[entry.filename]
            changed.append(entry.filename)
        target.writestr(entry, data)
    for name, data in patches.items():
        if name not in changed:
            target.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
            changed.append(name)
assert len(changed) == len(allowed)

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
print('Building MCP image with scoped OpenSearch CA trust and index discovery', flush=True)
run('docker', 'build', '-t', tag, str(context))
size = int(run('docker', 'image', 'inspect', '--format', '{{.Size}}', tag))
assert size < 700 * 1024 * 1024, 'MCP compact image size budget'
# Reuse the existing host driver tree. Never copy drivers into an image.
env_path = root / 'config/deployment.env'
env_before = env_path.read_bytes()
(release / 'deployment-env-before').write_bytes(env_before)
driver_root = Path('/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/lib/drivers')
assert driver_root.is_dir() and len(list(driver_root.glob('*/*.jar'))) >= 8
tls_root = root / 'config/native-search-tls'
assert not tls_root.exists(), 'Preserve existing custom CA configuration; review before replacing it'
tls_root.mkdir()
run('docker', 'cp', 'opensearch-node:/usr/share/opensearch/config/root-ca.pem', str(tls_root / 'root-ca.pem'))
(tls_root / 'application.yml').write_text("""chatchat:
  mcp:
    native-search:
      tls:
        endpoints:
          - url: https://192.168.195.221:9200
            ca-certificate-file: /srv/app/config/native-search-tls/root-ca.pem
            server-name: node-0.example.com
""")
for file in tls_root.iterdir(): file.chmod(0o644)
env_text = env_before.decode()
assert 'JDBC_DRIVERS_DIR=' not in env_text and 'NATIVE_SEARCH_TLS_DIR=' not in env_text
env_path.write_text(env_text.rstrip() + '\nJDBC_DRIVERS_DIR=' + str(driver_root)
                    + '\nNATIVE_SEARCH_TLS_DIR=' + str(tls_root) + '\n')
for name in ['api', 'mcp']:
    service = config['services'][name]
    def driver_mount(mount):
        target = mount.get('target', '') if isinstance(mount, dict) else mount.split(':')[1]
        return target == '/srv/app/lib/drivers' or target.startswith('/srv/app/lib/drivers/')
    service['volumes'] = [mount for mount in service.get('volumes', []) if not driver_mount(mount)]
    service['volumes'].append({'type':'bind','source':'${JDBC_DRIVERS_DIR:?Set JDBC_DRIVERS_DIR}',
        'target':'/srv/app/lib/drivers','read_only':True,'bind':{'create_host_path':False}})
    service.setdefault('environment', {})['CHATCHAT_TOOLS_DATABASEQUERY_DRIVERLIBPATH'] = '/srv/app/lib/drivers'
mcp = config['services']['mcp']
mcp['volumes'].append({'type':'bind','source':'${NATIVE_SEARCH_TLS_DIR:?Set NATIVE_SEARCH_TLS_DIR}',
    'target':'/srv/app/config/native-search-tls','read_only':True,'bind':{'create_host_path':False}})
locations = mcp['environment'].get('SPRING_CONFIG_ADDITIONAL_LOCATION','optional:file:/srv/app/config/')
mcp['environment']['SPRING_CONFIG_ADDITIONAL_LOCATION'] = locations.rstrip(',') + ',optional:file:/srv/app/config/native-search-tls/application.yml'
mcp['image'] = tag
config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n')
try:
    compose('config', '--quiet')
    print('Replacing MCP/API containers with persistent host driver mounts', flush=True)
    compose('up', '-d', '--no-deps', 'mcp', 'api')
    ready()
except Exception:
    config_path.write_bytes(original_bytes)
    env_path.write_bytes(env_before)
    compose('up', '-d', '--no-deps', 'mcp', 'api')
    raise
verification = {
    'previousImage': previous_image, 'image': tag, 'imageBytes': size,
    'jarSha256': hashlib.sha256((context / 'app.jar').read_bytes()).hexdigest(),
    'baselineSha256': manifest['baselineSha256'], 'patchedClasses': changed,
    'testResults': manifest['tests'], 'driverRoot':str(driver_root), 'tlsConfig':str(tls_root),
    'ready': True, 'composeBackup': str(release / 'compose-before.json'),
}
(release / 'deployment.json').write_text(json.dumps(verification, indent=2) + '\n')
print(json.dumps(verification), flush=True)
