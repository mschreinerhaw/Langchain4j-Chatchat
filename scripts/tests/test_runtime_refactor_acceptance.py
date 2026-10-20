import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).resolve().parents[1]/'verify-cline-monitor-stage.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)

class CodingPublicationAcceptanceTest(unittest.TestCase):
    def test_only_coding_owned_publication_without_a_java_model_call_is_admitted(self):
        self.assertTrue(acceptance.coding_owned_publication({'type': 'final_summary',
            'workflow': 'coding_owned_analysis', 'modelCalls': 0}))
        self.assertFalse(acceptance.coding_owned_publication({'eventKind': 'CODING_SEMANTIC_REVIEW', 'eventState': 'SUPPORTED'}))
        self.assertFalse(acceptance.coding_owned_publication({'type': 'final_summary', 'workflow': 'model_native_harness', 'modelCalls': 0}))
        self.assertFalse(acceptance.coding_owned_publication({'type': 'final_summary', 'workflow': 'coding_owned_analysis', 'modelCalls': 1}))

if __name__ == '__main__':
    unittest.main()
