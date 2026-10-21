"""Run on the authorized stage host: audit persisted Cline input/execution evidence, never UI logs."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--file-reference', action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'att-\d+-[a-f0-9-]{36}', args.run_id):
        raise ValueError('Invalid run ID')
    query = ("select record_json from runtime_evidence_entry where entry_kind='EVIDENCE' "
             "and record_json::jsonb->'scope'->>'runId'='" + args.run_id + "'")
    raw = subprocess.check_output(['docker', 'exec', '-u', 'postgres', 'postgres', 'psql', '-At',
                                   '-d', 'runtime_os_acceptance_api', '-c', query], text=True)
    records = [json.loads(line) for line in raw.splitlines()]
    counts = dict(Counter(row['evidenceType'] for row in records))
    snapshots = [row['payload'] for row in records if row['evidenceType'] == 'CODING_INPUT_SNAPSHOT']
    refs = [version['datasetReference'] for snapshot in snapshots for version in snapshot.get('inputVersions', [])]
    if args.file_reference:
        assert len(snapshots) == 1, 'Exactly one immutable Coding input snapshot is required'
        assert set(args.file_reference).issubset(refs), 'Cline did not receive every authorized original reference'
        for kind in ['CODING_CODE_SUBMISSION', 'CODING_EXECUTION_RECEIPT', 'CODING_STRUCTURED_DATASET', 'CODING_COMPUTATION_VALIDATION']:
            assert counts.get(kind, 0) > 0, 'Missing real Cline/Python evidence: ' + kind
    proof = {'runId': args.run_id, 'counts': counts, 'inputReferences': refs,
             'expectedFileReferences': args.file_reference, 'checkedFromDatabase': True,
             'snapshots': [{k: snapshot[k] for k in ['taskId', 'sourceDatasetRef', 'sha256', 'delivery', 'inputVersions'] if k in snapshot}
                           for snapshot in snapshots],
             'executions': [row['payload'] for row in records if row['evidenceType'] == 'CODING_EXECUTION_RECEIPT'],
             'code': [row['payload'] for row in records if row['evidenceType'] == 'CODING_CODE_SUBMISSION'],
             'models': [row['payload'].get('response', {}).get('modelInfo') for row in records if row['evidenceType'] == 'CODING_MODEL_RESPONSE_RECEIPT']}
    args.output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    args.output.write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'runId': args.run_id, 'counts': counts, 'fileInputsVerified': bool(args.file_reference)}))


if __name__ == '__main__':
    main()
