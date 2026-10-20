"""Build a candidate from the running stage with two verified library replacements.

Retired document-search settings are removed from packaged defaults. All other
entries retain their bytes. No live container or deployment file is mutated here.
"""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile
import re
import sys

ROOT = Path('/opt/runtime-os-docker-stage/evidence/runtime-os-refactor-20261020')
MODULES = ('chatchat-agents', 'chatchat-knowledge-base')
EXTENDED_MODULES = (*MODULES, 'chatchat-common', 'chatchat-chat')
CONFIGS = {'application-dev.yml', 'application-prod.yml', 'application-dev.template',
           'application-prod.template', 'application-opensearch-mysql.template'}

def remove_document_rag_config(body):
    """Remove retired keys only within chatchat.search, preserving all other bytes."""
    retired = {'vector-rerank-max-bytes', 'vector-field', 'vector-candidate-limit',
               'bm25-weight', 'vector-weight', 'title-weight', 'freshness-weight', 'authority-weight'}
    output, removed = [], []
    chatchat = search = False
    skipping = None
    for line in body.decode('utf-8').splitlines(keepends=True):
        text = line.strip()
        indent = len(line) - len(line.lstrip(' '))
        content = bool(text) and not text.startswith('#')
        if content and indent == 0:
            chatchat = text == 'chatchat:'
        if content and indent <= 2:
            search = chatchat and indent == 2 and text == 'search:'
        if skipping is not None:
            if not content or indent > skipping:
                continue
            skipping = None
        key = text.split(':', 1)[0]
        if search and key == 'bge-reranker' and indent == 6:
            removed.append('bge-reranker')
            skipping = indent
            continue
        if search and key in retired and indent in (6, 8):
            removed.append(key)
            continue
        output.append(line)
    return ''.join(output).encode('utf-8'), removed

def digest(body):
    return hashlib.sha256(body).hexdigest()

def main():
    global ROOT
    if len(sys.argv) > 1:
        evidence_root = ROOT.parent.resolve()
        ROOT = evidence_root / sys.argv[1]
        if not ROOT.resolve().is_relative_to(evidence_root):
            raise ValueError('Assembly evidence must stay within stage evidence')
    manifest = json.loads((ROOT/'module-manifest.json').read_text())
    if set(manifest['modules']) not in (set(MODULES), set(EXTENDED_MODULES)):
        raise ValueError('Unexpected replacement modules')
    replacements = {}
    if 'apiClassOverlaySha256' in manifest:
        overlay=(ROOT/'api-classes.zip').read_bytes()
        if digest(overlay)!=manifest['apiClassOverlaySha256']:
            raise ValueError('API class overlay checksum mismatch')
        with zipfile.ZipFile(io.BytesIO(overlay)) as classes:
            if not classes.namelist():
                raise ValueError('Empty API class overlay')
            for name in classes.namelist():
                if not re.fullmatch(r'com/chatchat/api/(?:runtime/coding/(?:cline/(?:ClineCodingAdapter|ClineModelCallbackController|ClineModelReceiptStore)|analysis/(?:ClineContextAnswerService|CodingAnalysisService|CodingExecutionProfile)|workspace/CodingWorkspaceService)|datascience/(?:StudioCodePlanService|StudioTaskHistory|sql/SqlStudioService|python/PythonCodeAssistantService))(?:\$[^/]+)?\.class',name):
                    raise ValueError('Unexpected API class override')
                replacements['BOOT-INF/classes/' + name]=classes.read(name)
    if 'webAssetsSha256' in manifest:
        overlay=(ROOT/'web-assets.zip').read_bytes()
        if digest(overlay)!=manifest['webAssetsSha256']:raise ValueError('Web assets checksum mismatch')
        web_files=json.loads((ROOT/'web-files.json').read_text())
        with zipfile.ZipFile(io.BytesIO(overlay)) as web:
            if set(web.namelist())!=set(web_files):raise ValueError('Web asset manifest mismatch')
            for name in web.namelist():
                if not name.startswith('BOOT-INF/classes/static/') or '..' in name.split('/') or digest(web.read(name))!=web_files[name]:
                    raise ValueError('Invalid web asset')
                replacements[name]=web.read(name)
    for module in manifest['modules']:
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
    config_updates = {}
    replaced = set()
    added = []
    with zipfile.ZipFile(before) as source, zipfile.ZipFile(ROOT/'app.jar', 'w', allowZip64=True) as target:
        for entry in source.infolist():
            body = source.read(entry.filename)
            if entry.filename in replacements:
                body = replacements[entry.filename]
                replaced.add(entry.filename)
            elif entry.filename.startswith('BOOT-INF/classes/') and entry.filename.rsplit('/', 1)[-1] in CONFIGS:
                updated, removed = remove_document_rag_config(body)
                if removed:
                    body = updated
                    config_updates[entry.filename] = removed
                else:
                    retained[entry.filename] = digest(body)
            else:
                retained[entry.filename] = digest(body)
            target.writestr(entry, body)
        for name in set(replacements) - replaced:
            if not name.startswith('BOOT-INF/classes/') or not (name.endswith('.class') or name.startswith('BOOT-INF/classes/static/')):
                raise ValueError('Running stage has an unexpected library layout')
            target.writestr(name,replacements[name])
            replaced.add(name)
            added.append(name)
    if replaced != set(replacements):
        raise ValueError('Running stage has an unexpected library layout')
    with zipfile.ZipFile(ROOT/'app.jar') as candidate:
        if any(digest(candidate.read(name)) != value for name, value in retained.items()):
            raise ValueError('An unrelated archive entry changed')
    report = {'status': 'ASSEMBLED', 'sourceCommit': manifest['sourceCommit'],
              'assemblyScriptSha256': digest(Path(__file__).read_bytes()),
              'baseSha256': digest(before.read_bytes()), 'candidateSha256': digest((ROOT/'app.jar').read_bytes()),
              'modules': manifest['modules'], 'unchangedEntries': len(retained),
              'apiClassOverlaySha256': manifest.get('apiClassOverlaySha256'),
              'webAssetsSha256':manifest.get('webAssetsSha256'),
              'addedApiClasses': sorted(added),
              'removedDocumentRagConfig': config_updates,
              'retiredRagClasses': 0, 'productionSwitched': False,
              'configuration': 'Only retired chatchat.search RAG keys removed; model/API/Skills/MCP and all other configuration text retained.'}
    (ROOT/'module-package.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)

if __name__ == '__main__':
    main()
