"""Patch tested publication classes and the API console onto exact running artifacts."""
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
    args = parser.parse_args()
    root = args.release_dir.resolve()
    manifest = {'version': '2.4.5-tool-draft-edit-20261023', 'sourceCommit': args.source_commit, 'baseline': {}, 'artifacts': {}}
    for service, expected in [('api', args.api_baseline), ('mcp', args.mcp_baseline)]:
        before = root / (service + '-before.jar')
        with before.open('wb') as output:
            subprocess.run(['docker', 'exec', 'runtime-os-app-stage-' + service + '-1', 'cat', '/srv/app/app.jar'], stdout=output, check=True)
        assert digest(before) == expected, 'Running baseline changed: ' + service
        with zipfile.ZipFile(root / (service + '-classes.zip')) as patch, zipfile.ZipFile(before) as source:
            replacements = {name: patch.read(name) for name in patch.namelist() if not name.endswith('/')}
            assert replacements and all(name.startswith('BOOT-INF/classes/com/chatchat/') and name.endswith('.class') and '\\' not in name and '..' not in name.split('/') and value.startswith(b'\xca\xfe\xba\xbe') for name, value in replacements.items())
            class_names = sorted(replacements)
            if service == 'api':
                with zipfile.ZipFile(root / 'frontend.zip') as web:
                    assert web.testzip() is None and 'index.html' in web.namelist()
                    for name in web.namelist():
                        assert '\\' not in name and not name.startswith('/') and '..' not in name.split('/')
                        if not name.endswith('/'):
                            replacements['BOOT-INF/classes/static/' + name] = web.read(name)
            candidate = root / (service + '.jar')
            with zipfile.ZipFile(candidate, 'w', allowZip64=True) as result:
                result.comment = source.comment
                for item in source.infolist():
                    if item.filename in replacements or service == 'api' and item.filename.startswith('BOOT-INF/classes/static/'):
                        continue
                    result.writestr(copy.copy(item), source.read(item.filename))
                for name, value in replacements.items():
                    result.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
            with zipfile.ZipFile(candidate) as result:
                assert result.testzip() is None
                for name in source.namelist():
                    if name not in replacements and not (service == 'api' and name.startswith('BOOT-INF/classes/static/')):
                        assert source.read(name) == result.read(name), 'Unexpected backend change: ' + name
                assert all(result.read(name) == value for name, value in replacements.items())
            manifest['baseline'][service] = expected
            manifest['artifacts'][service] = {'sha256': digest(candidate), 'testedClasses': class_names, 'otherBackendEntriesUnchanged': True}
    (root / 'release-manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest), flush=True)


if __name__ == '__main__':
    main()
