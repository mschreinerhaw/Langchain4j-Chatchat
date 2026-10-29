"""Replay recorded real catalog receipts through current SQLGlot. No network/SQL execution."""
import argparse
import importlib.util
import json
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--receipts', type=Path, required=True, help='Private Evidence Store export; never copied to output')
    parser.add_argument('--sql', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location('validator', root / 'chatchat-api/src/main/resources/sql-toolchain/validate.py')
    validator = importlib.util.module_from_spec(spec); spec.loader.exec_module(validator)
    records = json.loads(args.receipts.read_text(encoding='utf-8'))
    task_ids = sorted({row.get('record', row).get('payload', {}).get('taskId') for row in records
                       if row.get('record', row).get('payload', {}).get('taskId')})
    assert len(task_ids) == 1, 'Receipt export must contain exactly one task'
    tables = {}
    for exported in records:
        record = exported.get('record', exported)
        if record.get('evidenceType') != 'CODING_WORKSPACE_TOOL_RECEIPT':
            continue
        response = record.get('payload', {}).get('response', {})
        for item in response.get('items', []):
            if item.get('tableName') and item.get('columns'):
                table = dict(item, bindingId=response.get('bindingId'))
                key = tuple(table.get(k) for k in ('bindingId', 'database', 'schema', 'tableName'))
                tables[key] = table
    assert tables, 'No described physical tables in recorded receipts'
    request = dict(dialect='oracle', files=[dict(path='sql/customer_070200046604.sql', content=args.sql.read_text(encoding='utf-8'))],
                   schema=dict(tables=list(tables.values())))
    started = time.perf_counter()
    result = validator.validate(request)
    elapsed = round((time.perf_counter() - started) * 1000, 3)
    result.pop('normalizedFiles', None)  # Source already exists; export no copied customer metadata.
    output = dict(verificationMode='RECORDED_REAL_METADATA_OFFLINE_REPLAY', liveDatabaseTest=False,
                  modelCalled=False, metadataAuthorizationRecheckedLive=False, sqlExecuted=False,
                  customerNumber='070200046604', sourceTaskId=task_ids[0], describedTables=len(tables), validationMs=elapsed, result=result)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    print(json.dumps(dict(valid=result['valid'], sqlExecuted=False, validationMs=elapsed,
                         describedTables=len(tables), diagnostics=result['diagnostics'])))
    if not result['valid']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
