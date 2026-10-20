"""Keep deployed console bytes while assembling newly compiled Spring Boot services."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile


def assemble(source, web, target):
    root = 'BOOT-INF/classes/static/'
    expected = {}
    with zipfile.ZipFile(source) as compiled, zipfile.ZipFile(web) as console, \
            zipfile.ZipFile(target, 'w', allowZip64=True) as out:
        for info in compiled.infolist():
            if not info.filename.startswith(root):
                out.writestr(info, compiled.read(info.filename))
        for info in console.infolist():
            if not info.is_dir():
                body=console.read(info.filename)
                expected[root + info.filename]=hashlib.sha256(body).hexdigest()
                out.writestr(root + info.filename, body,
                    compress_type=info.compress_type)
    with zipfile.ZipFile(target) as result:
        for name, digest in expected.items():
            if hashlib.sha256(result.read(name)).hexdigest() != digest:
                raise RuntimeError('Console asset changed: ' + name)
    return {'path': str(target), 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
        'consoleFiles': len(expected)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--api-web', type=Path, required=True)
    parser.add_argument('--mcp-web', type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    pairs = (
        ('api','chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar',args.api_web,'app.jar'),
        ('mcp','chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT.jar',args.mcp_web,'mcp.jar'))
    result = {}
    for label, source, web, name in pairs:
        if not Path(source).is_file() or not web.is_file():
            raise FileNotFoundError('Compiled service or deployed console archive is missing')
        result[label] = assemble(Path(source), web, args.output_dir/name)
    with zipfile.ZipFile(args.output_dir/'app.jar') as api:
        nested = next((item for item in api.namelist() if item.startswith('BOOT-INF/lib/chatchat-agents-')
            and item.endswith('.jar')),None)
        if nested is None: raise RuntimeError('Agents module is absent from API package')
        with zipfile.ZipFile(io.BytesIO(api.read(nested))) as agents:
            if any('ComputedReportAdmission' in name for name in agents.namelist()):
                raise RuntimeError('Retired business admission remains in packaged Agents module')
        if any('CodingSemanticAdmission' in name for name in api.namelist()):
            raise RuntimeError('Retired business admission remains in packaged API')
    with zipfile.ZipFile(args.output_dir/'mcp.jar') as mcp:
        if 'BOOT-INF/classes/python/runtime_input.py' not in mcp.namelist():
            raise RuntimeError('Protocol input reader is absent from MCP package')
    result['source']='locally compiled service packages with byte-preserved deployed console'
    (args.output_dir/'performance-package-manifest.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result))


if __name__ == '__main__':
    main()
