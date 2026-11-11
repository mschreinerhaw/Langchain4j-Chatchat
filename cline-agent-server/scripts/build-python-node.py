"""Build on the deployment host from pinned local images and verified assets.

Usage: python3 build-python-node.py --context /srv/.../context
The context contains cline-agent-server/, deploy/docker/python-runtime/, runtime/.
No credentials or model configuration belong in this context.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--context', type=pathlib.Path, required=True)
parser.add_argument('--python-base', default='sha256:3434f7630ff7c54a053c7385710e089d249e65d668699ab96f9603b6e1b27eef')
parser.add_argument('--node-base', default='sha256:ee3100eabd0862d0f60f0777d9884b5d06e50cfeb80e1fb166a22db70ffd793d')
parser.add_argument('--tag', default='runtime-os-coding-node:cline-4.1.26-python3.11')
args = parser.parse_args()
for ref in (args.python_base, args.node_base):
    if not (ref.startswith('sha256:') or '@sha256:' in ref):
        raise SystemExit('Base image must be immutable: ' + ref)
context = args.context.resolve()
manifest_path = context/'runtime/runtime-manifest.json'
manifest = json.loads(manifest_path.read_text())
assert manifest['sourceCommit'] == 'a459f1d04dea030833b558be645011ab739c463b'
for item in manifest['files']:
    path = (context/'runtime'/item['path']).resolve()
    if not path.is_relative_to(context/'runtime'):
        raise RuntimeError('Invalid runtime asset path')
    if path.stat().st_size != item['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
        raise RuntimeError('Runtime asset checksum mismatch: '+item['path'])
evidence = context.parent/'evidence'
evidence.mkdir(exist_ok=True)
attempt = evidence/('build-'+str(time.time_ns()))
attempt.mkdir()
started = time.time()
source_files={str(p.relative_to(context)):hashlib.sha256(p.read_bytes()).hexdigest()
    for directory in ['cline-agent-server','deploy/docker/python-runtime']
    for p in sorted((context/directory).rglob('*'))
    if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc'}
source_bytes=json.dumps(source_files,sort_keys=True,indent=2).encode()
(attempt/'source-files.json').write_bytes(source_bytes)
def build_ref(ref, role):
    # BuildKit interprets a bare sha256: ID as a Docker Hub repository name.
    # Resolve a local immutable ID into a content-addressed temporary local tag.
    image_id = json.loads(subprocess.check_output(['docker','image','inspect',ref]))[0]['Id']
    if ref.startswith('sha256:') and image_id != ref:
        raise RuntimeError('Local base identity mismatch')
    tag = 'runtime-os-build-'+role+':'+image_id.replace(':','-')
    subprocess.run(['docker','image','tag',image_id,tag],check=True)
    return tag
python_ref, node_ref = build_ref(args.python_base,'python'), build_ref(args.node_base,'node')
with (attempt/'build.log').open('w') as log:
    result = subprocess.run(['docker', 'build', '--network=none', '--pull=false',
        '--build-arg', 'PYTHON_BASE='+python_ref,
        '--build-arg', 'NODE_BASE='+node_ref,
        '-f', str(context/'cline-agent-server/docker/Dockerfile.python'),
        '-t', args.tag, str(context)], stdout=log, stderr=subprocess.STDOUT)
record = dict(tag=args.tag, pythonBase=args.python_base, nodeBase=args.node_base,
    officialSourceCommit=manifest['sourceCommit'], elapsedSeconds=time.time()-started,
    buildExitCode=result.returncode,
    runtimeManifestSha256=hashlib.sha256(manifest_path.read_bytes()).hexdigest())
record['sourceFilesManifestSha256']=hashlib.sha256(source_bytes).hexdigest()
if result.returncode == 0:
    image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', args.tag]))[0]
    record.update(imageId=image['Id'], repoDigests=image.get('RepoDigests', []))
    # An unpushed local image has an image ID, but no registry manifest digest.
(attempt/'build.json').write_text(json.dumps(record, indent=2))
(evidence/'build.json').write_text(json.dumps({**record,'attemptEvidence':str(attempt)}, indent=2))
print(json.dumps(record, indent=2))
raise SystemExit(result.returncode)
