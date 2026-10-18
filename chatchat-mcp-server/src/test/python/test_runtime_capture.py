import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

RUNNER = pathlib.Path(__file__).parents[2] / 'main/resources/python/runtime_capture.py'


class StreamBoundaryTest(unittest.TestCase):
    def execute(self, source):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            script = root / 'main.py'
            diagnostic = root / 'diagnostic.json'
            script.write_text(source, encoding='utf-8')
            result = subprocess.run([sys.executable, str(RUNNER), str(script), str(diagnostic)], capture_output=True)
            content = diagnostic.read_text()
            return result, json.loads(content), content

    def test_raw_rows_and_exception_message_never_reach_persisted_output(self):
        result, diagnostic, content = self.execute("import sys\nprint('private-row-123')\nraise ValueError('private-row-456')\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b'', result.stdout)
        self.assertEqual(b'', result.stderr)
        self.assertNotIn('private-row', content)
        self.assertEqual('ValueError', diagnostic['exceptionType'])
        self.assertEqual(3, diagnostic['line'])
        self.assertGreater(diagnostic['stdoutBytes'], 0)

    def test_large_native_stream_is_drained_without_a_raw_log(self):
        result, diagnostic, content = self.execute("import os\nos.write(1, b'x' * 2097152)\n")
        self.assertEqual(0, result.returncode)
        self.assertEqual(2097152, diagnostic['stdoutBytes'])
        self.assertLess(len(content), 512)
        self.assertEqual(b'', result.stdout)


if __name__ == '__main__':
    unittest.main()
