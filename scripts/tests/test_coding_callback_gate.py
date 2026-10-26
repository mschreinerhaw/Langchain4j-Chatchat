import importlib.util
from pathlib import Path
import subprocess
import unittest

spec = importlib.util.spec_from_file_location('gate', Path(__file__).parents[1] / 'verify-coding-callback.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class CallbackGateTest(unittest.TestCase):
    def test_failed_authentication_prevents_traffic_switch_and_redacts_errors(self):
        switched = []
        def denied(*args, **kwargs):
            return subprocess.CompletedProcess([], 1, '', 'private credential')
        with self.assertRaisesRegex(RuntimeError, '^Candidate callback connectivity/authentication failed$'):
            gate.gated_switch('candidate', lambda: switched.append(True), denied)
        self.assertEqual(switched, [])

    def test_positive_and_negative_authentication_checks_are_both_required(self):
        for response in ['{"status":"PASSED","signedCallback":true}',
                         '{"status":"FAILED","signedCallback":true,"unsignedRejected":true}']:
            with self.subTest(response=response), self.assertRaises(RuntimeError):
                gate.gated_switch('candidate', lambda: self.fail('unverified candidate routed'),
                                  lambda *a, **k: subprocess.CompletedProcess([], 0, response, ''))
        order = []
        def accepted(*args, **kwargs):
            order.append('verified')
            return subprocess.CompletedProcess([], 0,
                '{"status":"PASSED","signedCallback":true,"unsignedRejected":true}', '')
        gate.gated_switch('candidate', lambda: order.append('switched'), accepted)
        self.assertEqual(order, ['verified', 'switched'])


if __name__ == '__main__':
    unittest.main()
