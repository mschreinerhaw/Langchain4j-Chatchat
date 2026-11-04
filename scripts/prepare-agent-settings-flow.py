"""Apply the resource opt-in -> document scope -> strategy flow to the deployed UI."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request
import zipfile


def sha(body):
    return hashlib.sha256(body).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def fetch(name):
        with client.open(args.origin.rstrip('/') + '/assets/' + name, timeout=30) as response:
            return response.read().decode('utf-8')
    old_name = 'AgentWorkshopView-settings-6d8e1c622baa.js'
    original = fetch(old_name)
    assert sha(original.encode()) == '6d8e1c622baa76d0699dfdc77a3f1f37988855659516463e29138f3684ef290a'
    start = original.index('l("section",qt,')
    documents_start = original.index('l("section",Xt,', start)
    end = original.index('l("section",Zt,', documents_start)
    resource = original[start:documents_start - 1]
    assert resource.endswith('])])')
    strategy_start = resource.index('l("label",null,[t[142]')
    help_start = resource.index(',t[145]', strategy_start)
    strategy = resource[strategy_start:help_start].replace('仅在用户明确要求时查阅', '在对话中勾选使用资料后查阅')
    documents = original[documents_start:end - 1]
    assert documents.endswith('])')
    documents = documents.replace('l("section",Xt,',
        'r("section",{key:"knowledge-scope",class:"agent-resource-selector agent-resource-documents"},', 1)
    nested = ('e.form.resourceIds.length?(n(),r("div",{key:"resource-settings",class:"agent-resource-settings"},['
        'e.form.resourceIds.includes("knowledge_documents")?(n(),' + documents + '):p("",!0),'
        + strategy + ',l("small",null,"Agent 只能查阅使用者有权访问的内容。")])):p("",!0)')
    replacement = resource[:strategy_start] + nested + '])]),'
    script = original[:start] + replacement + original[end:]
    new_name = 'AgentWorkshopView-flow-' + sha(script.encode())[:12] + '.js'
    (output / 'preview.mjs').write_text(script, encoding='utf-8')
    subprocess.run(['node', '--check', str(output / 'preview.mjs')], check=True)
    index_name = 'index-KwmQfLwa.js'
    index = fetch(index_name)
    assert index.count(old_name) == 2
    index = index.replace(old_name, new_name)
    css_name = 'skill-hub-Xz_7eIPd.css'
    css = fetch(css_name)
    source_css = root / 'chatchat-api/web-app/src/styles/pages/skill-hub.css'
    source = source_css.read_text(encoding='utf-8')
    css_start = source.index('.agent-workshop-view .agent-resource-settings {')
    css_end = source.index('.agent-workshop-view .agent-resource-selector-copy > label:has(', css_start)
    additions = source[css_start:css_end]
    (output / 'preview.css').write_text(additions, encoding='utf-8')
    assets = {new_name: script, index_name: index, css_name: css + '\n' + additions}
    assets = {'BOOT-INF/classes/static/assets/' + name: body.encode() for name, body in assets.items()}
    with zipfile.ZipFile(output / 'web-assets.zip', 'w') as archive:
        for name, body in assets.items():
            archive.writestr(name, body)
    for name in ['classes.zip', 'api-classes.zip']:
        with zipfile.ZipFile(output / name, 'w'):
            pass
    sources = [source_css, root / 'chatchat-api/web-app/src/views/AgentWorkshopView.vue', Path(__file__).resolve()]
    manifest = {
        'imageTag': 'runtime-os-api:agent-settings-flow-20261104-r1',
        'baselineSha256': 'b7373b62c792b6fdc96a42f839d70f68ba638b0e974903e8f9dcc5d931146eaf',
        'classes': {}, 'apiClasses': {}, 'webAssets': {name: sha(body) for name, body in assets.items()},
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        'sourceDiffSha256': sha(b''.join(path.read_bytes() for path in sources)),
        'sourceFiles': {path.relative_to(root).as_posix(): sha(path.read_bytes()) for path in sources},
    }
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
