"""Build a candidate from the running stage with two verified library replacements.

All other entries, including UI, Model APIs, Skills, MCP and Sandbox integration,
retain their original bytes. No live container or deployment file is mutated here.
"""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path('/opt/runtime-os-docker-stage/evidence/runtime-os-refactor-20261020')
MODULES = ('chatchat-agents', 'chatchat-knowledge-base')

def digest(body):
    return hashlib.sha256(body).hexdigest()

def main():
    manifest = json.loads((ROOT/'module-manifest.json').read_text())
    if set(manifest['modules']) != set(MODULES):
        raise ValueError('Unexpected replacement modules')
    replacements = {}
    for module in MODULES:
        file = module + '-1.0.0-SNAPSHOT.jar'
        body = (ROOT/file).read_bytes()
        if digest(body) != manifest['modules'][module]:
            raise ValueError('Module checksum mismatch: ' + module)
        replacements['BOOT-INF/lib/' + file] = body
        with zipfile.ZipFile(io.BytesIO(body)) as library:
            forbidden = ('LangChain4jKnowledgeFusion', 'BgeDocumentCandidateReranker',
                         'DocumentCandidateReranker', 'CandidateRerankStage', '$BgeReranker', '$HybridHit')
            if any(any(name in entry for name in forbidden) for entry in library.namelist()):
                raise ValueError('Retired class remains in ' + module)
    before = ROOT/'assembly-base.jar'
    subprocess.run(['docker', 'cp', 'runtime-os-app-stage-api-1:/srv/app/app.jar', str(before)], check=True)
    retained = {}
    replaced = set()
    with zipfile.ZipFile(before) as source, zipfile.ZipFile(ROOT/'app.jar', 'w', allowZip64=True) as target:
        for entry in source.infolist():
            body = source.read(entry.filename)
            if entry.filename in replacements:
                body = replacements[entry.filename]
                replaced.add(entry.filename)
            else:
                retained[entry.filename] = digest(body)
            target.writestr(entry, body)
    if replaced != set(replacements):
        raise ValueError('Running stage has an unexpected library layout')
    with zipfile.ZipFile(ROOT/'app.jar') as candidate:
        if any(digest(candidate.read(name)) != value for name, value in retained.items()):
            raise ValueError('An unrelated archive entry changed')
    report = {'status': 'ASSEMBLED', 'sourceCommit': manifest['sourceCommit'],
              'baseSha256': digest(before.read_bytes()), 'candidateSha256': digest((ROOT/'app.jar').read_bytes()),
              'modules': manifest['modules'], 'unchangedEntries': len(retained),
              'retiredRagClasses': 0, 'productionSwitched': False,
              'configuration': 'Existing stage settings retained. Removed RAG-only settings are ignored; shared embedding remains for Skills/MCP.'}
    (ROOT/'module-package.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)

if __name__ == '__main__':
    main()
