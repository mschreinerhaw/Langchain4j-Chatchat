import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('assembly', Path(__file__).resolve().parents[1]/'assemble-runtime-refactor-stage.py')
assembly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembly)

class RuntimeRefactorAssemblyTest(unittest.TestCase):
    def test_shared_credentials_and_unrelated_search_config_are_preserved(self):
        before = (b'chatchat:\r\n  search:\r\n    problem-analysis:\r\n      final-evidence-limit: 5\r\n'
                  b'      bge-reranker:\r\n        enabled: true\r\n        api-key: retired\r\n'
                  b'    opensearch:\r\n      embedding:\r\n        api-key: keep-shared\r\n'
                  b'        model: keep-model\r\n        vector-field: contentVector\r\n'
                  b'  model-api:\r\n    vector-weight: keep-unrelated\r\n'
                  b'spring:\r\n  search:\r\n    opensearch:\r\n      vector-field: keep-spring\r\n')
        after, removed = assembly.remove_document_rag_config(before)
        self.assertEqual(removed, ['bge-reranker', 'vector-field'])
        self.assertIn(b'api-key: keep-shared\r\n', after)
        self.assertIn(b'model: keep-model\r\n', after)
        self.assertIn(b'vector-weight: keep-unrelated\r\n', after)
        self.assertIn(b'vector-field: keep-spring\r\n', after)
        self.assertNotIn(b'api-key: retired', after)
        self.assertEqual(assembly.remove_document_rag_config(after), (after, []))

    def test_non_search_resources_keep_exact_bytes(self):
        before = b'models:\n  api-key: unchanged\n  vector-field: unrelated\n'
        self.assertEqual(assembly.remove_document_rag_config(before), (before, []))

if __name__ == '__main__':
    unittest.main()
