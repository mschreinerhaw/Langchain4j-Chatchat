import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('deployment', Path(__file__).parents[1] / 'deploy-report-presentation.py')
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class FrontendArchiveTest(unittest.TestCase):
    def check(self, entries):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            for path, value in entries.items():
                archive.writestr('BOOT-INF/classes/static/' + path, value)
        buffer.seek(0)
        with zipfile.ZipFile(buffer) as archive:
            return deployment.frontend_resources(archive)

    def test_rejects_windows_archive_paths_before_deployment(self):
        with self.assertRaisesRegex(AssertionError, 'forward slashes'):
            self.check({'index.html': '<script src="./assets/app.js"></script>', 'assets\\app.js': 'code'})

    def test_rejects_missing_resources_even_when_index_is_present(self):
        with self.assertRaisesRegex(AssertionError, 'Missing frontend resource'):
            self.check({'index.html': '<script src="./assets/missing.js"></script>'})

    def test_reads_all_local_scripts_and_styles_for_live_verification(self):
        resources = self.check({'index.html': '<script src="./assets/app.js?v=1"></script><link href="/assets/app.css" rel="stylesheet"><link href="https://example.com/font.css" rel="stylesheet">', 'assets/app.js': 'code', 'assets/app.css': 'style'})
        self.assertEqual({'assets/app.js': b'code', 'assets/app.css': b'style'}, resources)


if __name__ == '__main__':
    unittest.main()
