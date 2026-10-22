"""Export a checked offline release without live configuration, keys or data."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024): value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    args = parser.parse_args()
    root, destination = args.release_dir.resolve(), args.output_dir.resolve()
    manifest = json.loads((root / 'release-manifest.json').read_text())
    version = manifest['version']
    if json.loads((root / 'evidence/acceptance.json').read_text()).get('status') != 'PASSED':
        raise RuntimeError('Release acceptance must pass before packaging')
    images = ['runtime-os-' + s + ':' + version for s in ('api', 'mcp', 'news', 'coding-agent')]
    images += ['runtime-os-api-runtime:' + version, 'runtime-os-java-base:temurin17-jammy-20261022',
               'nginx:1.27.5-alpine', 'chatchat-python-runtime:3.11-v1.1',
               'chatchat-python-runtime:3.11-v1.2-cjk-20261021']
    records = []
    for image in images:
        data = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image], text=True))[0]
        records.append({'tag': image, 'imageId': data['Id'], 'sizeBytes': data['Size']})
    manifest['images'] = records
    manifest['packaging'] = {'platform': 'linux/amd64', 'containsCredentials': False,
        'containsUserData': False, 'containsJarCopies': False,
        'infrastructure': 'PostgreSQL, OpenSearch, Redis and object storage are external prerequisites'}
    (root / 'release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    archive = root / 'images.tar.gz'
    temporary = root / 'images.tar.gz.partial'
    with temporary.open('wb') as output, gzip.GzipFile(fileobj=output, mode='wb', compresslevel=1, mtime=0) as compressed:
        process = subprocess.Popen(['docker', 'save', *images], stdout=subprocess.PIPE)
        try:
            while chunk := process.stdout.read(1024 * 1024): compressed.write(chunk)
            if process.wait() != 0: raise RuntimeError('Docker image export failed')
        finally: process.stdout.close()
    temporary.replace(archive)
    checksums = {'images.tar.gz': digest(archive), 'release-manifest.json': digest(root / 'release-manifest.json')}
    (root / 'SHA256SUMS').write_text(''.join(h + '  ' + name + '\n' for name, h in checksums.items()))
    included = [archive, root / 'SHA256SUMS', root / 'release-manifest.json', root / 'RELEASE_VERSION']
    for folder in ('deploy/docker/application', 'docs', 'database/init/postgresql'):
        included += [p for p in (root / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
    included += [root / 'scripts' / name for name in ('release-product.py', 'verify-product-release.py',
                  'package-product-release.py', 'application_image_build.py', 'jar-delta.py')]
    for name in ('acceptance.json', 'build.json', 'deployment.json', 'cleanup.json', 'online-verification.json', 'business-acceptance.json', 'base-provenance.json'):
        p = root / 'evidence' / name
        if p.is_file(): included.append(p)
    # Every public file is explicitly allowlisted. private-backup, live Compose,
    # raw answers, .env files and the JAR staging directories are never visited.
    destination.mkdir(parents=True, exist_ok=True)
    bundle = destination / ('chatchat-' + version + '-linux-amd64.tar.gz')
    with tarfile.open(bundle.with_suffix(bundle.suffix + '.partial'), 'w:gz', compresslevel=1) as package:
        for item in included:
            relative = item.relative_to(root).as_posix()
            if any(part.startswith('.env') for part in item.parts) or item.suffix in ('.key', '.pem'):
                raise RuntimeError('Unexpected sensitive package filename')
            info = package.gettarinfo(str(item), arcname='chatchat-' + version + '/' + relative)
            info.uid = info.gid = 0; info.uname = info.gname = 'root'
            with item.open('rb') as stream: package.addfile(info, stream)
    bundle.with_suffix(bundle.suffix + '.partial').replace(bundle)
    checksum = digest(bundle)
    bundle.with_suffix(bundle.suffix + '.sha256').write_text(checksum + '  ' + bundle.name + '\n')
    report = {'version': version, 'bundle': str(bundle), 'bundleBytes': bundle.stat().st_size,
              'bundleSha256': checksum, 'imagesArchiveBytes': archive.stat().st_size, 'images': records,
              'containsPrivateBackup': False, 'containsUserData': False}
    (root / 'evidence/package.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__': main()
