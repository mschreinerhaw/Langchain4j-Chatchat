import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import json
import unittest

CAPTURE = Path(__file__).resolve().parents[2] / 'chatchat-mcp-server/src/main/resources/python/runtime_capture.py'


class PythonRuntimeDiagnosticTest(unittest.TestCase):
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


if __name__ == '__main__': unittest.main()
