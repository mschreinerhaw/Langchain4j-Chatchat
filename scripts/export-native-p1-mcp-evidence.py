"""Export only enumerated public acceptance evidence; never copy auth/config/private journals."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tarfile

os.umask(0o077)
ROOT = Path('/var/tmp/runtime-native-coding-p1-20261107')
BASE = Path('/var/tmp/runtime-native-coding-acceptance-20261107')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def main():
    label = sys.argv[1]
    assert re.fullmatch('[a-zA-Z0-9-]{1,60}', label)
    output = ROOT / ('public-mcp-' + label)
    output.mkdir(exist_ok=False)

    def copy(source, name):
        if source.is_file():
            target = output / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)

    for path in ROOT.glob('mcp-deployment*-public.json'):
        copy(path, 'deployment/' + path.name)
    copy(ROOT / 'api-startup-r1-public.json', 'failures/api-startup-r1-public.json')
    raw = ROOT / 'api-startup-r1-private.log'
    if raw.exists():
        text = raw.read_text()
        start = text.find('APPLICATION FAILED TO START')
        assert start >= 0
        (output / 'failures').mkdir(exist_ok=True)
        (output / 'failures/api-startup-r1-excerpt.log').write_text(text[start:start + 4000])
    for directory in ROOT.glob('normal-r*'):
        for name in ('request.json', 'intent.json', 'submit.json', 'result-public.json', 'files.json',
                     'history.json', 'events.json', 'fixture-cleanup-public.json', 'independent-stdout.log', 'independent-stderr.log'):
            copy(directory / name, directory.name + '/' + name)
        for path in directory.glob('*public.json'):
            copy(path, directory.name + '/' + path.name)
    spec = importlib.util.spec_from_file_location('projection', BASE / 'project-evidence-projection.py')
    projection = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(projection)
    rejected = ROOT / 'normal-r2'
    if (rejected / 'submit.json').exists():
        response = json.loads((rejected / 'submit.json').read_text())
        task = json.loads((rejected / 'intent.json').read_text())['taskId']
        records = projection.rows(task)
        inputs = [row['document'] for row in records if row['kind'] == 'EVIDENCE'
            and row['document'].get('evidenceType') == 'CODING_NATIVE_PROJECT_INPUT']
        assert len(inputs) == 1
        record = inputs[0]
        normalized_hash = sha(json.dumps(record['payload'], sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode())
        has_journal = any(row['kind'] == 'EXEC_CHECKPOINT' and row['document'].get('key') == 'coding.tasks.v1' for row in records)
        model_receipts = sum(row['kind'] == 'EVIDENCE' and row['document'].get('evidenceType') == 'CODING_MODEL_RESPONSE_RECEIPT' for row in records)
        proof = {'issue': 'M-05', 'taskId': task, 'http': response['http'], 'message': response['body']['message'],
            'storedInputHash': record['contentSha256'], 'hashAfterJsonMapRead': normalized_hash,
            'persistedBindingFieldOrder': list(record['payload']['mcpBindings'][0]), 'hashMismatchReproduced': record['contentSha256'] != normalized_hash,
            'admittedJournalPresent': has_journal, 'modelReceiptCount': model_receipts, 'completed': False}
        assert proof['hashMismatchReproduced'] and not has_journal and model_receipts == 0
        (output / 'failures/input-hash-r2-public.json').write_text(json.dumps(proof, indent=2) + '\n')
    entries = []
    for path in output.rglob('*'):
        if path.is_file():
            entries.append({'path': path.relative_to(output).as_posix(), 'sha256': sha(path.read_bytes()), 'bytes': path.stat().st_size})
    (output / 'manifest.json').write_text(json.dumps({'scope': 'PUBLIC_RUNTIME_EVIDENCE_NOT_FINAL_P1_ACCEPTANCE', 'files': entries}, indent=2) + '\n')
    archive = ROOT / ('mcp-evidence-' + label + '.tgz')
    assert not archive.exists()
    with tarfile.open(archive, 'w:gz') as bundle:
        bundle.add(output, arcname='mcp')
    print(json.dumps({'archive': str(archive), 'sha256': sha(archive.read_bytes()), 'files': len(entries)}))


if __name__ == '__main__':
    main()
