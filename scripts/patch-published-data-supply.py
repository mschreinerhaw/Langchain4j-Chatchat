"""Patch verified supply-chain classes onto the exact running API; preserve all other entries."""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import zipfile


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def patch_archive(source_bytes, replacements):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source_bytes)) as source, zipfile.ZipFile(output, 'w') as result:
        result.comment = source.comment
        for entry in source.infolist():
            result.writestr(copy.copy(entry), replacements.get(entry.filename, source.read(entry.filename)))
        for name, value in replacements.items():
            if name not in source.namelist():
                result.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(source_bytes)) as source, zipfile.ZipFile(output) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(source.namelist()) | set(replacements)
        for name in source.namelist():
            assert result.read(name) == replacements.get(name, source.read(name)), name
        for name, value in replacements.items():
            assert result.read(name) == value, name
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--baseline-file', type=Path, required=True)
    args = parser.parse_args()
    root = args.release_dir.resolve()
    manifest = json.loads((root / 'supply-manifest.json').read_text())
    assert digest(args.baseline_file) == manifest['baseline']['api'], 'API baseline changed'
    replacements = {}
    for name in manifest['classes']:
        assert name.startswith('com/chatchat/agents/') and name.endswith('.class') and '..' not in name
        value = (root / 'classes' / name).read_bytes()
        assert value.startswith(b'\xca\xfe\xba\xbe')
        replacements[name] = value
    assert len(replacements) == 9
    with zipfile.ZipFile(args.baseline_file) as source:
        nested = [name for name in source.namelist() if name.startswith('BOOT-INF/lib/chatchat-agents-')]
        assert len(nested) == 1
        module = patch_archive(source.read(nested[0]), replacements)
    result = patch_archive(args.baseline_file.read_bytes(), {nested[0]: module})
    (root / 'api.jar').write_bytes(result)
    manifest['artifacts'] = {'api': {'sha256': digest(root / 'api.jar')}, 'mcp': {'sha256': manifest['baseline']['mcp']}}
    manifest['otherEntriesUnchanged'] = True
    (root / 'release-manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps({'version': manifest['version'], 'apiSha256': manifest['artifacts']['api']['sha256'],
                      'patchedClasses': len(replacements), 'otherEntriesUnchanged': True}), flush=True)


if __name__ == '__main__':
    main()
