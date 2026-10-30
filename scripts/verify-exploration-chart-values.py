"""Independently compare an actual workspace chart/table with its archived account input.

No model calls, synthesized rows, source mutation or execution-status inference.
Inputs remain private; output records hashes, field identities and comparison counts.
"""
import argparse
from decimal import Decimal
import hashlib
import json
import re
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--source', required=True, type=Path)
p.add_argument('--result', required=True, type=Path)
p.add_argument('--figure', required=True, type=Path)
p.add_argument('--baseline', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--node-index', type=int, default=0)
a = p.parse_args()
read = lambda path: json.loads(path.read_text(encoding='utf8'), parse_float=Decimal)
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
source, result, figure, baseline = map(read, [a.source, a.result, a.figure, a.baseline])
rows = sorted(source['rows'], key=lambda row: str(row['busi_date']))
assert rows and len({str(row['cust_num']) for row in rows}) == 1
assert baseline['sourceFileSha256'] == digest(a.source)
customer = str(rows[0]['cust_num'])
definitions = source['fieldDefinitions']
fields = {value['description']: key for key, value in definitions.items()}
comparisons, trace_fields = 0, []
dates = [str(row['busi_date']) for row in rows]
for trace in figure['data']:
    field = fields[trace['name']]  # exact producer-declared meaning, never fuzzy column inference
    assert [str(value).replace('-', '') for value in trace['x']] == dates
    assert len(trace['y']) == len(rows)
    for value, row in zip(trace['y'], rows):
        assert Decimal(str(value)) == Decimal(str(row[field])), field
        comparisons += 1
    trace_fields.append(field)
assert comparisons and len(trace_fields) >= 1
receipt = result['nodes'][a.node_index]['receipt']
assert receipt['executionMode'] == 'PYTHON' and receipt['resultAvailable'] is True
assert len(receipt['evidenceRefs']) >= 6
report = receipt['visualReport']
latest = rows[-1]
latest_tables = [table for table in report['tables'] if table['id'] == 'asset_structure_latest']
if latest_tables:
    table = latest_tables[0]
    assert customer in table['scope'] and str(latest['busi_date']) in table['scope']
    for row in table['rows']:
        if row[0] not in fields:
            assert row[0] == '合计'
            expected = latest['tot_ast']
        else:
            expected = latest[fields[row[0]]]
        assert Decimal(str(row[1])) == Decimal(str(expected))
        comparisons += 1
        actual_percent = Decimal(str(row[2]).rstrip('%'))
        expected_percent = Decimal(str(expected)) / Decimal(str(latest['tot_ast'])) * 100
        assert abs(actual_percent - expected_percent) <= Decimal('0.00005')
        comparisons += 1
else:
    table = next(table for table in report['tables'] if len(table['columns']) > 1
                 and all(column.split('（')[0] in fields for column in table['columns'][1:]))
    assert customer in table.get('scope', '') or customer in report['summary']
    assert len(table['rows']) == len(rows)
    table_fields = [fields[column.split('（')[0]] for column in table['columns'][1:]]
    for actual, original in zip(table['rows'], rows):
        assert str(actual[0]).replace('-', '') == str(original['busi_date'])
        for field, value in zip(table_fields, actual[1:]):
            assert Decimal(str(value)) == Decimal(str(original[field]))
            comparisons += 1
unit_declared = any(value.get('unit') or value.get('currency') for value in definitions.values())
labels = [column for table in report['tables'] for column in table['columns']]
labels += [metric.get('unit', '') for metric in report.get('metrics', [])]
labels += [str(axis.get('title', '')) for key, axis in figure.get('layout', {}).items() if key.startswith(('xaxis', 'yaxis')) and isinstance(axis, dict)]
unit_issue = not unit_declared and (any('元' in label for label in labels)
    or bool(re.search(r'[\d,.]+\s*(?:万元|元)', report['summary'])))
evidence = {
    'status': 'NUMERIC_VALUES_PASSED', 'taskId': receipt['taskId'],
    'actualCustomer': customer, 'sourceRows': len(rows), 'sourceKind': source['source']['kind'],
    'sourceArchiveSha256': source['source']['snapshotSha256'], 'selectedDatasetSha256': digest(a.source),
    'figureSha256': digest(a.figure), 'independentBaselineSha256': digest(a.baseline),
    'baselineComputedAt': baseline['computedBeforeWorkspaceResultAt'],
    'comparedTraceFields': trace_fields, 'exactValueAndPercentComparisons': comparisons,
    'sourceUnchanged': True, 'reportStatus': report['status'],
    'businessSemanticStatus': 'PARTIAL_UNDECLARED_CURRENCY' if unit_issue else 'SOURCE_UNIT_UNDECLARED_CORRECTLY_LABELLED' if not unit_declared else 'SOURCE_MEANINGS_CHECKED_FOR_COMPARED_FIELDS',
    'limitations': ['Archived snapshot, not a current customer query.',
        'Only listed chart series and the corresponding structure table were independently checked.']
        + (['Report uses a currency unit absent from producer metadata; numeric agreement does not validate that unit.'] if unit_issue else []),
}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf8')
print(json.dumps(evidence, ensure_ascii=True))
