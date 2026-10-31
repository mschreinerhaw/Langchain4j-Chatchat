# Financial charts and reports

Use the Runtime's visual-report contract and the delivered business definitions. These are presentation and verification methods, not permission to query financial systems or invent metric meanings.

## Scope and numerical integrity

- Identify the customer/portfolio, currency, reporting window and actual as-of date. Keep valuation, order, fill and settlement dates distinct. Align comparable periods and market calendars before plotting; missing periods are gaps, not zero activity. Never use an empty result for one period to assert no activity in another.
- Confirm monetary units and scale from producer metadata or explicit user instructions. Label axes, table headers and captions consistently, including conversions to ten-thousand/hundred-million units. An unspecified currency stays unspecified. Do not combine different currencies without an authorized exchange-rate source, date and conversion basis.
- Calculate money with Decimal or declared exact decimal types; round only for display. Preserve the supplied precision, show thousands separators where useful, and do not conceal a reconciliation difference through display rounding. Distinguish ratios expressed as fractions from percentages; percentage-point and basis-point changes are different from relative percent change.
- Keep orders, fills, cancellations, positions and settlement records separate. Cash flow is not investment return; realized P&L, unrealized P&L, fees, tax and valuation effects are not interchangeable. State return/benchmark methodology only when its inputs and definitions are present. Do not derive a stable trading strategy or risk preference from one observation window.

## Figure selection and appearance

- Use white/light backgrounds, restrained blue/gray structural colors, subtle grids and legible Chinese fonts already installed in the Sandbox. Keep a consistent series palette across panels; avoid 3D, gradients, decoration and oversized metrics without context. Check readability in grayscale, at report-page size and after export.
- Use time-series lines for aligned historical trends; separate panels for amounts and rates; horizontal bars for rankings or exposures; diverging bars for signed P&L or differences. Use a waterfall only for a documented bridge whose components reconcile to the start/end totals. Do not invent missing bridge components.
- For signed bars, show zero, preserve losses and use computed signed labels. Bar axes start at zero. Disclose focused line-chart ranges; do not use logarithmic scales with nonpositive values. Do not use dual axes to suggest unsupported correlation.
- State the market convention for price/return direction: mainland-China charts normally show red for rises and green for falls; international conventions vary. An increase in cost, liability or risk is not automatically favorable. For unrelated categorical series use neutral distinct colors. Include signs, labels or line patterns so color is not the only signal.
- Composition charts require a few nonnegative components sharing the same currency, date, scope and total; verify their sum and show any documented remainder. Do not put negative P&L in a pie chart or compare independent totals as parts of a whole. Prefer bars when categories are numerous or close in size.
- Add benchmarks, regulatory/portfolio limits, targets or confidence bands only with an explicit source or calculated method. Do not fabricate reference lines. Use a table when a chart would conceal missing evidence or exaggerate a small sample.

## Report and reconciliation review

Lead with the principal finding and covered period, then evidence charts/tables, calculation basis and material limitations. Each chart needs a descriptive title, labelled axes/unit, date/window, series legend, exact registered data-source references and any necessary scope note. Follow the existing rule keeping technical paths, task IDs and hashes out of narrative.

Build figures, table values and prose from the same computed objects. A reconciliation table should name both sides, their unit/period/grain, the declared formula, calculated difference, stated tolerance and check status. Derive status and labels from the calculation; never hardcode a zero difference, a liability value or a successful check. Distinguish an observed arithmetic difference from a confirmed business error; show missing fees, settlement timing, flow or other components as unresolved rather than zero.

Inspect the actual rendered chart at its intended report size: clipped labels, scientific notation, missing minus signs, overcrowded legends and misleading scales can invalidate an otherwise correct calculation. Export static charts through the existing registered artifact paths with enough resolution for reading. Keep the contract's INPUT_REQUIRED/UNCONFIRMED restrictions and preserve supported text when a chart cannot be justified.

## Reusable calculation recipes

Inline the applicable functions below unchanged in the proposed Python source, then add task-specific field bindings and rendering. They are stateless examples with no file, network or execution access. They are not an installed Runtime module. Pass Decimal values decoded from the original text; binary floats and nonfinite values are rejected. Precision 128 covers the Runtime's declared maximum 38-digit inputs and their sums/products.

Use the returned reconciliation label and component rows everywhere: a sum of several source groups must name every group, not just one. A zero residual is an arithmetic observation, not independent business certification. Set `basis_confirmed=True` only with explicit compatible definitions, scope, units and precision; otherwise leave it false and explain the conditional arithmetic result.

For cash flows, supply declared, normalized group/order keys (for example account, currency and date, then the actual timestamp/sequence). If a key is missing or tied, a footnote does not justify displaying an arbitrary last-row balance. Render `None` as unavailable, not zero. A unique last *observed* balance still does not establish an official day-end balance or complete ledger coverage.

