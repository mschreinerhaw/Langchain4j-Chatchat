"""Verify Java-exported fixtures using real pandas/PyArrow, without models or business sources.
Run in a no-network, read-only container with the fixture directory mounted at /fixtures.
"""
import argparse
import hashlib
import json
import pathlib
from decimal import Decimal

import pandas as pd
import pyarrow


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', default='/fixtures')
    args = parser.parse_args()
    root = pathlib.Path(args.root)
    result = []
    for fmt in ('PARQUET', 'CSV'):
        directory = root / fmt
        snapshot = json.loads((directory / 'root.json').read_text())
        manifest_bytes = (directory / snapshot['fileId'] / snapshot['fileName']).read_bytes()
        assert hashlib.sha256(manifest_bytes).hexdigest() == snapshot['sha256']
        manifest = json.loads(manifest_bytes)
        schema_file = next(x for x in snapshot['chunks'] if x['fileId'] == manifest['schemaFileId'])
        schema_bytes = (directory / schema_file['fileId'] / schema_file['fileName']).read_bytes()
        assert hashlib.sha256(schema_bytes).hexdigest() == schema_file['sha256']
        schema = json.loads(schema_bytes)
        assert schema['columns'] == manifest['columns']
        rows = []
        for part in manifest['parts']:
            path = directory / part['fileId'] / part['fileName']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == part['sha256']
            frame = pd.read_parquet(path, dtype_backend='pyarrow') if fmt == 'PARQUET' else pd.read_csv(path, dtype=str, keep_default_na=False)
            assert len(frame) == part['rowCount']
            for record in frame.to_dict('records'):
                row = {}
                for column in manifest['columns']:
                    storage = column['storageName']
                    value = record[storage]
                    if fmt == 'PARQUET':
                        if not record['p' + storage[1:]]:
                            continue
                        if pd.isna(value):
                            value = None
                    else:
                        if value == schema['csv']['missingToken']:
                            continue
                        if value == schema['csv']['nullToken']:
                            value = None
                        elif value.startswith('\\\\'):
                            value = value[1:]
                    if value is not None:
                        kind = column['type']
                        if kind == 'DECIMAL':
                            value = Decimal(str(value))
                        elif kind == 'INT64':
                            value = int(value)
                        elif kind == 'FLOAT64':
                            value = float(value)
                        elif kind == 'BOOLEAN':
                            value = value if isinstance(value, bool) else value == 'true'
                        elif kind == 'JSON':
                            value = json.loads(value, parse_float=Decimal)
                    row[column['name']] = value
                rows.append(row)
        assert len(rows) == manifest['rowCount'] == 2
        assert rows[0]['amount'] == Decimal('100.2500') and rows[0]['label'] == 'original'
        assert rows[1]['amount'] == Decimal('0.0100') and rows[1]['label'] is None
        assert rows[0]['identifier'] == 9007199254740993 and 'identifier' not in rows[1]
        assert rows[0]['record']['precise'] == Decimal('1234567890123.4500')
        assert rows[0]['record']['identifier'] == 9007199254740993
        result.append({'format': fmt, 'rows': len(rows), 'parts': len(manifest['parts']), 'status': 'PASSED'})
    print(json.dumps({'scope': 'Real Python reader, Java test fixtures, no model or business source',
        'pandas': pd.__version__, 'pyarrow': pyarrow.__version__, 'results': result}))


if __name__ == '__main__':
    main()
