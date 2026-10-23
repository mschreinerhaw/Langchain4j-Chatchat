import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('release', Path(__file__).resolve().parents[1] / 'prepare-workspace-ui-release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class WorkspaceUiReleaseTest(unittest.TestCase):
    def test_overlay_preserves_every_backend_byte_and_jar_metadata(self):
        content = io.BytesIO()
        with zipfile.ZipFile(content, 'w') as jar:
            jar.comment = b'original archive metadata'
            jar.writestr('BOOT-INF/classes/static/index.html', 'old frontend')
            jar.writestr('BOOT-INF/classes/backend.class', b'compiled backend' * 100, compress_type=zipfile.ZIP_DEFLATED)
            jar.writestr('BOOT-INF/lib/dependency.jar', b'nested dependency')
        before = content.getvalue()
        expected = hashlib.sha256(before).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / 'frontend.zip', 'w') as web:
                web.writestr('index.html', 'new frontend')
                web.writestr('assets/view.js', 'new UI')
            def snapshot(*args, **kwargs):
                kwargs['stdout'].write(before)
            with patch('sys.argv', ['prepare', '--release-dir', directory, '--source-commit', 'source', '--api-baseline', expected, '--mcp-baseline', expected]), patch.object(release.subprocess, 'run', side_effect=snapshot):
                release.main()
            with zipfile.ZipFile(root / 'api.jar') as result:
                self.assertEqual(result.comment, b'original archive metadata')
                self.assertEqual(result.read('BOOT-INF/classes/backend.class'), b'compiled backend' * 100)
                self.assertEqual(result.read('BOOT-INF/lib/dependency.jar'), b'nested dependency')
                self.assertEqual(result.read('BOOT-INF/classes/static/index.html'), b'new frontend')
                self.assertEqual(result.getinfo('BOOT-INF/lib/dependency.jar').compress_type, zipfile.ZIP_STORED)
            manifest = json.loads((root / 'release-manifest.json').read_text())
            self.assertTrue(manifest['backendEntriesUnchanged'])
            self.assertEqual(manifest['baseline']['api'], expected)


if __name__ == '__main__':
    unittest.main()
