"""Collect release evidence without credentials, source rows, generated code or prompts."""
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request

ROOT = Path('/opt/runtime-os-docker-stage/evidence/runtime-os-refactor-20261020')
SAFE_FILES = ('module-manifest.json', 'runner-manifest.json', 'module-package.json', 'deployment.json',
              'module-package-initial.json', 'deployment-initial.json',
              'deployment-runner-analysis-fast.json', 'service-and-boundary.json',
              'baseline-cancel.json', 'candidate-cancel.json')

def main():
    collected = []
    for name in SAFE_FILES:
        if (ROOT/name).is_file():
            collected.append(name)
    for group in ('baseline', 'candidate'):
        folder = ROOT/group
        path = folder/'run-1.json'
        if not path.is_file():
            continue
        value = json.loads(path.read_text())
        parent = value.get('parent', {})
        projected = {'taskId': value.get('taskId'), 'elapsedSeconds': value.get('elapsedSeconds'),
                     'status': parent.get('canonicalState') or parent.get('status'),
                     'eventCount': len(value.get('events', [])), 'summaryAvailable': (folder/'summary.json').is_file()}
        for name in ('summary.json', 'trace-1.json'):
            if (folder/name).is_file():
                collected.append(group + '/' + name)
        (folder/'observation.json').write_text(json.dumps(projected, indent=2))
        collected.append(group + '/observation.json')
    if (ROOT/'performance-attempts.json').is_file():
        collected.append('performance-attempts.json')
    for case in ('qa', 'simple-data', 'simple-data-fixed'):
        path = ROOT/(case + '-live.json')
        if not path.is_file():
            continue
        value = json.loads(path.read_text())
        keys = ('mode', 'status', 'taskId', 'runId', 'parent', 'hasActualModelTelemetry',
                'hasArchivedCode', 'hasCompletedCoding', 'resultAdmitted', 'productionSwitched',
                'expectedInputFormat', 'inputBindings', 'javaSemanticReviewInvoked', 'resultAdmissionScope')
        projected = {key: value.get(key) for key in keys}
        projected['codingTasks'] = [{'taskId': task.get('taskId'),
                                    'status': task.get('outcome', {}).get('status'),
                                    'usage': task.get('outcome', {}).get('usage'),
                                    'validationStatus': task.get('outcome', {}).get('validationStatus'),
                                    'executionControl': task.get('outcome', {}).get('executionControl'),
                                    'resultDatasetRef': task.get('outcome', {}).get('resultDatasetRef'),
                                    'evidenceBundleRef': task.get('outcome', {}).get('evidenceBundleRef')}
                                   for task in value.get('tasks', [])]
        name = case + '-summary.json'
        (ROOT/name).write_text(json.dumps(projected, indent=2))
        collected.append(name)
    snapshot = subprocess.check_output(['docker', 'ps', '--format', '{{.Names}} {{.Image}} {{.Status}}'], text=True)
    (ROOT/'deployment-after.txt').write_text(snapshot)
    collected.append('deployment-after.txt')
    token = (ROOT.parents[1]/'config/coding-node-monitor.token').read_text().strip()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node', headers={'Authorization': 'Bearer ' + token})
    with client.open(request, timeout=10) as response:
        snapshot = json.load(response)
    (ROOT/'post-validation-sessions.json').write_text(json.dumps({'active': snapshot['sessions']['active']}))
    collected.append('post-validation-sessions.json')
    with tarfile.open(ROOT/'release-evidence.tar.gz', 'w:gz') as archive:
        for name in collected:
            archive.add(ROOT/name, arcname=name, recursive=False)
    print(json.dumps({'files': collected, 'archive': str(ROOT/'release-evidence.tar.gz')}))

if __name__ == '__main__':
    main()
