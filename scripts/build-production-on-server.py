"""Build a verified source snapshot on a Linux production host through its HTTP proxy."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--builder', default='production-source-20261118')
    parser.add_argument('--service', default='all', choices=['all','chatchat-runtime-news','chatchat-mcp-server','chatchat-api','cline-agent-server','frontend-resource-hub'])
    parser.add_argument('--proxy', default='http://192.168.195.249:18118')
    args = parser.parse_args()
    release = Path(args.release).resolve()
    source = release / 'source'
    manifest = json.loads((release / 'source-manifest.json').read_text())
    for name, digest in manifest['files'].items():
        path = (source / name).resolve()
        if not path.is_relative_to(source) or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Source digest mismatch: ' + name)
    proxy = urlsplit(args.proxy)
    if proxy.scheme != 'http' or not proxy.hostname or proxy.username:
        raise RuntimeError('Supply the authorized HTTP proxy without embedded credentials')
    private = release / 'private'
    private.mkdir(mode=0o700, exist_ok=True)
    settings = ET.Element('settings')
    proxies = ET.SubElement(settings, 'proxies')
    entry = ET.SubElement(proxies, 'proxy')
    for key, value in {'id': 'production-build', 'active': 'true', 'protocol': 'http',
                       'host': proxy.hostname, 'port': str(proxy.port or 80),
                       'nonProxyHosts': 'localhost|127.*'}.items():
        ET.SubElement(entry, key).text = value
    settings_file = private / 'build-maven-settings.xml'
    ET.ElementTree(settings).write(settings_file, encoding='utf-8', xml_declaration=True)
    settings_file.chmod(0o600)
    ca = next((p for p in [Path('/etc/pki/tls/cert.pem'), Path('/etc/ssl/certs/ca-certificates.crt')]
               if p.is_file()), None)
    if ca is None:
        raise RuntimeError('System CA bundle missing')
    tag = manifest.get('releaseTag', 'source-20261118-' + manifest['commit'][:9])
    if not __import__('re').fullmatch(r'[a-z0-9][a-z0-9_.-]{0,127}', tag):
        raise RuntimeError('Invalid release image tag')
    records = []
    modules = ['chatchat-runtime-news', 'chatchat-mcp-server', 'chatchat-api', 'cline-agent-server', 'frontend-resource-hub']
    logdir = release / 'logs' / (('parallel-' + args.service) if args.service != 'all' else '')
    logdir.mkdir(exist_ok=True)
    for module in modules if args.service == 'all' else [args.service]:
        dockerfile = module + ('/Dockerfile.production' if module == 'frontend-resource-hub' else '/Dockerfile')
        image = module + ':' + tag
        command = ['docker', 'buildx', 'build', '--builder', args.builder, '--load', '--progress=plain', '-f', dockerfile, '-t', image]
        for key in ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'NPM_HTTPS_PROXY']:
            command += ['--build-arg', key + '=' + args.proxy]
        command += ['--build-arg', 'NO_PROXY=localhost,127.0.0.1', '--secret',
                    'id=maven_settings,src=' + str(settings_file), '--secret',
                    'id=proxy_ca,src=' + str(ca), '.']
        started = time.time()
        logfile = logdir / (module + '-build.log')
        print('Building ' + image, flush=True)
        with logfile.open('w') as output:
            result = subprocess.run(command, cwd=source, env={**os.environ, 'DOCKER_BUILDKIT': '1', 'HTTP_PROXY': args.proxy, 'HTTPS_PROXY': args.proxy, 'http_proxy': args.proxy, 'https_proxy': args.proxy, 'NO_PROXY': 'localhost,127.0.0.1'},
                                    stdout=output, stderr=subprocess.STDOUT)
        record = {'image': image, 'dockerfile': dockerfile, 'exitCode': result.returncode,
                  'seconds': round(time.time() - started, 2), 'log': str(logfile)}
        if result.returncode == 0:
            record['imageId'] = subprocess.check_output(['docker', 'image', 'inspect', image,
                '--format', '{{.Id}}'], text=True).strip()
        records.append(record)
        (release / ('build-summary' + (('-' + args.service) if args.service != 'all' else '') + '.json')).write_text(json.dumps({'sourceCommit': manifest['commit'],
            'sourceFilesVerified': len(manifest['files']), 'proxy': args.proxy, 'builds': records}, indent=2))
        if result.returncode:
            print('Build failed; inspect ' + str(logfile), flush=True)
            return result.returncode
        print('Built ' + image, flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
