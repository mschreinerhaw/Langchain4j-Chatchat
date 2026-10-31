"""Execute the packaged documentation recipes against synthetic boundary cases."""
import json
import pathlib
import random
import re
import tempfile
import unittest
from decimal import Decimal

import pyarrow as pa
import pyarrow.parquet as pq

ROOT = pathlib.Path(__file__).resolve().parents[1] / "skills/python-data-analysis/references"


def recipe(file_name, marker):
    text = (ROOT / file_name).read_text(encoding="utf-8")
    block = next(code for code in re.findall(r"```python\n(.*?)\n```", text, re.S) if marker in code)
    namespace = {}
    exec(compile(block, str(ROOT / file_name), "exec"), namespace)
    return namespace


METHODS = recipe("financial-reporting.md", "runtime-os-financial-methods-v1")
AMOUNT = METHODS["financial_amount"]
RECONCILE = METHODS["reconcile_components"]
ORDERED = METHODS["ordered_balances"]
CANDIDATES = METHODS["candidate_matrix"]
COMPOSITION = METHODS["percentage_composition"]
SIGNED = METHODS["signed_amount_matrix"]
D = Decimal


class FinancialMethodTests(unittest.TestCase):
    def test_all_component_labels_and_exact_decimal_sum_are_preserved(self):
        result = RECONCILE([("持仓小计", D("0.1")), ("清仓小计", D("0.2"))], "资产当日盈亏", D("0.3"))
        self.assertEqual(result["label"], "持仓小计 + 清仓小计 vs 资产当日盈亏")
        self.assertEqual(result["left"], D("0.3"))
        self.assertEqual(result["difference"], 0)
        self.assertTrue(result["arithmetic_match"])
        self.assertEqual(result["status"], "UNRESOLVED")

    def test_missing_component_is_not_silently_zero(self):
        result = RECONCILE([("a", None), ("b", D(2))], "total", D(2))
        self.assertIsNone(result["left"])
        self.assertIsNone(result["difference"])
        self.assertIsNone(result["arithmetic_match"])

    def test_confirmed_formula_can_report_a_computed_difference(self):
        result = RECONCILE([("a", D(2))], "total", D(3), basis_confirmed=True)
        self.assertEqual(result["difference"], -1)
        self.assertEqual(result["status"], "FAILED")

    def test_tolerance_does_not_replace_the_original_difference(self):
        result = RECONCILE([("a", D("1.001"))], "total", D(1), tolerance=D("0.01"), basis_confirmed=True)
        self.assertEqual(result["difference"], D("0.001"))
        self.assertEqual(result["status"], "PASSED")

    def test_high_precision_inputs_are_not_rounded_by_the_default_context(self):
        large = D("123456789012345678901234567890123456.01")
        result = RECONCILE([("a", large), ("b", D("0.01"))], "total", "123456789012345678901234567890123456.02")
        self.assertEqual(result["difference"], 0)

    def test_component_labels_cannot_be_missing_or_ambiguous(self):
        for components in [[], [("", 1)], [("a", 1), ("a", 2)]]:
            with self.assertRaises(ValueError):
                RECONCILE(components, "total", 1)

    def test_money_rejects_binary_floats_booleans_and_nonfinite_values(self):
        for value in [0.1, True, D("NaN"), D("Infinity"), "not a number"]:
            with self.assertRaises((ValueError, TypeError, ArithmeticError)):
                AMOUNT(value)
        self.assertEqual(AMOUNT(0), D(0))
        self.assertIsNone(AMOUNT(None))

    def test_negative_tolerance_is_not_a_valid_policy(self):
        with self.assertRaises(ValueError):
            RECONCILE([("a", 1)], "b", 1, tolerance=-1)
        with self.assertRaises(ValueError):
            CANDIDATES([], group_key=lambda r: "a", observed=lambda r: 1, candidates={}, tolerance=-1)

    def flow(self, rows, **overrides):
        options = dict(group_key=lambda r: (r["account"],), order_key=lambda r: (r.get("time"),),
                       balance=lambda r: r["balance"], delta=lambda r: r["delta"])
        options.update(overrides)
        return ORDERED(rows, **options)

    def test_tied_records_have_no_last_balance_even_after_permutation(self):
        rows = [dict(account="a", time=1, balance=D(i), delta=D(1)) for i in range(27)]
        expected = self.flow(rows)
        for seed in range(12):
            random.Random(seed).shuffle(rows)
            self.assertEqual(self.flow(rows), expected)
        item = expected[0]
        self.assertIsNone(item["last_observed_balance"])
        self.assertFalse(item["last_order_is_unique"])
        self.assertEqual(item["checked_intervals"], 0)
        self.assertEqual(item["tied_key_groups"], 1)
        self.assertEqual(item["status"], "UNRESOLVED")

    def test_tie_does_not_even_read_an_arbitrary_balance(self):
        rows = [dict(account="a", time=1) for _ in range(3)]
        def forbidden(_):
            self.fail("A tied group has no permissible last balance")
        self.assertIsNone(self.flow(rows, balance=forbidden, delta=forbidden)[0]["last_observed_balance"])

    def test_unique_order_is_invariant_to_input_array_order(self):
        rows = [dict(account="a", time=2, balance=D(12), delta=D(2)),
                dict(account="a", time=1, balance=D(10), delta=D(0))]
        result = self.flow(rows, basis_confirmed=True)[0]
        self.assertEqual(result["last_observed_balance"], 12)
        self.assertEqual(result["checked_intervals"], 1)
        self.assertEqual(result["residuals"], [D(0)])
        self.assertEqual(result["status"], "PASSED")
        self.assertEqual(self.flow(rows), self.flow(list(reversed(rows))))

    def test_missing_order_prevents_selecting_a_known_row_as_last(self):
        rows = [dict(account="a", time=2, balance=D(12), delta=D(2)),
                dict(account="a", time=None, balance=D(10), delta=D(0))]
        result = self.flow(rows)[0]
        self.assertIsNone(result["last_observed_balance"])
        self.assertEqual(result["checked_intervals"], 0)

    def test_different_accounts_do_not_become_adjacent_rows(self):
        rows = [dict(account="a", time=1, balance=D(10), delta=D(1)),
                dict(account="b", time=2, balance=D(80), delta=D(1))]
        self.assertEqual([r["checked_intervals"] for r in self.flow(rows)], [0, 0])

    def test_missing_balance_does_not_create_a_zero_value_interval(self):
        rows = [dict(account="a", time=1, balance=None, delta=D(1)),
                dict(account="a", time=2, balance=D(2), delta=D(2))]
        result = self.flow(rows, basis_confirmed=True)[0]
        self.assertEqual(result["checked_intervals"], 0)
        self.assertEqual(result["status"], "UNRESOLVED")

    def test_single_observation_does_not_certify_continuity(self):
        item = self.flow([dict(account="a", time=1, balance=D(1), delta=D(0))], basis_confirmed=True)[0]
        self.assertEqual(item["status"], "UNRESOLVED")
        self.assertNotIn("closing_balance", item)

    def test_every_candidate_is_evaluated_in_every_direction_without_confirming_meaning(self):
        rows = [dict(side="BUY", amount=D(10), fee=D(1), raw=D(-11)),
                dict(side="SELL", amount=D(10), fee=D(1), raw=D(9))]
        result = CANDIDATES(rows, group_key=lambda r: r["side"], observed=lambda r: r["raw"],
                            candidates={"negative plus costs": lambda r: -(r["amount"] + r["fee"]),
                                        "positive minus costs": lambda r: r["amount"] - r["fee"]})
        matrix = {(r["group"], r["candidate"]): r["matches"] for r in result}
        self.assertEqual(matrix, {("BUY", "negative plus costs"): 1, ("BUY", "positive minus costs"): 0,
                                  ("SELL", "negative plus costs"): 0, ("SELL", "positive minus costs"): 1})
        self.assertTrue(all(not r["meaning_confirmed"] for r in result))

    def test_candidate_missing_and_invalid_rows_remain_visible(self):
        rows = [dict(raw=None), dict(raw="bad"), dict(raw=D(2))]
        item = CANDIDATES(rows, group_key=lambda r: "g", observed=lambda r: r["raw"],
                          candidates={"two": lambda r: D(2)})[0]
        self.assertEqual((item["records"], item["comparable"], item["matches"], item["missing"], item["invalid"]), (3, 1, 1, 1, 1))

    def test_transport_parquet_maps_columns_and_preserves_json_decimals_missing_and_null(self):
        decode = recipe("data-methods.md", "runtime-os-parquet-mapping-v1")["logical_parquet_rows"]
        columns = [{"name": "datasetReference", "storageName": "c0", "type": "STRING"},
                   {"name": "record", "storageName": "c1", "type": "JSON"}]
        rows = [dict(c0="source", p0=True, c1='{"id":"0001","amount":0.10000000000000000001}', p1=True, extra="ignored"),
                dict(c0="source", p0=True, c1=None, p1=False, extra="ignored"),
                dict(c0="source", p0=True, c1=None, p1=True, extra="ignored")]
        with tempfile.TemporaryDirectory() as directory:
            part = pathlib.Path(directory) / "part.parquet"
            pq.write_table(pa.Table.from_pylist(rows), part)
            actual = list(decode(part, {"columns": columns}, {"presenceColumns": {"c0": "p0", "c1": "p1"}}))
        self.assertEqual(actual[0]["record"]["id"], "0001")
        self.assertEqual(actual[0]["record"]["amount"], D("0.10000000000000000001"))
        self.assertNotIn("record", actual[1])
        self.assertIsNone(actual[2]["record"])
        self.assertNotIn("extra", actual[0])

    def test_composition_total_uses_unrounded_values_not_rounded_row_sum(self):
        result = COMPOSITION([("a", "1"), ("b", "1"), ("c", "1")])
        self.assertEqual([r["display"] for r in result["items"]], ["33.33"] * 3)
        self.assertEqual(result["total_display"], "100.00")
        self.assertEqual(result["display_sum"], D("99.99"))
        self.assertEqual(result["display_difference"], D("-0.01"))

    def test_composition_discloses_positive_half_up_rounding_tail(self):
        result = COMPOSITION([("a", "1"), ("b", "31")])
        self.assertEqual([r["display"] for r in result["items"]], ["3.13", "96.88"])
        self.assertEqual(result["total_display"], "100.00")
        self.assertEqual(result["display_difference"], D("0.01"))

    def test_missing_weight_does_not_become_zero_or_a_partial_denominator(self):
        result = COMPOSITION([("a", "1"), ("b", None)])
        self.assertIsNone(result["denominator"])
        self.assertIsNone(result["total_percentage"])
        self.assertTrue(all(r["display"] is None for r in result["items"]))

    def test_zero_weight_denominator_has_no_fabricated_percentage(self):
        result = COMPOSITION([("a", "0"), ("b", "0")])
        self.assertEqual(result["denominator"], 0)
        self.assertIsNone(result["total_display"])
        self.assertEqual(result["status"], "UNRESOLVED")

    def test_negative_composition_is_rejected(self):
        with self.assertRaises(ValueError):
            COMPOSITION([("a", "2"), ("b", "-1")])

    def test_percentage_display_precision_is_bounded_and_not_boolean(self):
        for places in [True, -1, 9, "2"]:
            with self.assertRaises(ValueError):
                COMPOSITION([("a", "1")], places=places)

    def test_composition_preserves_large_original_decimal_values(self):
        raw = "123456789012345678901234567890123456.01"
        result = COMPOSITION([("a", raw), ("b", "0.01")])
        self.assertEqual(result["items"][0]["value"], D(raw))
        self.assertEqual(result["denominator"], D("123456789012345678901234567890123456.02"))
        self.assertEqual(result["total_percentage"], 100)

    def signed(self, rows, **changes):
        args = dict(group_field="side", observed_field="raw", amount_field="amount",
                    fee_fields=["fee"], declared_fields=["side", "raw", "amount", "fee"])
        args.update(changes)
        return SIGNED(rows, **args)

    def test_signed_matrix_binds_observed_field_and_all_six_candidates_in_each_group(self):
        rows = [dict(side="BUY", raw="-11", amount="10", fee="1"),
                dict(side="SELL", raw="9", amount="10", fee="1")]
        result = self.signed(rows)
        self.assertEqual(len(result), 12)
        matched = {(r["group"], r["candidate"]): r["matches"] for r in result if r["matches"]}
        self.assertEqual(matched, {("BUY", "-amount-(fee)"): 1, ("SELL", "amount-(fee)"): 1})
        self.assertTrue(all(r["observed_field"] == "raw" and r["fee_fields"] == ["fee"] for r in result))
        self.assertTrue(all(not r["meaning_confirmed"] for r in result))

    def test_signed_missing_fee_is_not_zero_in_cost_candidates(self):
        result = self.signed([dict(side="BUY", raw="-10", amount="10", fee=None)])
        cost = [r for r in result if "(" in r["candidate"]]
        self.assertEqual(len(cost), 4)
        self.assertTrue(all(r["missing"] == 1 and r["comparable"] == 0 for r in cost))
        self.assertTrue(all(r["comparable"] == 1 for r in result if "(" not in r["candidate"]))

    def test_signed_undeclared_field_is_rejected_even_when_its_raw_value_is_zero(self):
        with self.assertRaises(ValueError):
            self.signed([dict(side="BUY", raw="-11", amount="10", fee="1", unknown="0")], fee_fields=["fee", "unknown"])

    def test_signed_observed_or_amount_field_cannot_be_counted_as_a_fee(self):
        for field in ["raw", "amount"]:
            with self.assertRaises(ValueError):
                self.signed([], fee_fields=[field])

    def test_signed_empty_or_implicit_fee_sequence_is_rejected(self):
        for value in [[], "fee", iter(["fee"])]:
            with self.assertRaises(ValueError):
                self.signed([], fee_fields=value)

    def test_signed_nonfinite_observed_values_remain_explicitly_invalid(self):
        result = self.signed([dict(side="BUY", raw="NaN", amount="10", fee="1")])
        self.assertEqual(len(result), 6)
        self.assertTrue(all(r["invalid"] == 1 and r["comparable"] == 0 for r in result))


if __name__ == "__main__":
    unittest.main(verbosity=2)
