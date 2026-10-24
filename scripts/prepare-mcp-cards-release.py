"""Patch two tested API classes and the MCP view onto the exact deployed artifact."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import zipfile


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--api-baseline-file', type=Path, required=True)
    parser.add_argument('--api-baseline', required=True)
    parser.add_argument('--mcp-baseline', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    root = args.release_dir.resolve()
    assert digest(args.api_baseline_file) == args.api_baseline, 'API baseline changed'
    assets = json.loads((root / 'asset-manifest.json').read_text())
    prefix = 'BOOT-INF/classes/static/assets/'
    allowed_classes = {'BOOT-INF/classes/com/chatchat/api/controller/mcp/McpServiceController.class',
                       'BOOT-INF/classes/com/chatchat/api/service/PublishedMcpToolCatalog.class'}
    allowed_assets = {prefix + assets[name] for name in ['main', 'nextView', 'nextCss']}
    replacements = {}
    for archive, allowed in [('api-classes.zip', allowed_classes), ('frontend.zip', allowed_assets)]:
        with zipfile.ZipFile(root / archive) as patch:
            assert patch.testzip() is None
            entries = {name: patch.read(name) for name in patch.namelist() if not name.endswith('/')}
            assert set(entries) == allowed, 'Unexpected patch entries: ' + archive
            if archive == 'api-classes.zip':
                assert all(value.startswith(b'\xca\xfe\xba\xbe') for value in entries.values())
            replacements.update(entries)
    with zipfile.ZipFile(args.api_baseline_file) as source:
        assert hashlib.sha256(source.read(prefix + assets['main'])).hexdigest() == assets['baselineMainSha256']
        with zipfile.ZipFile(root / 'api.jar', 'w', allowZip64=True) as result:
            result.comment = source.comment
            for entry in source.infolist():
                if entry.filename not in replacements:
                    result.writestr(copy.copy(entry), source.read(entry.filename))
            for name, value in replacements.items():
                result.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(root / 'api.jar') as result:
            assert result.testzip() is None
            for entry in source.infolist():
                if entry.filename not in replacements:
                    assert source.read(entry.filename) == result.read(entry.filename), 'Unexpected artifact change: ' + entry.filename
            for name, value in replacements.items():
                assert result.read(name) == value
    manifest = {'version': args.version, 'sourceCommit': args.source_commit,
                'baseline': {'api': args.api_baseline, 'mcp': args.mcp_baseline},
                'artifacts': {'api': {'sha256': digest(root / 'api.jar')}, 'mcp': {'sha256': args.mcp_baseline}},
                'patchedEntries': sorted(replacements), 'otherEntriesUnchanged': True,
                'preservedDeployedSystemSearch': True, 'preservedOtherFrontendViews': True}
    (root / 'release-manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest), flush=True)


if __name__ == '__main__':
    main()
