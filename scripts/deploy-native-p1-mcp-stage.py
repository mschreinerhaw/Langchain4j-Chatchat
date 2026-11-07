"""Build a reviewed MCP overlay and upgrade only the existing isolated acceptance API/Worker.

Run on the stage host with OVERLAY_SHA256. Retains original containers, all task
data and the unmodified MCP/Sandbox. Credentials stay in private files/process memory.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import urllib.error
import zipfile
import tarfile

os.umask(0o077)
ROOT = Path('/var/tmp/runtime-native-coding-p1-20261107')
API = 'runtime-native-acceptance-api'
WORKER = 'runtime-native-acceptance-worker'
EXPECTED_API = 'sha256:21beb4fb86e09d648f868b0740b07877ebf3670cf9ef54911b3fd7ec04a967db'
EXPECTED_WORKER = 'sha256:1b8e2925cb99d75f50b9494f6ee029b7fdfb2ccec50aa2b881f44eba6bd89d86'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(args, **kwargs):
    p = subprocess.run(args, capture_output=True, **kwargs)
    if p.returncode:
        raise RuntimeError('Stage operation failed: ' + args[0] + ' stderr-sha256=' + sha(p.stderr))
    return p.stdout


def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]


def rewrite(original, replacements):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(output, 'w') as target:
        names = set(source.namelist())
        for item in source.infolist():
            target.writestr(item, replacements.get(item.filename, source.read(item)))
        for name in replacements.keys() - names:
            target.writestr(name, replacements[name], compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(original)) as before, zipfile.ZipFile(output) as after:
        for name in before.namelist():
            if name not in replacements:
                assert before.read(name) == after.read(name), 'Unreviewed archive change'
        for name, data in replacements.items():
            assert after.read(name) == data
    return output.getvalue()


def main():
    attempt = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    assert 1 <= attempt <= 20
    archive = ROOT / ('overlay.tgz' if attempt == 1 else 'overlay-r' + str(attempt) + '.tgz')
    assert sha(archive.read_bytes()) == sys.argv[1]
    api, worker = inspect(API), inspect(WORKER)
    if attempt == 1:
        assert api['Image'] == EXPECTED_API and worker['Image'] == EXPECTED_WORKER
        bases = {'api': api, 'worker': worker}
    else:
        candidates = [json.loads(p.read_text()) for p in ROOT.glob('mcp-deployment*-public.json')]
        matches = [value for value in candidates if value.get('images', {}).get('api') == api['Image']]
        assert len(matches) == 1
        previous = matches[0]
        assert previous.get('apiReady') or not api['State']['Running']
        assert worker['Image'] == previous['images']['worker']
        bases = {'api': inspect(API + '-before-p1-mcp'), 'worker': inspect(WORKER + '-before-p1-mcp')}
        assert bases['api']['Image'] == EXPECTED_API and bases['worker']['Image'] == EXPECTED_WORKER
        if not api['State']['Running']:
            logs = subprocess.run(['docker', 'logs', api['Id']], capture_output=True)
            raw = logs.stdout + logs.stderr
            (ROOT / 'api-startup-r1-private.log').write_bytes(raw)
            write_failure = {'status': 'FAILED_BEFORE_TASK_ADMISSION', 'reason': 'AMBIGUOUS_MCP_DIRECTORY_BEAN',
                'apiImage': api['Image'], 'exitCode': api['State']['ExitCode'], 'rawLogSha256': sha(raw),
                'taskSubmitted': (ROOT / 'normal-r1' / 'submit.json').exists()}
            assert not write_failure['taskSubmitted']
            (ROOT / 'api-startup-r1-public.json').write_text(json.dumps(write_failure, indent=2) + '\n')
    (ROOT / ('before-private.json' if attempt == 1 else 'before-r' + str(attempt) + '-private.json')).write_text(json.dumps({'api': api, 'worker': worker}))
    env = dict(v.split('=', 1) for v in api['Config']['Env'] if '=' in v)
    settings = json.loads(env['SPRING_APPLICATION_JSON'])
    secret = settings['chatchat.coding.cline.shared-secret']

    def ready():
        if not inspect(API)['State']['Running']:
            # Docker removes the exited API container's published ports. Its Worker
            # still owns that network namespace; query it without publishing a new port.
            script = ("const chunks=[];for await(const c of process.stdin)chunks.push(c);"
                "const {secret}=JSON.parse(Buffer.concat(chunks));"
                "const r=await fetch('http://127.0.0.1:3100/readyz',{headers:{authorization:'Bearer '+secret}});"
                "if(!r.ok)throw Error('Worker readiness rejected');console.log(JSON.stringify(await r.json()));")
            return json.loads(run(['docker', 'exec', '-i', WORKER, 'node', '--input-type=module', '-e', script],
                input=json.dumps({'secret': secret}).encode()))
        q = urllib.request.Request('http://127.0.0.1:18291/readyz', headers={'Authorization': 'Bearer ' + secret})
        with urllib.request.urlopen(q, timeout=10) as r:
            return json.load(r)

    assert ready()['activeSessions'] == 0
    assert not run(['docker', 'ps', '-q', '--filter', 'ancestor=sha256:a3a279fc391bda1bb5aefb62acd8ee56200215ce2905cfb83d189398c33c5828']).strip()
    context = ROOT / ('mcp-build' if attempt == 1 else 'mcp-build-r' + str(attempt))
    context.mkdir(exist_ok=False)
    with tarfile.open(archive) as bundle:
        for entry in bundle:
            path = Path(entry.name)
            assert not path.is_absolute() and '..' not in path.parts
            assert entry.isfile() or entry.isdir()
        bundle.extractall(context)  # Every member above is a relative regular file/directory; links are rejected.
    manifest = json.loads((context / 'manifest.json').read_text())
    replacements, modules = {}, {}
    for item in manifest['classes']:
        module, name = item['path'].split('/', 1)
        content = (context / 'classes' / item['path']).read_bytes()
        assert sha(content) == item['sha256']
        if module == 'chatchat-api':
            replacements['BOOT-INF/classes/' + name] = content
        else:
            assert module in ('chatchat-common', 'chatchat-agents')
            modules.setdefault(module, {})[name] = content
    baseline = ROOT / 'api-p0.jar'
    if attempt == 1:
        run(['docker', 'cp', API + ':/srv/app/app.jar', str(baseline)])
    else:
        assert sha(baseline.read_bytes()) == previous['baselineApiJarSha256']
    with zipfile.ZipFile(baseline) as source:
        for module, entries in modules.items():
            name = 'BOOT-INF/lib/' + module + '-1.0.0-SNAPSHOT.jar'
            replacements[name] = rewrite(source.read(name), entries)
    patched = rewrite(baseline.read_bytes(), replacements)
    (context / 'api-overlay.jar').write_bytes(patched)
    for item in manifest['dist']:
        assert sha((context / 'dist' / item['path']).read_bytes()) == item['sha256']
    for item in manifest['experiments']:
        assert item['path'] == 'p1-observed-bootstrap.mjs'
        assert sha((context / 'experiments' / item['path']).read_bytes()) == item['sha256']
    images = {}
    for kind, base in bases.items():
        base_tag = 'runtime-native-p1-' + kind + '-base:20261107-mcp'
        run(['docker', 'tag', base['Image'], base_tag])
        lines = ['FROM ' + base_tag]
        if kind == 'api':
            lines += ['COPY api-overlay.jar /srv/app/app.jar']
        else:
            lines += ['USER root', 'COPY --chown=1000:1000 dist /srv/coding-agent/dist',
                'COPY --chown=1000:1000 experiments /srv/coding-agent/experiments', 'USER 1000:1000',
                'CMD ["/srv/coding-agent/experiments/p1-observed-bootstrap.mjs"]']
        lines += ['LABEL org.opencontainers.image.revision="' + manifest['sourceCommit'] + '"', '']
        dockerfile = context / ('Dockerfile.' + kind)
        dockerfile.write_text('\n'.join(lines))
        tag = 'runtime-native-p1-' + kind + ':20261107-mcp-r' + str(attempt)
        p = subprocess.run(['docker', 'build', '-f', str(dockerfile), '-t', tag, str(context)], capture_output=True)
        suffix = '' if attempt == 1 else '-r' + str(attempt)
        (ROOT / ('build-' + kind + suffix + '.log')).write_bytes(p.stdout + p.stderr)
        assert p.returncode == 0, 'P1 image build failed'
        images[kind] = inspect(tag)['Id']
    assert ready()['activeSessions'] == 0
    for name in (WORKER, API):
        if inspect(name)['State']['Running']:
            run(['docker', 'stop', '-t', '15', name])
        backup = name + ('-before-p1-mcp' if attempt == 1 else '-p1-mcp-failed-r' + str(attempt - 1))
        run(['docker', 'rename', name, backup])
    args = ['docker', 'create', '--name', API, '--network', 'runtime-native-acceptance-20261107',
            '--add-host', 'host.docker.internal:host-gateway', '--memory', str(api['HostConfig']['Memory']), '--pids-limit', '512']
    for port, bindings in api['HostConfig']['PortBindings'].items():
        for binding in bindings:
            args += ['-p', binding['HostIp'] + ':' + binding['HostPort'] + ':' + port.split('/')[0]]
    for key in env:
        args += ['--env', key]
    for mount in api['Mounts']:
        args += ['-v', mount['Source'] + ':' + mount['Destination'] + ('' if mount['RW'] else ':ro')]
    aid = run(args + [images['api']], env={**os.environ, **env}).decode().strip()
    run(['docker', 'network', 'connect', 'runtime-os-app-stage_control', aid])
    run(['docker', 'start', aid])
    wid = run(['docker', 'create', '-i', '--name', WORKER, '--network', 'container:' + aid,
        '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '512m',
        '--pids-limit', '256', '--tmpfs', '/tmp:rw,nosuid,noexec,size=64m', images['worker']]).decode().strip()
    with (ROOT / 'worker-private.log').open('ab') as log:
        process = subprocess.Popen(['docker', 'start', '-i', wid], stdin=subprocess.PIPE,
            stdout=log, stderr=log, start_new_session=True)
        process.stdin.write(json.dumps({'secret': secret}).encode())
        process.stdin.close()
    for attempt in range(120):
        try:
            assert inspect(API)['State']['Running'], 'Acceptance API exited during startup'
            token = json.loads((Path('/var/tmp/runtime-native-coding-acceptance-20261107') / 'api-auth-private.json').read_text())['token']
            api_request = urllib.request.Request('http://127.0.0.1:18280/api/v1/enterprise/auth/me', headers={'Authorization': 'Bearer ' + token})
            try:
                with urllib.request.urlopen(api_request, timeout=5) as response:
                    assert json.load(response)['code'] == 200
                authenticated = True
            except urllib.error.HTTPError as denied:
                # An API restart invalidates old login sessions. A separate authenticated
                # byte/readiness verifier must complete after a fresh stage login.
                assert denied.code == 401 and json.load(denied)['code'] == 401
                authenticated = False
            state = ready()
            assert state['status'] == 'READY' and state['activeSessions'] == 0
            q = urllib.request.Request('http://127.0.0.1:18291/v1/input-capabilities', data=b'{}',
                headers={'Authorization': 'Bearer ' + secret, 'Content-Type': 'application/json'})
            with urllib.request.urlopen(q, timeout=10) as response:
                capabilities = json.load(response)
            assert capabilities['nativeMcpVersions'] == ['runtime_os.native_mcp.v1']
            break
        except Exception:
            if attempt == 119:
                raise
            time.sleep(1)
    proof = {'sourceCommit': manifest['sourceCommit'], 'archiveSha256': sys.argv[1], 'classes': manifest['classes'], 'apiReady': True, 'apiAuthenticatedRead': authenticated,
        'images': images, 'containers': {'api': aid, 'worker': wid}, 'ready': state, 'capabilities': capabilities,
        'apiJarSha256': sha(patched), 'baselineApiJarSha256': sha(baseline.read_bytes()),
        'settingsUnchanged': dict(v.split('=', 1) for v in inspect(API)['Config']['Env']) == env,
        'mcpImageUnchanged': inspect('runtime-native-acceptance-mcp')['Image'],
        'productionChanged': False, 'modelChanged': False, 'budgetChanged': False,
        'acceptance': 'NOT_RUN'}
    proof_name = 'mcp-deployment-public.json' if len(sys.argv) <= 2 else 'mcp-deployment-r' + sys.argv[2] + ('-public.json' if authenticated else '-pending-auth.json')
    (ROOT / proof_name).write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps({k: v for k, v in proof.items() if k != 'classes'}))


if __name__ == '__main__':
    main()
