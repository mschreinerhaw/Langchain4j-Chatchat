import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('native_profiles', Path(__file__).with_name('publish-native-model-profile.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeModelPublicationTest(unittest.TestCase):
    def setUp(self):
        self.gateway = {'modelProfiles': {'fixed': {'model': 'provider-default', 'apiKey': 'old-secret', 'baseUrl': 'https://old.invalid'}}, 'tenants': {'tenant': {'models': ['fixed'], 'datasets': ['authorized-only']}}}
        self.executor = {'modelNames': {'fixed': 'provider-default'}, 'tenants': copy.deepcopy(self.gateway['tenants'])}
        self.connection = {'defaultModel': 'default', 'modelProfiles': {'default': 'fixed'}}
        self.row = {'name': 'chosen', 'provider_model': 'actual-provider-model', 'protocol': 'openai', 'enabled': True, 'base_url': 'https://published.invalid'}

    def test_selected_model_uses_its_published_provider_and_credential(self):
        before = copy.deepcopy((self.gateway, self.executor, self.connection))
        profile, gateway, executor, connection = module.prepare('chosen', self.row, self.gateway, self.executor, self.connection, 'new-secret')
        self.assertEqual('actual-provider-model', executor['modelNames'][connection['modelProfiles']['chosen']])
        self.assertEqual('new-secret', gateway['modelProfiles'][profile]['apiKey'])
        self.assertEqual(self.gateway['modelProfiles']['fixed'], gateway['modelProfiles']['fixed'])
        self.assertEqual(['authorized-only'], gateway['tenants']['tenant']['datasets'])
        self.assertEqual(before, (self.gateway, self.executor, self.connection))

    def test_disabled_or_wrong_publication_cannot_be_admitted(self):
        for changes in [{'enabled': False}, {'name': 'other'}, {'protocol': 'unsupported'}, {'base_url': 'http://private.invalid'}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                module.prepare('chosen', dict(self.row, **changes), self.gateway, self.executor, self.connection, 'secret')

    def test_full_chat_endpoint_is_normalized_for_native_transport(self):
        self.row['base_url'] = 'https://published.invalid/compatible-mode/v1/chat/completions/'
        profile, gateway, _, _ = module.prepare('chosen', self.row, self.gateway, self.executor, self.connection, 'secret')
        self.assertEqual('https://published.invalid/compatible-mode/v1', gateway['modelProfiles'][profile]['baseUrl'])

    def test_conflicting_existing_profile_is_not_reassigned(self):
        self.connection['modelProfiles']['chosen'] = 'fixed'
        with self.assertRaises(ValueError):
            module.prepare('chosen', self.row, self.gateway, self.executor, self.connection, 'secret')


if __name__ == '__main__':
    unittest.main()
