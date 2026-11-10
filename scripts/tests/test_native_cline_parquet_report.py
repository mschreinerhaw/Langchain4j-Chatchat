import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CHECKER = Path(__file__).resolve().parents[1] / 'verify-native-cline-parquet-report.py'


class ReportAuditTest(unittest.TestCase):
    def audit(self, text):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline = {'rows': 10, 'columns': 1, 'duplicateRowsExcludingFirst': 0,
                        'pandasDtypes': {'x': 'float64'}, 'nulls': {'x': 2},
                        'numericDescribe': {'x': {'count': 8, 'mean': 1.592247}}}
            supplement = {'sourceColumns': ['x'], 'categoricalCounts': {}, 'numericQuantiles': {},
                          'correlations': {}, 'nonNullCounts': {'x': 8}, 'uniqueCounts': {'x': 4},
                          'passengerOnePercentAll': 74.685075586, 'passengerOnePercentNonNull': 81.377893113}
            for name, data in [('baseline', baseline), ('supplement', supplement)]:
                (root / (name + '.json')).write_text(json.dumps(data), encoding='utf-8')
            (root / 'analysis.py').write_text('print("actual code is audited separately")', encoding='utf-8')
            (root / 'report.md').write_text(text, encoding='utf-8')
            result = subprocess.run([sys.executable, str(CHECKER), str(root/'baseline.json'),
                                     str(root/'supplement.json'), str(root/'analysis.py'),
                                     str(root/'report.md'), str(root/'audit.json')], capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            return json.loads((root/'audit.json').read_text(encoding='utf-8'))

    def test_false_column_count_and_wrong_percent_are_retained_as_failures(self):
        result = self.audit('| 指标 | 值 |\n|---|---|\n| 列数 | 2 |\n\n绝大多数为 1 人（81.27%）')
        self.assertEqual('FAILED', result['status'])
        self.assertEqual(2, len(result['mismatches']))

    def test_anomaly_predicate_count_is_not_compared_to_entire_column_count(self):
        result = self.audit('| Field | Description | Count |\n|---|---|---|\n| x | x = 0 | 2 |')
        self.assertEqual([], result['mismatches'])
        self.assertEqual('REVIEW_REQUIRED', result['status'])

    def test_display_rounding_and_partial_parser_never_manufacture_a_pass(self):
        result = self.audit('| Column | Count | Mean |\n|---|---|---|\n| x | 8 | 1.5922 |')
        self.assertEqual([], result['mismatches'])
        self.assertEqual(2, result['passedChecks'])
        self.assertEqual('REVIEW_REQUIRED', result['status'])


if __name__ == '__main__':
    unittest.main()
