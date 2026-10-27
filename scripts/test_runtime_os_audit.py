import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('runtime_audit', Path(__file__).with_name('audit-runtime-os.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class RuntimeAuditTest(unittest.TestCase):
    def test_console_overlay_preserves_other_bytes_and_requires_an_explicit_addition_scope(self):
        import io
        import zipfile
        spec = importlib.util.spec_from_file_location('deployment', Path(__file__).with_name('deploy-runtime-template-supply.py'))
        deployment = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(deployment)
        before = io.BytesIO()
        with zipfile.ZipFile(before, 'w') as archive:
            archive.writestr('BOOT-INF/classes/static/index.html', b'old entry')
            archive.writestr('BOOT-INF/lib/unchanged.jar', b'unchanged backend')
        changes = {'BOOT-INF/classes/static/index.html': b'new entry',
                   'BOOT-INF/classes/static/assets/monitor.js': b'new monitor'}
        with self.assertRaises(AssertionError):
            deployment.replace_entries(before.getvalue(), changes)
        after = deployment.replace_entries(before.getvalue(), changes,
            allowed_new_prefixes=('BOOT-INF/classes/static/',))
        with zipfile.ZipFile(io.BytesIO(after)) as archive:
            self.assertEqual(archive.read('BOOT-INF/lib/unchanged.jar'), b'unchanged backend')
            self.assertEqual(archive.read('BOOT-INF/classes/static/assets/monitor.js'), b'new monitor')

    def test_unknown_tokens_are_not_zero_and_duplicate_trace_delivery_is_not_another_call(self):
        call = {'phase': 'selection', 'operationId': 'selection:1', 'status': 'FAILED', 'durationMs': 180000}
        result = audit.summarize([{'runId': 'run', 'status': 'FAILED', 'modelCalls': [call, call]}])
        selection = result['phases']['selection']
        self.assertEqual(selection['observedCalls'], 1)
        self.assertEqual(selection['usage']['inputTokens']['unknownCalls'], 1)
        self.assertIsNone(selection['usage']['inputTokens']['observedTotal'])
        self.assertIsNone(selection['p95Ms'])
        self.assertEqual(len(result['duplicateObservations']), 1)

    def test_operation_identity_is_scoped_to_phase_and_run(self):
        call = {'phase': 'selection', 'operationId': 'model:1', 'inputTokens': 4, 'outputTokens': 2}
        other = {**call, 'phase': 'coding'}
        result = audit.summarize([{'runId': 'one', 'modelCalls': [call, other]},
                                  {'runId': 'two', 'modelCalls': [call]}])
        self.assertEqual(result['phases']['selection']['observedCalls'], 2)
        self.assertEqual(result['phases']['selection']['usage']['inputTokens']['observedTotal'], 8)
        self.assertFalse(result['duplicateObservations'])

    def test_only_identical_snapshots_and_successful_runs_are_comparable(self):
        before = {'runId': 'before', 'status': 'SUCCEEDED', 'caseId': 'case', 'snapshotSha256': 'a', 'variant': 'before'}
        after = {**before, 'runId': 'after', 'variant': 'after', 'snapshotSha256': 'b'}
        self.assertFalse(audit.summarize([before, after])['comparableFixedSnapshots'])
        after['snapshotSha256'] = 'a'
        self.assertEqual(len(audit.summarize([before, after])['comparableFixedSnapshots']), 1)
        after['status'] = 'CANCELLED'
        result = audit.summarize([before, after])
        self.assertFalse(result['comparableFixedSnapshots'])
        self.assertEqual(result['successRate'], .5)

    def test_p95_requires_twenty_actual_durations(self):
        calls = [{'phase': 'coding', 'operationId': str(i), 'durationMs': i} for i in range(1, 21)]
        result = audit.summarize([{'runId': 'run', 'modelCalls': calls}])
        self.assertEqual(result['phases']['coding']['p95Ms'], 19)


if __name__ == '__main__':
    unittest.main()
