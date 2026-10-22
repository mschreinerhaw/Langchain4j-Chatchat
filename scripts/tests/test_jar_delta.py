import importlib.util
from pathlib import Path
import random
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('jar_delta', Path(__file__).parents[1] / 'jar-delta.py')
delta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delta)


class JarDeltaTest(unittest.TestCase):
    def test_reconstructs_exact_bytes_with_changed_headers_classes_and_deleted_entries(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            dependency = random.Random(7).randbytes(1024 * 1024)
            for filename, version in [('old.jar', 'old'), ('new.jar', 'new')]:
                with zipfile.ZipFile(root / filename, 'w') as archive:
                    info = zipfile.ZipInfo('lib/dependency.jar', (2026, 10, 21 if version == 'old' else 22, 1, 2, 0))
                    archive.writestr(info, dependency)
                    archive.writestr('classes/Application.class', version * 100, compress_type=zipfile.ZIP_DEFLATED)
                    archive.writestr('empty', b'')
                    if version == 'old': archive.writestr('removed', 'legacy')
            delta.signature(root / 'old.jar', root / 'signature.json')
            delta.create(root / 'signature.json', root / 'new.jar', root / 'patch.zip')
            delta.apply(root / 'old.jar', root / 'patch.zip', root / 'restored.jar')
            self.assertEqual((root / 'new.jar').read_bytes(), (root / 'restored.jar').read_bytes())
            self.assertLess((root / 'patch.zip').stat().st_size, 10000)

    def test_changed_baseline_is_rejected_without_replacing_existing_output(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            with zipfile.ZipFile(root / 'base.jar', 'w') as archive: archive.writestr('a', 'old')
            delta.signature(root / 'base.jar', root / 'signature.json')
            delta.create(root / 'signature.json', root / 'base.jar', root / 'patch.zip')
            (root / 'base.jar').write_bytes(b'corrupted')
            (root / 'output.jar').write_bytes(b'previous release')
            with self.assertRaisesRegex(ValueError, 'Baseline JAR checksum mismatch'):
                delta.apply(root / 'base.jar', root / 'patch.zip', root / 'output.jar')
            self.assertEqual((root / 'output.jar').read_bytes(), b'previous release')


if __name__ == '__main__': unittest.main()
