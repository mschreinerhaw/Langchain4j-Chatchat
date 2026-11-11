"""Verifier feedback discloses actual failures, never private golden answers."""
import unittest
from native_silo_control import feedback_for

class NativeSiloFeedbackTest(unittest.TestCase):
    def test_wrong_json_type_reports_actual_output_without_exporting_golden_answer(self):
        response=feedback_for({'facts':{'expected':{'transactionQuantity':'731968254'},'faults':['METRIC_transactionQuantity'],
            'metricChecks':[{'field':'transactionQuantity','passed':False,'expectedType':'DECIMAL_STRING','actualType':'NUMBER','actualValuePreview':'41','diagnostic':'VALUE_OR_TYPE_MISMATCH','expectedValue':'731968254'}]}},'v2')
        self.assertIn('DECIMAL_STRING',response);self.assertIn('NUMBER',response);self.assertIn('41',response)
        self.assertNotIn('731968254',response);self.assertNotIn('expectedValue',response)

    def test_scope_failure_does_not_request_another_model_dispatch(self):
        self.assertIsNone(feedback_for({'facts':{'faults':['INDEPENDENT_INPUT_VERIFICATION_ERROR:SOURCE_BINDING_UNAVAILABLE']}},'v1'))

    def test_actual_report_file_is_identified_without_promoting_auxiliary_notes(self):
        response=feedback_for({'facts':{'faults':['REPORT_SEMANTIC_DRYK_UNDECLARED_FLOATING'],
            'reportChecks':[{'path':'reports/account-review.md','faults':['REPORT_SEMANTIC_DRYK_UNDECLARED_FLOATING']},{'path':'reports/other-valid.md','faults':[]}]}},'v3')
        self.assertIn('reports/account-review.md',response);self.assertNotIn('reports/other-valid.md',response);self.assertIn('AUXILIARY',response)

    def test_trade_count_feedback_returns_the_reported_value_without_the_independent_answer(self):
        response=feedback_for({'facts':{'faults':['REPORT_SEMANTIC_TRADE_COUNT_UNSUPPORTED'],
            'reportClaimChecks':[{'path':'report.md','claimType':'EXECUTED_SECURITY_COUNT','reportedCount':14,'observedCount':731968254,'passed':False,'diagnostic':'REPORT_SEMANTIC_TRADE_COUNT_UNSUPPORTED'}]}},'v2')
        self.assertIn('EXECUTED_SECURITY_COUNT',response);self.assertIn('reportedCount',response);self.assertIn('14',response)
        self.assertNotIn('731968254',response);self.assertNotIn('observedCount',response)

if __name__=='__main__':unittest.main()
