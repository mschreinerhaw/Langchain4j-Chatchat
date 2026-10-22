"""Byte-exact ZIP/JAR delta for slow deployment links; no ZIP recompression.

signature BASE SIGNATURE_JSON
create SIGNATURE_JSON TARGET PATCH_ZIP
apply BASE PATCH_ZIP OUTPUT
The final full-file SHA-256 is mandatory before using the reconstructed JAR.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk := stream.read(1024 * 1024): value.update(chunk)
    return value.hexdigest()


def payloads(path):
    with zipfile.ZipFile(path) as archive, Path(path).open('rb') as stream:
        for member in archive.infolist():
            stream.seek(member.header_offset)
            header = stream.read(30)
            if header[:4] != b'PK\x03\x04': raise ValueError('Invalid ZIP local header')
            name, extra = struct.unpack_from('<HH', header, 26)
            start = member.header_offset + 30 + name + extra
            stream.seek(start)
            data = stream.read(member.compress_size)
            if len(data) != member.compress_size: raise ValueError('Truncated ZIP payload')
            yield member.filename, start, data


def signature(source, output):
    entries = {name: {'offset': offset, 'length': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
               for name, offset, data in payloads(source)}
    Path(output).write_text(json.dumps({'baseSha256': digest(source), 'entries': entries}))


def create(signature_file, target, patch_file):
    baseline = json.loads(Path(signature_file).read_text())
    pieces = []
    with zipfile.ZipFile(patch_file, 'w', compression=zipfile.ZIP_STORED) as patch, Path(target).open('rb') as stream:
        cursor = 0
        def literal(data):
            if not data: return
            name = 'literal/' + str(len(pieces)); patch.writestr(name, data); pieces.append({'literal': name})
        for name, offset, data in payloads(target):
            stream.seek(cursor); literal(stream.read(offset - cursor))
            prior = baseline['entries'].get(name)
            if prior and prior['length'] == len(data) and prior['sha256'] == hashlib.sha256(data).hexdigest():
                pieces.append({'baseOffset': prior['offset'], 'length': len(data)})
            else: literal(data)
            cursor = offset + len(data)
        stream.seek(cursor); literal(stream.read())
        recipe = {'baseSha256': baseline['baseSha256'], 'targetSha256': digest(target), 'pieces': pieces}
        patch.writestr('recipe.json', json.dumps(recipe))
    print(json.dumps({'patchBytes': Path(patch_file).stat().st_size, 'targetSha256': recipe['targetSha256']}))


def apply(source, patch_file, output):
    source, output = Path(source).resolve(), Path(output).resolve()
    if source == output: raise ValueError('Output cannot overwrite the baseline JAR')
    with zipfile.ZipFile(patch_file) as patch:
        recipe = json.loads(patch.read('recipe.json'))
        if digest(source) != recipe['baseSha256']: raise ValueError('Baseline JAR checksum mismatch')
        temporary = output.with_suffix(output.suffix + '.partial')
        try:
            with source.open('rb') as stream, temporary.open('wb') as target:
                for item in recipe['pieces']:
                    if 'literal' in item: target.write(patch.read(item['literal']))
                    else:
                        stream.seek(item['baseOffset']); remaining = item['length']
                        while remaining:
                            chunk = stream.read(min(1024 * 1024, remaining))
                            if not chunk: raise ValueError('Truncated baseline')
                            target.write(chunk); remaining -= len(chunk)
            if digest(temporary) != recipe['targetSha256']: raise ValueError('Reconstructed JAR checksum mismatch')
            temporary.replace(output)
        finally:
            if temporary.exists(): temporary.unlink()
    print('JAR_SHA256_VERIFIED', output.name)


if __name__ == '__main__':
    {'signature': signature, 'create': create, 'apply': apply}[sys.argv[1]](*sys.argv[2:])
