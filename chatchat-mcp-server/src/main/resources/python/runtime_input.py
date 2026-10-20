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
            import pyarrow.parquet as pq
            for batch in pq.ParquetFile(source).iter_batches(batch_size=1000):
                for cells in batch.to_pylist():
                    row = {}
                    for column in columns:
                        storage = column['storageName']
                        if not cells['p' + storage[1:]]:
                            continue
                        value = cells[storage]
                        if value is not None and column['type'] == 'JSON':
                            value = json.loads(value, parse_float=Decimal)
                        row[column['name']] = value
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
