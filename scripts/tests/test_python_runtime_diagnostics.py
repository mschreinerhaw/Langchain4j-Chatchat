import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import json
import unittest

CAPTURE = Path(__file__).resolve().parents[2] / 'chatchat-mcp-server/src/main/resources/python/runtime_capture.py'


class PythonRuntimeDiagnosticTest(unittest.TestCase):
    def test_real_syntax_field_and_dependency_failures_keep_only_safe_source_locations(self):
        cases = [('def broken(:\n    pass\n', 'SyntaxError'),
                 ("row={'amount':1}\nprint(row['private_customer_field'])\n", 'KeyError'),
                 ('import runtime_feedback_missing_private_package\n', 'ModuleNotFoundError')]
        for code, expected in cases:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as name:
                root=Path(name); source=root/'main.py'; source.write_text(code,encoding='utf-8'); diagnostic=root/'diagnostic.json'
                result=subprocess.run([sys.executable,str(CAPTURE),str(source),str(diagnostic)],capture_output=True)
                self.assertEqual(result.returncode,1)
                text=diagnostic.read_text(); value=json.loads(text)
                self.assertEqual(value['schemaVersion'],'runtime_os.python_diagnostic.v1')
                self.assertEqual(value['exceptionType'],expected)
                self.assertEqual(value['stack'][-1]['file'],'main.py')
                self.assertGreater(value['stack'][-1]['line'],0)
                self.assertNotIn('private_customer_field',text)
                self.assertNotIn('runtime_feedback_missing_private_package',text)

    def test_large_private_stdout_is_counted_without_becoming_feedback_or_a_log_file(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);source=root/'main.py';source.write_text("print('private_customer_record'*100000)\n",encoding='utf-8')
            diagnostic=root/'diagnostic.json'
            result=subprocess.run([sys.executable,str(CAPTURE),str(source),str(diagnostic)],capture_output=True)
            self.assertEqual(result.returncode,0)
            value=json.loads(diagnostic.read_text());self.assertGreater(value['stdoutBytes'],1000000)
            self.assertLess(diagnostic.stat().st_size,2048)
            self.assertNotIn('private_customer_record',diagnostic.read_text())
            self.assertEqual(result.stdout,b'')

    def test_repeated_historical_scalar_failure_gets_safe_machine_readable_repair_category(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            source = root / 'main.py'
            source.write_text("from decimal import Decimal\nvalues={'synthetic':Decimal('1.23')}\n"
                              "for key,value in sorted(values.items(),key=lambda item:item[1]):\n"
                              "    print(value['amount'])\n", encoding='utf-8')
            output = root / 'runtime-diagnostic.json'
            result = subprocess.run([sys.executable, str(CAPTURE), str(source), str(output)], capture_output=True)
            self.assertEqual(result.returncode, 1)
            diagnostic = json.loads(output.read_text())
            self.assertEqual(diagnostic['diagnosticCode'], 'VALUE_NOT_SUBSCRIPTABLE')
            self.assertEqual(diagnostic['exceptionType'], 'TypeError')
            self.assertEqual(diagnostic['line'], 4)
            self.assertNotIn('1.23', output.read_text())
            self.assertNotIn('synthetic', output.read_text())

    def test_unknown_and_user_supplied_error_text_is_never_copied(self):
        spec = importlib.util.spec_from_file_location('capture', CAPTURE)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        self.assertIsNone(module.diagnostic_code('ValueError: confidential customer data'))
        self.assertEqual(module.diagnostic_code("ModuleNotFoundError: No module named 'private_name'"), 'PACKAGE_NOT_INSTALLED')


class RuntimeDiagnosticTest(unittest.TestCase):
    def test_resources_and_exception_frames_without_raw_streams(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'analysis.py'
            output = Path(folder) / 'diagnostic.json'
            source.write_text('print("private fixture value")\nsum(i*i for i in range(100000))\nraise ValueError("private failure")\n', encoding='utf-8')
            result = subprocess.run([sys.executable, str(CAPTURE), str(source), str(output)], capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, b'')
            self.assertEqual(result.stderr, b'')
            diagnostic = json.loads(output.read_text(encoding='utf-8'))
            self.assertNotIn('private', json.dumps(diagnostic))
            self.assertEqual(diagnostic['exceptionType'], 'ValueError')
            self.assertEqual(diagnostic['stack'][-1], {'file': 'analysis.py', 'line': 3})
            if sys.platform != 'win32':
                self.assertGreaterEqual(diagnostic['cpuTimeMs'], 0)
                self.assertGreater(diagnostic['maxRssBytes'], 0)
            else:
                self.assertNotIn('cpuTimeMs', diagnostic)
                self.assertNotIn('maxRssBytes', diagnostic)


if __name__ == '__main__': unittest.main()
