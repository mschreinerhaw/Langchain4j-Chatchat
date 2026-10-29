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

    def test_missing_required_import_is_detected_before_any_user_statement(self):
        result, diagnostic, content = self.execute("print('must-not-run')\nimport missing_runtime_dependency_7ac4\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('FAILED', diagnostic['preflightStatus'])
        self.assertEqual('ModuleNotFoundError', diagnostic['exceptionType'])
        self.assertEqual('PACKAGE_NOT_INSTALLED', diagnostic['diagnosticCode'])
        self.assertEqual(2, diagnostic['line'])
        self.assertEqual(0, diagnostic['stdoutBytes'])
        self.assertNotIn('missing_runtime_dependency', content)
        self.assertEqual(b'', result.stderr)

    def test_invalid_syntax_fails_preflight_without_running_user_code(self):
        result, diagnostic, content = self.execute("print('must-not-run')\nif True print('invalid')\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('FAILED', diagnostic['preflightStatus'])
        self.assertEqual('SyntaxError', diagnostic['exceptionType'])
        self.assertEqual(2, diagnostic['line'])
        self.assertEqual(0, diagnostic['stdoutBytes'])
        self.assertNotIn('must-not-run', content)

    def test_optional_dependency_fallback_remains_executable(self):
        result, diagnostic, _ = self.execute("try:\n import missing_optional_dependency_7ac4\nexcept ImportError:\n import json\nprint(json.dumps({'count': len([])}))\n")
        self.assertEqual(0, result.returncode)
        self.assertEqual('PASSED', diagnostic['preflightStatus'])
        self.assertGreater(diagnostic['stdoutBytes'], 0)

    def test_unexecuted_function_does_not_require_its_optional_dependency(self):
        result, diagnostic, _ = self.execute("def unused():\n import missing_unused_dependency_7ac4\nprint('complete')\n")
        self.assertEqual(0, result.returncode)
        self.assertEqual('PASSED', diagnostic['preflightStatus'])

    def test_real_runtime_failure_is_distinct_from_preflight_failure(self):
        result, diagnostic, _ = self.execute("data = []\nprint(data[0])\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('PASSED', diagnostic['preflightStatus'])
        self.assertEqual('IndexError', diagnostic['exceptionType'])
        self.assertEqual(2, diagnostic['line'])

    def test_read_only_input_failure_is_a_permission_boundary_not_a_package_repair(self):
        result, diagnostic, content = self.execute("raise OSError(30, 'Read-only file system: private-input')\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('PERMISSION_DENIED', diagnostic['diagnosticCode'])
        self.assertNotIn('private-input', content)
    def test_local_sqlite_error_is_repairable_without_leaking_columns_or_values(self):
        result, diagnostic, content = self.execute("import sqlite3\ncon=sqlite3.connect(':memory:')\ncon.execute('select private_column_7ac4 from missing_private_table')\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('LOCAL_SQL_ERROR', diagnostic['diagnosticCode'])
        self.assertEqual('PASSED', diagnostic['preflightStatus'])
        self.assertEqual(3, diagnostic['line'])
        self.assertNotIn('private_column', content)
        self.assertNotIn('private_table', content)
    def test_standard_parser_failures_keep_only_fixed_technical_categories(self):
        cases=[("import json\njson.loads('PRIVATE_ORIGINAL_TEXT_42')\n", 'INPUT_FORMAT_ERROR'),
               ("import re\nre.compile('(?<PRIVATE_GROUP>text)')\n", 'PATTERN_SYNTAX_ERROR'),
               ("from decimal import Decimal\nDecimal('PRIVATE_AMOUNT_42')\n", 'NUMERIC_TYPE_MISMATCH')]
        for source, expected in cases:
            with self.subTest(expected=expected):
                result, diagnostic, content=self.execute(source)
                self.assertNotEqual(0,result.returncode)
                self.assertEqual(expected,diagnostic['diagnosticCode'])
                self.assertEqual('PASSED',diagnostic['preflightStatus'])
                self.assertNotIn('PRIVATE_',content)


if __name__ == '__main__':
    unittest.main()
