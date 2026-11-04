"""Build a narrow, hash-pinned overlay against the deployed knowledge release.

The deployed KnowledgeResponseAdvice is not yet present in origin/main. Compile its
reviewed replacement against the running JAR instead of replacing unrelated code.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def sha(body):
    return hashlib.sha256(body).hexdigest()


def patch_tasks(source):
    replacements = {
        'async reloadEvents(e={}){if(!this.selectedTask?.taskId){this.selectedEvents=[];return}':
            'async reloadEvents(e={}){if(!this.selectedTask?.taskId||this.selectedTask.contentRestricted){this.selectedEvents=[],this.selectedEventId="";return}',
        'async loadPlanDag(e={}){if(!this.selectedTask?.taskId){':
            'async loadPlanDag(e={}){if(!this.selectedTask?.taskId||this.selectedTask.contentRestricted){',
        'async loadExplorationTimeline(){':
            'async loadExplorationTimeline(){if(this.selectedTask?.contentRestricted){this.explorationTimeline={observations:[],steps:[]},this.explorationRunId="",this.explorationWarning="",this.explorationHasMore=!1;return}',
        'canRecordFeedback(){': 'canRecordFeedback(){if(this.selectedTask?.contentRestricted)return!1;',
        'this.planLoadedTaskId!==h.taskId&&await this.loadPlanDag({silent:!0})':
            'h.contentRestricted?await this.selectTask(h):this.planLoadedTaskId!==h.taskId&&await this.loadPlanDag({silent:!0})',
    }
    for old, new in replacements.items():
        assert source.count(old) == 1, 'Unexpected deployed task bundle: ' + old
        source = source.replace(old, new)
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    patch = Path(__file__).resolve().parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    baseline = args.baseline.resolve()
    # Exact running production release; a new release requires a fresh review.
    baseline_hash = sha(baseline.read_bytes())
    expected = json.loads((patch / 'baseline.json').read_text())
    assert baseline_hash == expected['jarSha256'], 'Running release changed'
    dependencies = output / 'dependencies'
    with zipfile.ZipFile(baseline) as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar') or name.startswith('BOOT-INF/classes/') and name.endswith('.class'):
                jar.extract(name, dependencies)
        task_entry = 'BOOT-INF/classes/static/assets/TasksView-_vnaiyxx.js'
        tasks = patch_tasks(jar.read(task_entry).decode('utf-8')).encode('utf-8')
        task_name = 'TasksView-evidence-' + sha(tasks)[:12] + '.js'
        index_entry = 'BOOT-INF/classes/static/assets/index-KwmQfLwa.js'
        index = jar.read(index_entry).decode('utf-8')
        assert index.count('TasksView-_vnaiyxx.js') == 2
        index = index.replace('TasksView-_vnaiyxx.js', task_name).encode('utf-8')
    knowledge = root / 'chatchat-api/src/main/java/com/chatchat/api/runtime/coding/knowledge'
    sources = [knowledge / 'TaskCollectionDisclosure.java', knowledge / 'KnowledgeResponseAdvice.java']
    classes = output / 'classes'
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(map(str, [dependencies / 'BOOT-INF/classes', dependencies / 'BOOT-INF/lib/*']))
    subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), *map(str, sources)], check=True)
    api = {}
    with zipfile.ZipFile(output / 'api-classes.zip', 'w') as jar:
        for path in sorted(classes.rglob('*.class')):
            name = 'BOOT-INF/classes/' + path.relative_to(classes).as_posix()
            jar.write(path, name)
            api[name] = sha(path.read_bytes())
    with zipfile.ZipFile(output / 'classes.zip', 'w'):
        pass
    assets = {index_entry: index, 'BOOT-INF/classes/static/assets/' + task_name: tasks}
    with zipfile.ZipFile(output / 'web-assets.zip', 'w') as jar:
        for name, body in assets.items():
            jar.writestr(name, body)
    manifest = {
        'imageTag': 'runtime-os-api:task-evidence-disclosure-20261104-r1',
        'baselineSha256': baseline_hash, 'classes': {}, 'apiClasses': api,
        'webAssets': {name: sha(body) for name, body in assets.items()},
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        'sourceDiffSha256': sha(b''.join(source.read_bytes() for source in sources)),
        'deployedBaselineSourceCommit': expected['sourceCommit'],
        'sourceFiles': {str(source.relative_to(root)): sha(source.read_bytes()) for source in sources},
    }
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
