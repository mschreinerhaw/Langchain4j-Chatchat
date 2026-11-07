"""Read-only deployed-byte and authenticated readiness verification after a stage login."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

os.umask(0o077)
ROOT = Path('/var/tmp/runtime-native-coding-p1-20261107')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def main():
    attempt = int(sys.argv[1])
    assert 1 <= attempt <= 20
    context = ROOT / ('mcp-build' if attempt == 1 else 'mcp-build-r' + str(attempt))
    manifest = json.loads((context / 'manifest.json').read_text())
    api = json.loads(subprocess.check_output(['docker', 'inspect', 'runtime-native-acceptance-api']))[0]
    worker = json.loads(subprocess.check_output(['docker', 'inspect', 'runtime-native-acceptance-worker']))[0]
    assert api['State']['Running'] and worker['State']['Running']
    actual = subprocess.check_output(['docker', 'exec', api['Id'], 'sha256sum', '/srv/app/app.jar'], text=True).split()[0]
    assert actual == sha((context / 'api-overlay.jar').read_bytes())
    script = ("const chunks=[];for await(const c of process.stdin)chunks.push(c);"
        "const files=JSON.parse(Buffer.concat(chunks));const {readFile}=await import('node:fs/promises');"
        "const {createHash}=await import('node:crypto');"
        "for(const f of files){if(f.path.includes('..')||f.path.startsWith('/'))throw Error('Bad path');"
        "const b=await readFile('/srv/coding-agent/dist/'+f.path);"
        "if(createHash('sha256').update(b).digest('hex')!==f.sha256)throw Error('Worker byte mismatch');}"
        "console.log(files.length);")
    matched = int(subprocess.check_output(['docker', 'exec', '-i', worker['Id'], 'node', '--input-type=module', '-e', script],
        input=json.dumps(manifest['dist']).encode()))
    token = json.loads((ROOT / 'api-auth-private.json').read_text())['token']
    q = urllib.request.Request('http://127.0.0.1:18280/api/v1/enterprise/auth/me', headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(q, timeout=10) as response:
        assert json.load(response)['code'] == 200
    env = dict(v.split('=', 1) for v in api['Config']['Env'])
    secret = json.loads(env['SPRING_APPLICATION_JSON'])['chatchat.coding.cline.shared-secret']

    def read(path, body=None):
        request = urllib.request.Request('http://127.0.0.1:18291' + path, data=body,
            headers={'Authorization': 'Bearer ' + secret, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    ready = read('/readyz')
    capabilities = read('/v1/input-capabilities', b'{}')
    assert ready['status'] == 'READY' and ready['activeSessions'] == 0
    assert capabilities['nativeMcpVersions'] == ['runtime_os.native_mcp.v1']
    before = json.loads((ROOT / 'before-private.json').read_text())
    unchanged = dict(v.split('=', 1) for v in before['api']['Config']['Env']) == env
    assert unchanged
    archive = ROOT / ('overlay.tgz' if attempt == 1 else 'overlay-r' + str(attempt) + '.tgz')
    proof = {'sourceCommit': manifest['sourceCommit'], 'archiveSha256': sha(archive.read_bytes()),
        'images': {'api': api['Image'], 'worker': worker['Image']}, 'apiReady': True, 'apiAuthenticatedRead': True,
        'ready': ready, 'capabilities': capabilities, 'apiJarSha256': actual, 'classes': manifest['classes'],
        'baselineApiJarSha256': sha((ROOT / 'api-p0.jar').read_bytes()), 'workerDistFilesByteMatched': matched,
        'settingsUnchanged': unchanged, 'modelChanged': False, 'budgetChanged': False, 'productionChanged': False,
        'acceptance': 'NOT_RUN'}
    target = ROOT / ('mcp-deployment-r' + str(attempt) + '-public.json')
    assert not target.exists(), 'Preserve the previous deployment proof'
    target.write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps({key: value for key, value in proof.items() if key != 'classes'}))


if __name__ == '__main__':
    main()
