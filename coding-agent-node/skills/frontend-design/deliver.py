"""Download preview evidence and select files for the existing Runtime Silo transport.

Run in the authorized task workspace. Credentials come from environment only.
Does not claim that Runtime has uploaded the selected files.
"""
import argparse
import hashlib
import io
import json
import os
import re
from pathlib import Path
import tarfile
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('html', type=Path, nargs='?')
    parser.add_argument('--query', help='Read a resource catalog API path through task transport')
    parser.add_argument('--output', default='frontend')
    args = parser.parse_args()
    root = Path.cwd().resolve()
    output = root / args.output
    if output.resolve() == root or not output.resolve().is_relative_to(root):
        raise ValueError('Output must be inside task workspace')
    transport_path = root / 'input/frontend-transport.json'
    transport = json.loads(transport_path.read_text()) if transport_path.exists() else {}
    base = (os.environ.get('HUB_URL') or transport['url']).rstrip('/')
    token = os.environ.get('HUB_TOKEN') or transport['token']

    def request(path, body=None):
        req = urllib.request.Request(base + path, data=body,
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=65) as response:
            data = response.read(256 * 1024 * 1024 + 1)
            if len(data) > 256 * 1024 * 1024:
                raise ValueError('Bundle exceeds limit')
            return data

    if args.query:
        if not re.fullmatch(r'/api/v1/(resources|themes|templates|components)(/[a-z0-9-]+)?(\?q=[^#]*)?', args.query):
            raise ValueError('Catalog query path required')
        print(request(args.query).decode('utf-8'))
        return
    if not args.html:
        parser.error('HTML path or --query required')
    html = args.html.read_text(encoding='utf-8')
    job = json.loads(request('/api/v1/preview', json.dumps({'html': html}).encode()))
    path = '/api/v1/preview/' + job['id']
    for _ in range(120):
        job = json.loads(request(path + '/result'))
        if job['status'] != 'running' and (job.get('bundle') or job.get('bundleError') or job.get('error')):
            break
        time.sleep(1)
    output.mkdir(parents=True, exist_ok=True)
    history_path = output / 'repair-history.json'
    history = json.loads(history_path.read_text()) if history_path.exists() else []
    history.append(job)
    history_path.write_text(json.dumps(history, indent=2), encoding='utf-8')
    (output / 'index.html').write_text(html, encoding='utf-8')
    if job.get('report', {}).get('htmlSha256') != hashlib.sha256(html.encode()).hexdigest():
        raise RuntimeError('Preview does not match submitted HTML')
    if not job.get('bundle'):
        raise RuntimeError('Evidence retained; bundle unavailable: ' + str(job.get('bundleError', job.get('error'))))
    with tarfile.open(fileobj=io.BytesIO(request(path + '/bundle')), mode='r:gz') as archive:
        total = 0
        for member in archive.getmembers():
            target = (output / member.name).resolve()
            total += member.size
            if (not target.is_relative_to(output.resolve()) or
                not (member.isfile() or member.isdir()) or total > 512 * 1024 * 1024):
                raise ValueError('Unsafe bundle')
        archive.extractall(output, filter='data')
    selection_path = root / 'delivery_manifest.json'
    selection = json.loads(selection_path.read_text()) if selection_path.exists() else {
        'schemaVersion': 'coding_node_delivery_selection.v1', 'artifacts': []}
    paths = {x['path']: x for x in selection['artifacts']}
    for file in output.rglob('*'):
        if file.is_file():
            relative = file.relative_to(root).as_posix()
            paths[relative] = {'path': relative, 'type': 'SOURCE' if file.suffix == '.html' else 'AUXILIARY'}
    selection['artifacts'] = list(paths.values())
    if len(paths) > 1000:
        raise ValueError('Runtime artifact count exceeded; select a task-specific dependency bundle')
    selection_path.write_text(json.dumps(selection, indent=2), encoding='utf-8')
    print(json.dumps({'preview': job['status'], 'jobId': job['id'], 'selectedFiles': len(paths),
                      'silo': 'PENDING_RUNTIME_ARCHIVE'}))
    if job['status'] != 'passed':
        raise SystemExit(2)


if __name__ == '__main__':
    main()
