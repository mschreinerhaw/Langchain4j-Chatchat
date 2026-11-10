import json
import os
import hashlib
import pathlib
import subprocess
import sys
import tempfile
import unittest

RUNNER = pathlib.Path(__file__).parents[2] / 'main/resources/python/runtime_capture.py'


class StreamBoundaryTest(unittest.TestCase):
    def execute(self, source, preserve_stderr=False):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            script = root / 'main.py'
            diagnostic = root / 'diagnostic.json'
            script.write_text(source, encoding='utf-8')
            env = {**os.environ, 'RUNTIME_CAPTURE_TASK_STDERR': '1' if preserve_stderr else '0'}
            result = subprocess.run([sys.executable, str(RUNNER), str(script), str(diagnostic)], capture_output=True, env=env)
            self.last_stderr = (root / 'stderr.txt').read_bytes() if (root / 'stderr.txt').exists() else None
            content = diagnostic.read_text()
            return result, json.loads(content), content

    def test_explicit_native_capture_preserves_the_actual_long_error_without_stdout(self):
        result, diagnostic, content = self.execute("print('private stdout')\nraise RuntimeError('ACTUAL_REPAIR_MARKER_' + 'x' * 20000)\n", True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b'', result.stdout)
        self.assertEqual(b'', result.stderr)
        self.assertIn(b'RuntimeError: ACTUAL_REPAIR_MARKER_', self.last_stderr)
        self.assertGreater(len(self.last_stderr), 20000)
        self.assertEqual(diagnostic['stderrBytes'], len(self.last_stderr))
        self.assertEqual(diagnostic['stderrSha256'], hashlib.sha256(self.last_stderr).hexdigest())
        self.assertEqual('TASK_ERROR_DETAIL_V2', diagnostic['policy'])
        self.assertFalse(diagnostic['stderrTruncated'])
        self.assertNotIn('private stdout', content)
        self.assertNotIn(b'private stdout', self.last_stderr)

    def test_error_resource_boundary_is_explicit_and_preserves_original_metering(self):
        result, diagnostic, _ = self.execute("import os\nos.write(2, b'x' * (5 * 1024 * 1024))\n", True)
        self.assertEqual(0, result.returncode)
        self.assertEqual(4 * 1024 * 1024, len(self.last_stderr))
        self.assertEqual(5 * 1024 * 1024, diagnostic['stderrBytes'])
        self.assertEqual(len(self.last_stderr), diagnostic['stderrCapturedBytes'])
        self.assertTrue(diagnostic['stderrTruncated'])

    def test_preflight_failure_keeps_actual_missing_import_in_explicit_mode(self):
        result, diagnostic, _ = self.execute("print('must not run')\nimport missing_actual_probe_20261110\n", True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('FAILED', diagnostic['preflightStatus'])
        self.assertIn(b'missing_actual_probe_20261110', self.last_stderr)
        self.assertEqual(0, diagnostic['stdoutBytes'])

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


    def test_mixed_message_trailing_requirements_have_specific_safe_json_feedback(self):
        result, diagnostic, content = self.execute("import json\njson.loads('{\"rows\":[1,2]} PRIVATE_REQUIREMENT_94')\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('JSONDecodeError', diagnostic['exceptionType'])
        self.assertEqual('JSON_TRAILING_CONTENT', diagnostic['diagnosticCode'])
        self.assertEqual(2, diagnostic['line'])
        self.assertNotIn('PRIVATE_REQUIREMENT_94', content)
        self.assertNotIn('rows', content)

if __name__ == '__main__':
    unittest.main()
