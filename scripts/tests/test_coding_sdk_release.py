import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'prepare-coding-sdk-release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def archive(files):
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w') as z:
        for name, value in files.items():
            z.writestr(name, value)
    return result.getvalue()


class ReleaseCompatibilityTest(unittest.TestCase):
    def test_preserves_deployed_classes_absent_from_checkout(self):
        retained = {'com/chatchat/common/mcp/capability/McpDataSupplyPolicy.class': b'deployed extension',
                    'META-INF/MANIFEST.MF': b'deployed version', 'unrelated.schema.json': b'original schema'}
        target = 'com/chatchat/common/runtime/coding/ExecutionFeedback.class'
        before = archive(dict(retained, **{target: b'old code'}))
        compiled = archive({target: b'new code', 'runtime/coding/execution-feedback.schema.json': b'new optional metrics schema'})
        with zipfile.ZipFile(io.BytesIO(release.overlay_module(before, compiled, 'common'))) as result:
            for name, value in retained.items():
                self.assertEqual(result.read(name), value)
            self.assertEqual(result.read(target), b'new code')

    def test_does_not_replace_unrelated_agents_or_module_metadata(self):
        key = 'com/chatchat/agents/orchestration/model/ToolExecutionModels.class'
        before = archive({key: b'old', 'com/chatchat/agents/other/ExistingFeature.class': b'keep'})
        compiled = archive({key: b'bounded', 'com/chatchat/agents/other/ExistingFeature.class': b'not in scope'})
        with zipfile.ZipFile(io.BytesIO(release.overlay_module(before, compiled, 'agents'))) as result:
            self.assertEqual(result.read(key), b'bounded')
            self.assertEqual(result.read('com/chatchat/agents/other/ExistingFeature.class'), b'keep')


if __name__ == '__main__':
    unittest.main()