For unconfirmed field meanings, give `candidate_matrix` **every** justified candidate expression; it evaluates their full Cartesian product with the actual operation groups. Expressions may differ by buy/sell or other direction. A failed candidate is not a confirmed data error; a matching candidate is not a confirmed business meaning. Do not relabel a raw fee field as settlement cash or include it in normal fees on this basis.

For nonnegative portfolio weights use `percentage_composition`: its total uses the unrounded denominator, while `display_sum` and `display_difference` expose the rounding tail. Use its returned `total_display` for the total row, never the sum of rounded rows. Missing values or a zero denominator do not produce a fabricated zero percentage.

When testing an unconfirmed amount against signed transaction amount and declared costs, use `signed_amount_matrix`. Bind actual field names from the task's dictionary, including the **observed field**; it reads that field itself and carries its identity into every result. It supplies all six sign/cost candidates for every observed operation group. This avoids accidentally testing a different field or omitting the counterpart formula. Fee fields must be explicitly declared, distinct, and separate from the observed/amount fields. Continue to treat numerical matches as hypotheses only.

```python
# runtime-os-financial-methods-v1
from collections import defaultdict
from decimal import Decimal, localcontext, ROUND_HALF_UP

def financial_amount(value):
    if value is None or value == "":
        return None
    if isinstance(value, (bool, float)) or not isinstance(value, (str, int, Decimal)):
        raise TypeError("Use the original decimal text, Decimal or integer")
    result = value if isinstance(value, Decimal) else Decimal(value)
    if not result.is_finite():
        raise ValueError("Nonfinite financial value")
    return result

def reconcile_components(components, target_label, target_value, *, tolerance=None, basis_confirmed=False):
    labels = [label for label, _ in components]
    if not labels or any(not isinstance(label, str) or not label.strip() for label in labels):
        raise ValueError("Every component requires its source-group label")
    if len(set(labels)) != len(labels) or not target_label:
        raise ValueError("Component labels must be distinct and the target labelled")
    values = [financial_amount(value) for _, value in components]
    target, tol = financial_amount(target_value), financial_amount(tolerance)
    if tol is not None and tol < 0:
        raise ValueError("Negative tolerance")
    result = {"label": " + ".join(labels) + " vs " + target_label,
              "components": list(zip(labels, values)), "target": target,
              "tolerance": tol, "left": None, "difference": None,
              "arithmetic_match": None, "status": "UNRESOLVED"}
    if target is None or any(value is None for value in values):
        return result
    with localcontext() as ctx:
        ctx.prec = 128
        result["left"] = sum(values, Decimal(0))
        result["difference"] = result["left"] - target
        result["arithmetic_match"] = abs(result["difference"]) <= (tol if tol is not None else Decimal(0))
    if basis_confirmed:
        result["status"] = "PASSED" if result["arithmetic_match"] else "FAILED"
    return result

def ordered_balances(rows, *, group_key, order_key, balance, delta, basis_confirmed=False):
    groups = defaultdict(list)
    for row in rows:
        key = group_key(row)
        if not isinstance(key, tuple) or not key or any(value is None for value in key):
            raise ValueError("Supply the declared complete group key")
        groups[key].append(row)
    results = []
    for key, records in groups.items():
        item = {"group": key, "records": len(records), "checked_intervals": 0,
                "tied_key_groups": 0, "last_order_is_unique": False,
                "last_observed_balance": None, "residuals": [], "status": "UNRESOLVED"}
        buckets = defaultdict(list)
        missing_order = False
        for row in records:
            order = order_key(row)
            if not isinstance(order, tuple) or not order or any(value is None for value in order):
                missing_order = True
            else:
                buckets[order].append(row)
        item["tied_key_groups"] = sum(len(bucket) > 1 for bucket in buckets.values())
        try:
            keys = sorted(buckets)
        except TypeError:
            keys, missing_order = [], True
        if not missing_order and keys:
            last = buckets[keys[-1]]
            item["last_order_is_unique"] = len(last) == 1
            if len(last) == 1:
                item["last_observed_balance"] = financial_amount(balance(last[0]))
            with localcontext() as ctx:
                ctx.prec = 128
                for before, after in zip(keys, keys[1:]):
                    if len(buckets[before]) != 1 or len(buckets[after]) != 1:
                        continue
                    a, b = buckets[before][0], buckets[after][0]
                    left, right, change = map(financial_amount, (balance(a), balance(b), delta(b)))
                    if left is not None and right is not None and change is not None:
                        item["residuals"].append(right - left - change)
            item["checked_intervals"] = len(item["residuals"])
            if basis_confirmed and len(records) > 1 and item["checked_intervals"] == len(records) - 1:
                item["status"] = "PASSED" if all(value == 0 for value in item["residuals"]) else "FAILED"
        results.append(item)
    return results

def candidate_matrix(rows, *, group_key, observed, candidates, tolerance=None):
    tol = financial_amount(tolerance)
    if tol is not None and tol < 0:
        raise ValueError("Negative tolerance")
    groups = defaultdict(list)
    for row in rows:
        groups[group_key(row)].append(row)
    results = []
    for group, records in groups.items():
        for name, formula in candidates.items():
            item = {"group": group, "candidate": name, "records": len(records),
                    "comparable": 0, "matches": 0, "missing": 0, "invalid": 0,
                    "meaning_confirmed": False, "tolerance": tol}
            with localcontext() as ctx:
                ctx.prec = 128
                for row in records:
                    try:
                        left, right = financial_amount(observed(row)), financial_amount(formula(row))
                        if left is None or right is None:
                            item["missing"] += 1
                            continue
                        item["comparable"] += 1
                        item["matches"] += abs(left - right) <= (tol if tol is not None else Decimal(0))
                    except (KeyError, ValueError, TypeError, ArithmeticError):
                        item["invalid"] += 1
            results.append(item)
    return results

def percentage_composition(components, *, places=2):
    labels = [label for label, _ in components]
    if not labels or any(not isinstance(label, str) or not label.strip() for label in labels) or len(set(labels)) != len(labels):
        raise ValueError("Supply distinct component labels")
    if isinstance(places, bool) or not isinstance(places, int) or not 0 <= places <= 8:
        raise ValueError("Display precision must be an integer from 0 to 8")
    values = [financial_amount(value) for _, value in components]
    if any(value is not None and value < 0 for value in values):
        raise ValueError("Composition weights require nonnegative components")
    result = {"items": [{"label": label, "value": value, "percentage": None, "display": None}
                        for label, value in zip(labels, values)],
              "denominator": None, "total_percentage": None, "total_display": None,
              "display_sum": None, "display_difference": None, "status": "UNRESOLVED"}
    if any(value is None for value in values):
        return result
    with localcontext() as ctx:
        ctx.prec = 128
        total = sum(values, Decimal(0))
        result["denominator"] = total
        if total == 0:
            return result
        quantum = Decimal(1).scaleb(-places)
        for item in result["items"]:
            item["percentage"] = item["value"] / total * 100
            item["display"] = format(item["percentage"].quantize(quantum, rounding=ROUND_HALF_UP), "f")
        result["total_percentage"] = total / total * 100
        result["total_display"] = format(result["total_percentage"].quantize(quantum), "f")
        result["display_sum"] = sum((Decimal(item["display"]) for item in result["items"]), Decimal(0))
        result["display_difference"] = result["display_sum"] - result["total_percentage"]
        result["status"] = "CALCULATED"
    return result

def signed_amount_matrix(rows, *, group_field, observed_field, amount_field, fee_fields, declared_fields, tolerance=None):
    if not isinstance(fee_fields, (list, tuple)):
        raise ValueError("Supply an explicit sequence of declared fee fields")
    fields = [group_field, observed_field, amount_field, *fee_fields]
    if not fee_fields or any(not isinstance(field, str) or not field.strip() for field in fields) or len(set(fields)) != len(fields):
        raise ValueError("Supply distinct group, observed, amount and declared fee fields")
    if not set(fields).issubset(set(declared_fields)):
        raise ValueError("Do not infer meanings for undeclared fields")
    fee_fields = tuple(fee_fields)
    def amount(row):
        return financial_amount(row.get(amount_field))
    def costs(row):
        values = [financial_amount(row.get(field)) for field in fee_fields]
        return None if any(value is None for value in values) else sum(values, Decimal(0))
    def combine(row, sign, cost_sign):
        value, fee = amount(row), costs(row)
        return None if value is None or fee is None else sign * value + cost_sign * fee
    cost_label = "(" + "+".join(fee_fields) + ")"
    candidates = {"+" + amount_field: amount,
                  "-" + amount_field: lambda row: None if amount(row) is None else -amount(row),
                  amount_field + "+" + cost_label: lambda row: combine(row, 1, 1),
                  amount_field + "-" + cost_label: lambda row: combine(row, 1, -1),
                  "-" + amount_field + "+" + cost_label: lambda row: combine(row, -1, 1),
                  "-" + amount_field + "-" + cost_label: lambda row: combine(row, -1, -1)}
    results = candidate_matrix(rows, group_key=lambda row: row.get(group_field),
                               observed=lambda row: row.get(observed_field), candidates=candidates, tolerance=tolerance)
    for item in results:
        item.update({"observed_field": observed_field, "amount_field": amount_field, "fee_fields": list(fee_fields)})
    return results
```
