import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from decimal import Decimal

module_path = Path(__file__).parents[2] / 'main/resources/python/runtime_input.py'
spec = importlib.util.spec_from_file_location('runtime_input', module_path)
sdk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sdk)


class ReaderTest(unittest.TestCase):
    def native_fixture(self, root):
        original, _, part = self.fixture(root, 'PARQUET')
        uploaded_id, target_id = 'c' + 'd' * 63, 'c' + 'e' * 63
        # Uploaded original IDs intentionally differ from the current mount IDs.
        uploaded = root / uploaded_id / 'original-0.json'
        uploaded.parent.mkdir()
        uploaded.write_bytes(original.read_bytes())
        target = root / target_id / 'original-2.parquet'
        target.parent.mkdir()
        target.write_bytes(part.read_bytes())
        files = [dict(fileId=p.parent.name, fileName=p.name,
                      sha256=hashlib.sha256(p.read_bytes()).hexdigest(), sizeBytes=p.stat().st_size)
                 for p in (uploaded, target)]
        outer = root / ('c' + 'f' * 63) / 'manifest.json'
        outer.parent.mkdir()
        envelope = dict(schemaVersion='runtime_os.coding.input.v1', format='PARQUET',
                        columns=[dict(name='record', storageName='c0', type='JSON')], nativeFiles=files)
        outer.write_text(json.dumps(envelope), encoding='utf-8')
        # No old source paths survive: a reader must not follow original file IDs.
        original.unlink()
        part.unlink()
        return outer, target, uploaded, envelope

    def test_native_mapping_uses_selected_file_and_its_own_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            outer, target, _, _ = self.native_fixture(Path(tmp))
            rows = list(sdk.load_native_rows(outer, hashlib.sha256(outer.read_bytes()).hexdigest(), target.parent.name))
            self.assertEqual(rows[0]['identifier'], 9007199254740993)
            self.assertEqual(rows[0]['amount'], Decimal('12.3400'))
            self.assertEqual(rows[0]['record']['precise'], Decimal('1234567890123.4500'))
            self.assertNotIn('optional', rows[0])
            self.assertIsNone(rows[1]['optional'])
            self.assertEqual(rows[0]['literal'], '\\N')

    def test_native_rejects_tampering_unknown_ids_and_missing_binding(self):
        with tempfile.TemporaryDirectory() as tmp:
            outer, target, uploaded, envelope = self.native_fixture(Path(tmp))
            digest = hashlib.sha256(outer.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, 'authorized native'):
                list(sdk.load_native_rows(outer, digest, '../other'))
            target.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                list(sdk.load_native_rows(outer, digest, target.parent.name))
            envelope['nativeFiles'] = envelope['nativeFiles'][1:]
            envelope['nativeFiles'][0]['sha256'] = hashlib.sha256(target.read_bytes()).hexdigest()
            envelope['nativeFiles'][0]['sizeBytes'] = target.stat().st_size
            outer.write_text(json.dumps(envelope))
            with self.assertRaisesRegex(ValueError, 'absent or ambiguous'):
                list(sdk.load_native_rows(outer, hashlib.sha256(outer.read_bytes()).hexdigest(), target.parent.name))

    def test_native_rejects_ambiguous_maps_and_wrong_row_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            outer, target, uploaded, envelope = self.native_fixture(Path(tmp))
            original = json.loads(uploaded.read_text())
            original['parts'][0]['rowCount'] += 1
            uploaded.write_text(json.dumps(original))
            envelope['nativeFiles'][0]['sha256'] = hashlib.sha256(uploaded.read_bytes()).hexdigest()
            envelope['nativeFiles'][0]['sizeBytes'] = uploaded.stat().st_size
            outer.write_text(json.dumps(envelope))
            with self.assertRaisesRegex(ValueError, 'row count'):
                list(sdk.load_native_rows(outer, hashlib.sha256(outer.read_bytes()).hexdigest(), target.parent.name))
            envelope['nativeFiles'].append(envelope['nativeFiles'][0])
            outer.write_text(json.dumps(envelope))
            with self.assertRaisesRegex(ValueError, 'absent or ambiguous'):
                list(sdk.load_native_rows(outer, hashlib.sha256(outer.read_bytes()).hexdigest(), target.parent.name))

    def fixture(self, root, fmt):
        mid, pid = 'c' + 'a' * 63, 'c' + 'b' * 63
        columns = [dict(name=n, storageName='c' + str(i), type=t) for i, (n, t) in enumerate(
            [('identifier', 'INT64'), ('amount', 'DECIMAL'), ('record', 'JSON'), ('optional', 'STRING'), ('literal', 'STRING')])]
        folder = root / pid
        folder.mkdir()
        part = folder / ('part.' + fmt.lower())
        if fmt == 'CSV':
            with part.open('w', newline='', encoding='utf-8') as f:
                writer = csv.writer(f)
                writer.writerow(['c0', 'c1', 'c2', 'c3', 'c4'])
                writer.writerow(['9007199254740993', '12.3400', '{"x":1,"precise":1234567890123.4500}', '\\M', '\\\\N'])
                writer.writerow(['1', '\\N', '{}', '\\N', 'text'])
        else:
            import pyarrow as pa
            import pyarrow.parquet as pq
            pq.write_table(pa.table({'c0': pa.array([9007199254740993, 1], type=pa.int64()),
                'c1': pa.array([Decimal('12.3400'), None], type=pa.decimal128(12, 4)),
                'c2': ['{"x":1,"precise":1234567890123.4500}', '{}'], 'c3': [None, None], 'c4': ['\\N', 'text'],
                'p0': [True, True], 'p1': [True, True], 'p2': [True, True],
                'p3': [False, True], 'p4': [True, True]}), part)
        manifest = dict(schemaVersion='runtime_os.coding.input.v1', format=fmt, columns=columns, rowCount=2,
            parts=[dict(fileId=pid, fileName=part.name, sha256=hashlib.sha256(part.read_bytes()).hexdigest(), rowCount=2, sizeBytes=part.stat().st_size)])
        path = root / mid / 'manifest.json'
        path.parent.mkdir()
        path.write_text(json.dumps(manifest), encoding='utf-8')
        return path, hashlib.sha256(path.read_bytes()).hexdigest(), part

    def test_round_trip_both_formats_without_precision_loss_or_defaulting(self):
        for fmt in ('CSV', 'PARQUET'):
            with self.subTest(format=fmt), tempfile.TemporaryDirectory() as tmp:
                path, digest, _ = self.fixture(Path(tmp), fmt)
                rows = list(sdk.load_rows(path, digest))
                self.assertEqual(rows, [dict(identifier=9007199254740993, amount=Decimal('12.3400'), record={'x': 1, 'precise': Decimal('1234567890123.4500')}, literal='\\N'),
                    dict(identifier=1, amount=None, record={}, optional=None, literal='text')])

    def test_rejects_modified_manifest_and_modified_part(self):
        with tempfile.TemporaryDirectory() as tmp:
            path, digest, part = self.fixture(Path(tmp), 'CSV')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                list(sdk.load_rows(path, '0' * 64))
            part.write_bytes(b'corrupt')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                list(sdk.load_rows(path, digest))

    def test_rejects_incorrect_cardinality_and_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            path, _, _ = self.fixture(Path(tmp), 'CSV')
            manifest = json.loads(path.read_text())
            manifest['parts'][0]['rowCount'] = 3
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'row count'):
                list(sdk.load_rows(path, hashlib.sha256(path.read_bytes()).hexdigest()))
            manifest['parts'][0]['fileId'] = '../outside'
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'identity'):
                list(sdk.load_rows(path, hashlib.sha256(path.read_bytes()).hexdigest()))


if __name__ == '__main__':
    unittest.main()
