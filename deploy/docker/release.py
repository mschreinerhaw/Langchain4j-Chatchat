#!/usr/bin/env python3
"""Offline release entry: verify/load images, create site templates, use the existing deployment engine."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): value.update(chunk)
    return value.hexdigest()


def verify(root):
    records = (root / 'SHA256SUMS').read_text(encoding='utf-8').splitlines()
    if not records: raise ValueError('Empty release checksum list')
    checked = set()
    for line in records:
        checksum, relative = line.split('  ', 1)
        target = (root / relative).resolve()
        if not target.is_relative_to(root.resolve()) or not target.is_file():
            raise ValueError('Invalid or missing release file: ' + relative)
        if len(checksum) != 64 or digest(target) != checksum:
            raise ValueError('Release checksum mismatch: ' + relative)
        checked.add(relative)
    if not {'images.tar', 'release-manifest.json', 'deploy.py'} <= checked:
        raise ValueError('Release checksum list is incomplete')


def load(root):
    verify(root)
    subprocess.run(['docker', 'load', '-i', str(root / 'images.tar')], check=True)
    manifest = json.loads((root / 'release-manifest.json').read_text(encoding='utf-8'))
    for record in manifest['images'].values():
        image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', record['tag']], text=True))[0]
        if image['Id'] != record['imageId'] or image['Os'] != 'linux' or image['Architecture'] != 'amd64':
            raise ValueError('Loaded image identity/platform differs from release: ' + record['tag'])
    print('All release files and loaded image identities verified.')


def initialize(root, install_root, mode):
    install = install_root.resolve()
    if os.name != 'posix' or not str(install).startswith('/'):
        raise ValueError('Deployment initialization requires a Linux target host')
    if re.search(r'[\r\n$#]', str(install)):
        raise ValueError('Installation root contains unsupported environment-file characters')
    release = json.loads((root / 'release-manifest.json').read_text(encoding='utf-8'))
    records = release['images']
    config = install / 'config'
    envfile, sitefile = config / 'deployment.env', config / 'site.json'
    if envfile.exists() or sitefile.exists():
        raise ValueError('Site files already exist; preserve and edit the existing configuration')
    if mode == 'full' and install.exists() and any(install.iterdir()):
        raise ValueError('Full installation requires an empty install root; use existing mode for existing data')
    config.mkdir(parents=True, mode=0o700, exist_ok=True)
    source = root / 'deploy/docker'
    filename = 'runtime-deployment.full.example.json' if mode == 'full' else 'runtime-deployment.example.json'
    site = json.loads((source / filename).read_text(encoding='utf-8').replace('/opt/runtime-os', install.as_posix()))
    site['application_compose'] = str(source / 'application/compose.yaml')
    site['compose_env_file'] = str(envfile)
    service_roles = {'node-proxy': 'gateway', 'gateway': 'gateway', 'api': 'api', 'mcp': 'mcp',
                     'news': 'news', 'coding-agent': 'coding-agent'}
    site['image_ids'] = {name: records[role]['imageId'] for name, role in service_roles.items()}
    for database in site['databases'].values():
        if database['engine'] == 'postgresql': database['client_image'] = records['postgresql']['tag']
    if mode == 'full':
        site['infrastructure_image_ids'] = {name: records[role]['imageId'] for name, role in {
            'postgresql': 'postgresql', 'redis': 'redis', 'opensearch': 'opensearch', 'opensearch-security-init': 'opensearch'}.items()}
        site['temporal']['image_ids'] = {'temporal': records['temporal']['imageId'],
                                       'temporal-postgresql': records['postgresql']['imageId']}
    replacements = {'API_IMAGE': records['api']['tag'], 'MCP_IMAGE': records['mcp']['tag'],
                    'NEWS_IMAGE': records['news']['tag'], 'NODE_IMAGE': records['coding-agent']['tag'],
                    'NODE_PROXY_IMAGE': records['gateway']['tag'], 'POSTGRESQL_IMAGE': records['postgresql']['tag'],
                    'REDIS_IMAGE': records['redis']['tag'], 'OPENSEARCH_IMAGE': records['opensearch']['tag'],
                    'TEMPORAL_IMAGE': records['temporal']['tag'], 'TEMPORAL_POSTGRESQL_IMAGE': records['postgresql']['tag']}
    template = (source / 'application/deployment.env.example').read_text(encoding='utf-8').replace('/opt/runtime-os', install.as_posix())
    lines = []
    for line in template.splitlines():
        key = line.split('=', 1)[0]
        if key in replacements: line = key + '=' + replacements.pop(key)
        lines.append(line)
    lines += [key + '=' + value for key, value in replacements.items()]
    lines.append('PYTHON_RUNTIME_IMAGE=' + records['python']['tag'])
    sitefile.write_text(json.dumps(site, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    envfile.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    envfile.chmod(0o600); sitefile.chmod(0o600)
    print('Site manifest: ' + str(sitefile))
    print('Environment template: ' + str(envfile))
    print('Configure host NIC, external driver paths, credentials, CA/truststore, License and models before deployment.')
    print('Python runtime to publish in MCP: ' + records['python']['tag'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['load', 'init', 'prepare', 'plan', 'check', 'up', 'verify', 'snapshot'])
    parser.add_argument('--root', type=Path, help='Absolute Linux installation root, used only by init')
    parser.add_argument('--mode', choices=['full', 'existing'], default='full')
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--services', nargs='+')
    parser.add_argument('--write-probe', action='store_true')
    args = parser.parse_args()
    if args.action == 'load': load(ROOT)
    elif args.action == 'init':
        if not args.root or not args.root.is_absolute(): parser.error('init requires --root /absolute/install/path')
        initialize(ROOT, args.root, args.mode)
    else:
        if not args.manifest or not args.output: parser.error('Deployment actions require --manifest and --output')
        argv = [sys.executable, ROOT / 'deploy/docker/runtime-deploy.py', args.action,
                '--manifest', args.manifest, '--output', args.output]
        if args.services: argv += ['--services', *args.services]
        if args.write_probe: argv.append('--write-probe')
        subprocess.run([str(item) for item in argv], check=True)


if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('DEPLOY FAILED: ' + str(error), file=sys.stderr); sys.exit(1)
