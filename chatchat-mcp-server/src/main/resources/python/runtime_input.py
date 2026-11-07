"""Runtime input.v1 transport reader. No business parsing or value substitution."""
import csv
import hashlib
import json
import re
from decimal import Decimal
from pathlib import Path


def _file(root, file_id, name):
    if not re.fullmatch(r'c[0-9a-f]{63}', file_id) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,170}', name):
        raise ValueError('Invalid input file identity')
    path = root / file_id / name
    if path.is_symlink() or path.parent.is_symlink():
        raise ValueError('Input symlink')
    return path


def _verified(path, expected):
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Invalid input checksum')
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError('Input checksum mismatch')


def _parquet_rows(source, columns):
    import pyarrow.parquet as pq
    names, storage_names = set(), set()
    for column in columns:
        name, storage = column['name'], column['storageName']
        if (not isinstance(name, str) or not name or name in names
                or not re.fullmatch(r'c[0-9]{1,4}', storage) or storage in storage_names
                or column['type'] not in ('BOOLEAN', 'INT64', 'FLOAT64', 'DECIMAL', 'STRING', 'JSON', 'NULL')):
            raise ValueError('Invalid input column mapping')
        names.add(name)
        storage_names.add(storage)
    for batch in pq.ParquetFile(source).iter_batches(batch_size=1000):
        for cells in batch.to_pylist():
            row = {}
            for column in columns:
                storage = column['storageName']
                present = cells['p' + storage[1:]]
                if not isinstance(present, bool):
                    raise ValueError('Invalid input presence flag')
                if not present:
                    continue
                value = cells[storage]
                if value is not None and column['type'] == 'JSON':
                    value = json.loads(value, parse_float=Decimal)
                row[column['name']] = value
            yield row


def load_native_rows(manifest_path, expected_manifest_sha256, native_file_id):
    """Yield a selected original Parquet using its hash-bound ORIGINAL column map.

    Only files in the verified outer manifest can be opened. Old file IDs/paths in
    an uploaded manifest are never followed. No business fields are selected or
    converted; absent/ambiguous mappings fail rather than guessing a schema.
    Consume the iterator fully to verify the declared row count.
    """
    path = Path(manifest_path)
    root = path.parent.parent
    path = _file(root, path.parent.name, path.name)
    _verified(path, expected_manifest_sha256)
    manifest = json.loads(path.read_text(encoding='utf-8'))
    if manifest.get('schemaVersion') != 'runtime_os.coding.input.v1':
        raise ValueError('Unsupported input protocol')
    files = manifest.get('nativeFiles', [])
    selected = [item for item in files if item['fileId'] == native_file_id]
    if len(selected) != 1 or not selected[0]['fileName'].endswith('.parquet'):
        raise ValueError('Select one authorized native Parquet fileId')
    native = selected[0]
    source = _file(root, native['fileId'], native['fileName'])
    _verified(source, native['sha256'])
    if source.stat().st_size != native['sizeBytes']:
        raise ValueError('Native input size mismatch')
    bindings = []
    for item in files:
        if not item['fileName'].endswith('.json') or item['sizeBytes'] > 128 * 1024:
            continue
        candidate = _file(root, item['fileId'], item['fileName'])
        _verified(candidate, item['sha256'])
        if candidate.stat().st_size != item['sizeBytes']:
            raise ValueError('Native manifest size mismatch')
        try:
            original = json.loads(candidate.read_text(encoding='utf-8'))
        except (ValueError, UnicodeError):
            continue
        if not isinstance(original, dict) or original.get('schemaVersion') != 'runtime_os.coding.input.v1' or original.get('format') != 'PARQUET':
            continue
        for part in original.get('parts', []):
            if part['sha256'] == native['sha256']:
                if part['sizeBytes'] != native['sizeBytes']:
                    raise ValueError('Native part size binding mismatch')
                bindings.append((original['columns'], part['rowCount']))
    if len(bindings) != 1:
        raise ValueError('Native source manifest binding is absent or ambiguous')
    columns, expected_count = bindings[0]
    count = 0
    for row in _parquet_rows(source, columns):
        count += 1
        yield row
    if count != expected_count:
        raise ValueError('Native input row count mismatch')


def load_rows(manifest_path, expected_manifest_sha256):
    """Yield logical dictionaries; preserve missing/null, JSON, integers and decimals.

    For batched input each dictionary contains datasetReference and record. The
    caller owns grouping, document parsing, calculations and business judgments.
    """
    path = Path(manifest_path)
    root = path.parent.parent
    path = _file(root, path.parent.name, path.name)
    _verified(path, expected_manifest_sha256)
    manifest = json.loads(path.read_text(encoding='utf-8'))
    if manifest['schemaVersion'] != 'runtime_os.coding.input.v1' or manifest['format'] not in ('PARQUET', 'CSV'):
        raise ValueError('Unsupported input protocol')
    columns = manifest['columns']
    total = 0
    for part in manifest['parts']:
        source = _file(root, part['fileId'], part['fileName'])
        _verified(source, part['sha256'])
        if source.stat().st_size != part['sizeBytes']:
            raise ValueError('Input part size mismatch')
        count = 0
        if manifest['format'] == 'PARQUET':
            for row in _parquet_rows(source, columns):
                count += 1
                yield row
        else:
            with source.open(encoding='utf-8', newline='') as stream:
                for cells in csv.DictReader(stream):
                    row = {}
                    for column in columns:
                        value = cells[column['storageName']]
                        if value == '\\M':
                            continue
                        if value == '\\N':
                            value = None
                        else:
                            if value.startswith('\\\\'):
                                value = value[1:]
                            kind = column['type']
                            if kind == 'JSON':
                                value = json.loads(value, parse_float=Decimal)
                            elif kind == 'BOOLEAN':
                                if value not in ('true', 'false'):
                                    raise ValueError('Invalid protocol boolean')
                                value = value == 'true'
                            elif kind == 'INT64':
                                value = int(value)
                            elif kind == 'FLOAT64':
                                value = float(value)
                            elif kind == 'DECIMAL':
                                value = Decimal(value)
                            elif kind == 'NULL':
                                raise ValueError('Null-only column contains a value')
                            elif kind != 'STRING':
                                raise ValueError('Unsupported input column type')
                        row[column['name']] = value
                    count += 1
                    yield row
        if count != part['rowCount']:
            raise ValueError('Input part row count mismatch')
        total += count
    if total != manifest['rowCount']:
        raise ValueError('Input manifest row count mismatch')
