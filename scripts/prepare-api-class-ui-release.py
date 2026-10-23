"""Overlay tested API classes and frontend assets on the exact running API artifact."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--api-baseline', required=True)
    parser.add_argument('--mcp-baseline', required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    root = args.release_dir.resolve()
    actual_mcp = subprocess.check_output(['docker', 'exec', 'runtime-os-app-stage-mcp-1', 'sha256sum', '/srv/app/app.jar'], text=True).split()[0]
    assert actual_mcp == args.mcp_baseline, 'Running MCP baseline changed'
    before = root / 'api-before.jar'
    with before.open('wb') as output:
        subprocess.run(['docker', 'exec', 'runtime-os-app-stage-api-1', 'cat', '/srv/app/app.jar'], stdout=output, check=True)
    assert digest(before) == args.api_baseline, 'Running API baseline changed'
    with zipfile.ZipFile(root / 'api-classes.zip') as classes:
        replacements = {name: classes.read(name) for name in classes.namelist() if not name.endswith('/')}
    assert replacements
    assert all(name.startswith('BOOT-INF/classes/com/chatchat/api/') and name.endswith('.class')
               and '\\' not in name and '..' not in name.split('/') and value.startswith(b'\xca\xfe\xba\xbe')
               for name, value in replacements.items())
    class_names = sorted(replacements)
    with zipfile.ZipFile(root / 'frontend.zip') as web:
        assert web.testzip() is None and 'index.html' in web.namelist()
        for name in web.namelist():
            assert '\\' not in name and not name.startswith('/') and '..' not in name.split('/')
            if not name.endswith('/'):
                replacements['BOOT-INF/classes/static/' + name] = web.read(name)
    candidate = root / 'api.jar'
    with zipfile.ZipFile(before) as source, zipfile.ZipFile(candidate, 'w', allowZip64=True) as result:
        result.comment = source.comment
        for item in source.infolist():
            if item.filename not in replacements and not item.filename.startswith('BOOT-INF/classes/static/'):
                result.writestr(copy.copy(item), source.read(item.filename))
        for name, value in replacements.items():
            result.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(before) as source, zipfile.ZipFile(candidate) as result:
        assert result.testzip() is None
        for name in source.namelist():
            if name not in replacements and not name.startswith('BOOT-INF/classes/static/'):
                assert source.read(name) == result.read(name), 'Unexpected backend change: ' + name
        assert all(result.read(name) == value for name, value in replacements.items())
    manifest = {'version': args.version, 'sourceCommit': args.source_commit,
                'baseline': {'api': args.api_baseline, 'mcp': args.mcp_baseline},
                'backendEntriesUnchanged': False, 'patchedClasses': class_names, 'otherBackendEntriesUnchanged': True,
                'artifacts': {'api': {'sha256': digest(candidate)}, 'mcp': {'sha256': args.mcp_baseline}}}
    (root / 'release-manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest), flush=True)


if __name__ == '__main__':
    main()
