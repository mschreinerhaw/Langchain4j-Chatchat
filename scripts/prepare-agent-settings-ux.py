"""Prepare the Agent settings presentation overlay without rebuilding unrelated deployed UI."""
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
    old_name = 'AgentWorkshopView-DhAkShBf.js'
    original = fetch(old_name)
    script = original
    changes = {
        '数据资源授权': ('可查阅的资料', 2),
        '仅使用当前用户与 Agent 共同获准的资源；勾选不会增加用户权限。': ('允许 Agent 查阅以下资料，查找依据、补充信息并引用来源。', 1),
        'T(" "+d(o.label),1)': ('T(" "+d(o.id==="knowledge_documents"?"从知识文档中查找答案":o.label),1)', 1),
        '资源使用策略': ('何时查阅资料', 1),
        '根据任务自主选择': ('按问题需要自动查阅', 1),
        '仅在用户明确启用时使用': ('仅在用户明确要求时查阅', 1),
        '企业文档范围仍由下方文档绑定限定；历史行情按实际采集日期使用。': ('使用知识文档时，先开启上方的文档检索，再在下方选择具体文档。Agent 只能查阅使用者有权访问的内容。', 1),
        'l("strong",null,"知识文档")': ('l("strong",null,"可查阅的文档")', 1),
        '选择当前账号有权读取且已完成解析的文档。': ('选择此 Agent 要查阅的制度、业务规则、需求说明或数据字典。', 1),
        'l("summary",null,"工具调用设置（高级）",-1)': ('l("summary",null,[l("span",null,"工具调用规则"),l("small",null,"高级")],-1)', 1),
    }
    for old, (new, count) in changes.items():
        assert script.count(old) == count, 'Unexpected deployed component: ' + old
        script = script.replace(old, new)
    new_name = 'AgentWorkshopView-settings-' + sha(script.encode())[:12] + '.js'
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
    start = source.index('.agent-workshop-view .agent-resource-selector-copy > small {')
    end = source.index('.agent-workshop-view .workflow-enable,', start)
    additions = source[start:end]
    (output / 'preview.css').write_text(additions, encoding='utf-8')
    assets = {new_name: script, index_name: index, css_name: css + '\n' + additions}
    assets = {'BOOT-INF/classes/static/assets/' + name: body.encode('utf-8') for name, body in assets.items()}
    with zipfile.ZipFile(output / 'web-assets.zip', 'w') as archive:
        for name, body in assets.items():
            archive.writestr(name, body)
    for name in ['classes.zip', 'api-classes.zip']:
        with zipfile.ZipFile(output / name, 'w'):
            pass
    source_paths = [source_css, root / 'chatchat-api/web-app/src/views/AgentWorkshopView.vue', Path(__file__).resolve()]
    manifest = {
        'imageTag': 'runtime-os-api:agent-settings-ux-20261104-r1',
        'baselineSha256': 'e413d7d093c3fa96918f58b0a96372e309fa1e3b0772bd3d7c4649b603e6cbe3',
        'classes': {}, 'apiClasses': {},
        'webAssets': {name: sha(body) for name, body in assets.items()},
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        'sourceDiffSha256': sha(b''.join(path.read_bytes() for path in source_paths)),
        'sourceFiles': {path.relative_to(root).as_posix(): sha(path.read_bytes()) for path in source_paths},
        'baselineWorkshopBundleSha256': sha(original.encode('utf-8')),
    }
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
