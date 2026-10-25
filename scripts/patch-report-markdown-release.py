"""Apply the tested visual-report assets while preserving every Java and unrelated frontend entry."""
import argparse
import copy
import datetime
import hashlib
import json
from pathlib import Path
import zipfile


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--baseline-file', type=Path, required=True)
    args = parser.parse_args()
    root = args.release_dir.resolve()
    manifest = json.loads((root / 'release-input.json').read_text())
    assets = json.loads((root / 'asset-manifest.json').read_text())
    assert digest(args.baseline_file) == manifest['baseline']['api'], 'API baseline changed'
    prefix = 'BOOT-INF/classes/static/assets/'
    replacements = {}
    for name, expected in assets['hashes'].items():
        assert '/' not in name and '\\' not in name and name.endswith(('.js', '.css'))
        data = (root / 'assets' / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == expected
        replacements[prefix + name] = data
    assert set(assets['hashes']) == set(assets['referrers']) | {assets['nextJs'], assets['nextCss']}
    now = datetime.datetime.now(datetime.timezone.utc).timetuple()[:6]
    with zipfile.ZipFile(args.baseline_file) as source:
        for name, expected in assets['referrers'].items():
            assert hashlib.sha256(source.read(prefix + name)).hexdigest() == expected
        with zipfile.ZipFile(root / 'api.jar', 'w', allowZip64=True) as result:
            result.comment = source.comment
            for entry in source.infolist():
                info = copy.copy(entry)
                if entry.filename in replacements:
                    info.date_time = now
                result.writestr(info, replacements.get(entry.filename, source.read(entry.filename)))
            for name, data in replacements.items():
                if name not in source.namelist():
                    result.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(root / 'api.jar') as result:
            assert result.testzip() is None
            assert set(result.namelist()) == set(source.namelist()) | set(replacements)
            for entry in source.infolist():
                assert result.read(entry.filename) == replacements.get(entry.filename, source.read(entry.filename))
            for name, data in replacements.items():
                assert result.read(name) == data
    manifest.update(artifacts={'api': {'sha256': digest(root / 'api.jar')},
                               'mcp': {'sha256': manifest['baseline']['mcp']}},
                    patchedEntries=sorted(replacements), otherEntriesUnchanged=True)
    (root / 'release-manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps({'version': manifest['version'], 'patchedEntries': len(replacements),
                      'apiSha256': manifest['artifacts']['api']['sha256']}), flush=True)


if __name__ == '__main__':
    main()
