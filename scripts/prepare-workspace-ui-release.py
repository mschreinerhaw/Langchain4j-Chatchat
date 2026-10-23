"""Overlay a verified frontend on the exact running API, preserving all backend entries."""
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
    parser.add_argument('--version', default='2.4.2-workspace-ui-20261023')
    parser.add_argument('--workspace-navigation-class', type=Path)
    args = parser.parse_args()
    release = args.release_dir.resolve()
    baseline = {}
    for name, expected in [('api', args.api_baseline), ('mcp', args.mcp_baseline)]:
        target = release / (name + '-before.jar')
        with target.open('wb') as output:
            subprocess.run(['docker', 'exec', 'runtime-os-app-stage-' + name + '-1', 'cat', '/srv/app/app.jar'], stdout=output, check=True)
        assert digest(target) == expected, 'Running baseline mismatch: ' + name
        baseline[name] = expected
    prefix = 'BOOT-INF/classes/static/'
    navigation = 'BOOT-INF/classes/com/chatchat/api/workspace/PermissionWorkspaceUxMigration.class'
    navigation_bytes = args.workspace_navigation_class.read_bytes() if args.workspace_navigation_class else None
    if navigation_bytes is not None:
        assert navigation_bytes.startswith(b'\xca\xfe\xba\xbe')
    with zipfile.ZipFile(release / 'frontend.zip') as web, zipfile.ZipFile(release / 'api-before.jar') as old:
        assert web.testzip() is None
        names = web.namelist()
        assert 'index.html' in names
        assert all('\\' not in name and not name.startswith('/') and '..' not in Path(name).parts for name in names)
        with zipfile.ZipFile(release / 'api.jar', 'w', allowZip64=True) as new:
            new.comment = old.comment
            for entry in old.infolist():
                if not entry.filename.startswith(prefix) and not (navigation_bytes is not None and entry.filename == navigation):
                    new.writestr(copy.copy(entry), old.read(entry.filename))
            if navigation_bytes is not None:
                new.writestr(navigation, navigation_bytes, compress_type=zipfile.ZIP_DEFLATED)
            for entry in web.infolist():
                if not entry.is_dir():
                    new.writestr(prefix + entry.filename, web.read(entry.filename), compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(release / 'api.jar') as new:
            assert new.testzip() is None
            old_backend = {e.filename: e.CRC for e in old.infolist() if not e.filename.startswith(prefix) and not (navigation_bytes is not None and e.filename == navigation)}
            new_backend = {e.filename: e.CRC for e in new.infolist() if not e.filename.startswith(prefix) and not (navigation_bytes is not None and e.filename == navigation)}
            assert old_backend == new_backend
            assert all(old.read(name) == new.read(name) for name in old_backend)
            if navigation_bytes is not None:
                assert new.read(navigation) == navigation_bytes
    manifest = {'version': args.version, 'sourceCommit': args.source_commit,
                'baseline': baseline, 'backendEntriesUnchanged': navigation_bytes is None,
                'patchedClasses': [navigation] if navigation_bytes is not None else [], 'otherBackendEntriesUnchanged': True,
                'artifacts': {'api': {'sha256': digest(release / 'api.jar')}, 'mcp': {'sha256': baseline['mcp']}}}
    (release / 'release-manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest))


if __name__ == '__main__':
    main()
