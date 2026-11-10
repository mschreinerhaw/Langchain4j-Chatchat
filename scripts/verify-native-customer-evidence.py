"""Independent account acceptance calculations from frozen Native Parquet bytes.

Run in the published Python image with a read-only workspace and no network.
Output contains private customer metrics: retain it only in the test environment.
No generated Cline source or result is executed or used to calculate expectations.
"""
import argparse
from collections import Counter, defaultdict
from decimal import Decimal
import hashlib
import json
from pathlib import Path


def audit(workspace, customer):
    import pyarrow.parquet as pq
    inventory = json.loads((workspace / 'runtime-inputs.json').read_text())
    files = {f['fileId']: f for f in inventory['files']}

    def read(file_id):
        descriptor = files[file_id]
        path = (workspace / descriptor['nativePath']).resolve()
        if not path.is_relative_to(workspace.resolve()):
            raise ValueError('Input path escaped the frozen workspace')
        body = path.read_bytes()
        assert hashlib.sha256(body).hexdigest() == descriptor['sha256'], 'Input hash mismatch'
        assert len(body) == descriptor['size'], 'Input size mismatch'
        return path, body

    context = inventory['context']
    manifest_id = context['fileId']
    _, raw = read(manifest_id)
    manifest = json.loads(raw)
    assert hashlib.sha256(raw).hexdigest() == context['sha256']
    _, schema_raw = read(manifest['schemaFileId'])
    schema = json.loads(schema_raw)
    assert schema['columns'] == manifest['columns']
    mapping = {c['storageName']: c for c in manifest['columns']}
    groups = defaultdict(list)
    total = 0
    for part in manifest['parts']:
        path, raw = read(part['fileId'])
        assert hashlib.sha256(raw).hexdigest() == part['sha256']
        table = pq.read_table(path)
        assert table.num_rows == part['rowCount']
        for physical in table.to_pylist():
            logical = {}
            for storage, column in mapping.items():
                position = list(mapping).index(storage)
                assert physical.get('p' + str(position)) is True, 'Missing logical input value'
                value = physical[storage]
                if column['type'] == 'JSON':
                    value = json.loads(value, parse_float=Decimal)
                logical[column['name']] = value
            record = logical['record']
            assert record['KHH'] == customer, 'Customer identity mismatch (including leading zeros)'
            groups[logical['datasetReference']].append(record)
            total += 1
    assert total == manifest['rowCount'] == context['rowCount']
    versions = {v['datasetReference']: v for v in context['inputVersions']}
    assert set(groups) <= set(versions), 'Undeclared dataset in the frozen snapshot'
    for reference, version in versions.items():
        if reference not in groups:
            assert version['rowCount'] == 0, 'Declared nonempty dataset is missing'
            # Empty and unavailable sources remain distinct in the proof below.
            groups[reference] = []
    for reference, records in groups.items():
        assert len(records) == versions[reference]['rowCount'], 'Dataset row count mismatch'
        assert all(set(r) == set(versions[reference]['fieldSchema']) for r in records)

    # These are the declared source fields of this account acceptance scenario.
    # Roles bind to observed schemas, never a hardcoded template/customer selection.
    required = {'assets': {'ZZC', 'ZQSZ', 'ZJYE', 'DRYK', 'RQ'},
                'positions': {'ZXSZ', 'CCCB', 'LJYK', 'ZQSL', 'ZQDM'},
                'orders': {'WTRQ', 'WTH', 'WTSL', 'CJSL', 'CJJG', 'JYLBMC'}}
    roles = {}
    for role, fields in required.items():
        matches = [ref for ref, version in versions.items() if fields <= set(version['fieldSchema'])]
        assert len(matches) == 1, 'Account role is absent or ambiguous: ' + role
        reference = matches[0]
        assert fields <= set(versions[reference]['fieldDefinitions']), 'Missing declared field meanings'
        roles[role] = reference
    assets, positions, orders = [groups[roles[k]] for k in ['assets', 'positions', 'orders']]
    assert len(assets) == 1, 'Explicit single-date asset scope required'
    day = assets[0]['RQ']
    assert all(str(row['WTRQ']) == day for row in orders), 'Order date mismatch'
    bindings = json.loads(''.join(inventory['runtimeContext']['analysisInstructions'][1:]))['templateParameterBinding']['datasets']
    binding_by_ref = {b['datasetReference']: b['binding'] for b in bindings}
    for ref in roles.values():
        binding = binding_by_ref[ref]
        params = binding['effectiveParameters']
        assert params['khh'] == customer
        assert params['ksrq'] == params['jsrq'] == day

    def number(row, field):
        value = row[field]
        if value is None or isinstance(value, bool):
            raise ValueError('Missing/non-numeric business value')
        result = Decimal(str(value))
        assert result.is_finite()
        return result

    def sum_field(rows, field):
        return sum((number(row, field) for row in rows), Decimal(0))

    asset = {k: str(number(assets[0], k)) for k in ['ZZC', 'ZQSZ', 'ZJYE', 'DRYK', 'ZZC_SR', 'CRJE', 'QCJE', 'ZFZ']}
    position = {k: str(sum_field(positions, k)) for k in ['ZXSZ', 'CCCB', 'DRYK', 'LJYK', 'ZQSL']}
    side_counts = Counter(row['JYLBMC'] for row in orders)
    side_amounts = defaultdict(Decimal)
    for row in orders:
        side_amounts[row['JYLBMC']] += number(row, 'CJSL') * number(row, 'CJJG')
    # Arithmetic reconciliation is reported as a difference, not automatically
    # interpreted as an accounting error or as an approved return formula.
    return {'inputIntegrity': 'PASSED', 'customerMatched': True, 'businessDate': day,
        'dateSources': {ref: binding_by_ref[ref]['parameterSources'] for ref in roles.values()},
        'roles': roles, 'rows': {ref: len(records) for ref, records in groups.items()},
        'sourceAvailability': {ref: {k: version.get(k) for k in
            ['deliveryStatus', 'sourceVerifiedEmpty', 'rowCount']} for ref, version in versions.items()},
        'files': {fid: f['sha256'] for fid, f in files.items()},
        'assets': asset, 'positions': position,
        'orders': {'count': len(orders), 'sideCounts': dict(side_counts),
            'executedRows': sum(number(row, 'CJSL') > 0 for row in orders),
            'executedQuantity': str(sum_field(orders, 'CJSL')),
            'quantityTimesPriceBySide': {side: str(value) for side, value in side_amounts.items()},
            'uniqueOrderKeys': len({(str(r['WTRQ']), str(r['WTH']), r['GDH']) for r in orders}),
            'uniqueSecurities': len({row['ZQDM'] for row in orders})},
        'reconciliationDifferences': {
            'assetMinusCashAndSecurities': str(Decimal(asset['ZZC']) - Decimal(asset['ZJYE']) - Decimal(asset['ZQSZ'])),
            'securitiesMinusPositionMarketValues': str(Decimal(asset['ZQSZ']) - Decimal(position['ZXSZ'])),
            'assetDayPnlMinusPositionDayPnl': str(Decimal(asset['DRYK']) - Decimal(position['DRYK']))},
        'semanticAcceptance': 'INCONCLUSIVE_UNTIL_ARCHIVED_REPORT_COMPARISON',
        'limitations': ['Template default date is historical, not the current date.',
            'Quantity times executed price excludes fees and is not settled cash flow.',
            'Position cumulative realized profit is not floating profit.',
            'Single-day data does not establish long-term trading preferences.',
            'Reconciliation differences need matching metric scope and units before attribution.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--customer', required=True)
    args = parser.parse_args()
    print(json.dumps(audit(args.workspace, args.customer), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
