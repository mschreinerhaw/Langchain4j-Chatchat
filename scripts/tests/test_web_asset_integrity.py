"""Reproduce the deploy regression: valid HTML whose hashed JS/CSS were not packaged."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'VerifyWebAssets.java'


class WebAssetIntegrityTest(unittest.TestCase):
    def check_assets(self, html, files):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'index.html').write_text(html, encoding='utf8')
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf8')
            return subprocess.run([shutil.which('java'), '--source', '17', str(SOURCE), str(root)],
                                  capture_output=True, text=True)

    def test_missing_hashed_javascript_blocks_packaging(self):
        result = self.check_assets('<script type="module" src="./assets/index-CS__9XJW.js"></script>', {})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('assets/index-CS__9XJW.js', result.stderr)

    def test_missing_stylesheet_blocks_packaging(self):
        result = self.check_assets('<script src="./main.js"></script><link href="./main.css" rel="stylesheet">',
                                   {'main.js': 'export {}'})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('main.css', result.stderr)

    def test_complete_build_accepts_single_quotes_query_and_external_links(self):
        result = self.check_assets("<SCRIPT SRC='./assets/main.js?v=1'></SCRIPT>"
                                   "<link href='/main.css#style' rel='stylesheet'>"
                                   "<link href='https://example.invalid/style.css' rel='stylesheet'>",
                                   {'assets/main.js': 'export {}', 'main.css': 'body {}'})
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_empty_entrypoint_is_rejected(self):
        result = self.check_assets('<script src="./main.js"></script>', {'main.js': ''})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('main.js', result.stderr)

    def test_resource_escape_is_rejected(self):
        result = self.check_assets('<script src="../outside.js"></script>', {})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('../outside.js', result.stderr)

    def test_empty_html_cannot_certify_an_application(self):
        result = self.check_assets('<div id="app"></div>', {})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('no local script entrypoint', result.stderr)


if __name__ == '__main__':
    unittest.main()
