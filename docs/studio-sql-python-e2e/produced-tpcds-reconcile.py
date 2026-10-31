import json, os, sys
from decimal import Decimal, InvalidOperation

def _d(v):
    if isinstance(v, Decimal):
        d = v
    else:
        try:
            d = Decimal(str(v))
        except (InvalidOperation, TypeError, ValueError):
            raise ValueError(f"Illegal numeric value: {v!r}")
    if not d.is_finite():
        raise ValueError(f"Non-finite Decimal value: {v!r}")
    return d

def _int_year(v, name='year'):
    if isinstance(v, bool):
        raise ValueError(f"{name} must be int or int-string, got bool")
    if isinstance(v, int):
        return v
    if isinstance(v, str):
        s = v.strip()
        if '.' in s:
            raise ValueError(f"{name} must be int or int-string, got {v!r}")
        try:
            return int(s)
        except ValueError:
            raise ValueError(f"{name} must be int or int-string, got {v!r}")
    raise ValueError(f"{name} must be int or int-string, got {type(v).__name__}")

def _int_month(v):
    m = _int_year(v, 'sale_month')
    if m < 1 or m > 12:
        raise ValueError(f"Invalid sale_month: {m}")
    return m

def _validate_annual(rows, year):
    keys = {'sales_year','sales_rows','total_quantity','total_net_paid','total_net_profit','earliest_date','latest_date'}
    seen = set()
    for r in rows:
        if not isinstance(r, dict):
            raise ValueError(f"annual_rows item not dict: {r!r}")
        miss = keys - r.keys()
        if miss:
            raise ValueError(f"annual_rows missing fields: {miss}")
        y = _int_year(r['sales_year'], 'sales_year')
        if y in seen:
            raise ValueError(f"Duplicate annual sales_year: {y}")
        seen.add(y)
    matched = [r for r in rows if _int_year(r['sales_year'], 'sales_year') == year]
    if not matched:
        raise ValueError(f"No annual row for sales_year={year}")
    return matched[0]

def _validate_monthly(rows, year):
    keys = {'sale_year','sale_month','category','row_count','total_quantity','total_net_paid','total_net_profit'}
    seen = set()
    for r in rows:
        if not isinstance(r, dict):
            raise ValueError(f"monthly_rows item not dict: {r!r}")
        miss = keys - r.keys()
        if miss:
            raise ValueError(f"monthly_rows missing fields: {miss}")
        y = _int_year(r['sale_year'], 'sale_year')
        m = _int_month(r['sale_month'])
        k = (y, m, r['category'])
        if k in seen:
            raise ValueError(f"Duplicate monthly key: {k}")
        seen.add(k)
    matched = [r for r in rows if _int_year(r['sale_year'], 'sale_year') == year]
    if not matched:
        raise ValueError(f"No monthly rows for sale_year={year}")
    return matched

def _agg_monthly(rows):
    by_month = {}
    for r in rows:
        m = _int_month(r['sale_month'])
        qty = _d(r['total_quantity'])
        paid = _d(r['total_net_paid'])
        profit = _d(r['total_net_profit'])
        if m not in by_month:
            by_month[m] = {'quantity': Decimal(0), 'net_paid': Decimal(0), 'net_profit': Decimal(0)}
        by_month[m]['quantity'] += qty
        by_month[m]['net_paid'] += paid
        by_month[m]['net_profit'] += profit
    return by_month

def main():
    raw = os.environ.get('CHATCHAT_INPUT_JSON', '{}')
    inp = json.loads(raw, parse_float=Decimal)
    year = _int_year(inp.get('sales_year', 1999), 'sales_year')
    annual_rows = inp.get('annual_rows')
    monthly_rows = inp.get('monthly_rows')
    if annual_rows is None or monthly_rows is None:
        raise ValueError("Missing annual_rows or monthly_rows input")
    a = _validate_annual(annual_rows, year)
    m_rows = _validate_monthly(monthly_rows, year)
    by_month = _agg_monthly(m_rows)
    a_paid = _d(a['total_net_paid'])
    a_profit = _d(a['total_net_profit'])
    a_qty = _d(a['total_quantity'])
    m_paid = sum((v['net_paid'] for v in by_month.values()), Decimal(0))
    m_profit = sum((v['net_profit'] for v in by_month.values()), Decimal(0))
    m_qty = sum((v['quantity'] for v in by_month.values()), Decimal(0))
    covered = sorted(by_month.keys())
    missing = [m for m in range(1, 13) if m not in by_month]
    monthly_detail = [{'month': m, 'total_quantity': str(by_month[m]['quantity']),
        'total_net_paid': str(by_month[m]['net_paid']),
        'total_net_profit': str(by_month[m]['net_profit'])} for m in covered]
    x_vals = list(range(1, 13))
    y_paid = [float(by_month[m]['net_paid']) if m in by_month else None for m in x_vals]
    y_profit = [float(by_month[m]['net_profit']) if m in by_month else None for m in x_vals]
    chart = {'data': [
        {'type': 'scatter', 'mode': 'lines+markers', 'name': 'net_paid',
         'x': x_vals, 'y': y_paid, 'connectgaps': False},
        {'type': 'scatter', 'mode': 'lines+markers', 'name': 'net_profit',
         'x': x_vals, 'y': y_profit, 'connectgaps': False}],
        'layout': {'title': f'TPC-DS Monthly Reconciliation (year={year}, unit=source-undeclared)',
            'xaxis': {'title': 'Month', 'dtick': 1},
            'yaxis': {'title': 'Amount (unit undeclared by source)', 'zeroline': True}}}
    limitations = ("Annual and monthly templates may use different filtering criteria; "
                   "differences do not necessarily indicate database errors.")
    result = {'schemaVersion': 'tpcds_reconciliation.v1',
        'metrics': {'annual_net_paid': str(a_paid), 'monthly_net_paid': str(m_paid),
            'diff_net_paid': str(a_paid - m_paid),
            'annual_net_profit': str(a_profit), 'monthly_net_profit': str(m_profit),
            'diff_net_profit': str(a_profit - m_profit),
            'annual_quantity': str(a_qty), 'monthly_quantity': str(m_qty),
            'months_covered': len(covered), 'covered_months': covered},
        'monthly': monthly_detail, 'missing_months': missing, 'chart': chart,
        'limitations': limitations}
    json.dump(result, sys.stdout, ensure_ascii=False)

if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
