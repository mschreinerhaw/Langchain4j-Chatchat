"""Collect an explicit allowlist of sanitized acceptance evidence; never package runtime secrets/data."""
import json
import os
from pathlib import Path
import tarfile
import urllib.request

os.umask(0o077)
stage=Path('/opt/runtime-os-docker-stage')
cases=stage/'evidence/runtime-os-refactor-20261020'
release=stage/'evidence/runtime-os-scenarios-final-20261021'
for folder in (cases,release,stage/'evidence/runtime-os-scenarios-20261020'):
    folder.chmod(0o700)
metrics=json.loads((cases/'scenario-metrics.json').read_text())
labels=('scenario1-final','scenario2-final','scenario-qa-final')
for label in labels:
    sample=metrics[label]
    if sample['status']!='SUCCEEDED' or sample['acceptanceStatus']!='PASSED' or not sample['resultAdmitted']:
        raise RuntimeError('Final acceptance has not passed')
    if sample['javaSemanticReviewInvoked'] or not sample['skillDeliveryWithoutJavaInference']:
        raise RuntimeError('Java analytical inference remains in the verified chain')
    if sample['pythonCleanupPending'] or any(not task['executorStopped'] or task['reconciliationRequired'] for task in sample['codingConfiguration']):
        raise RuntimeError('Execution or cleanup remains unsettled')
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
request=urllib.request.Request('http://127.0.0.1:18100/v1/monitor/node',
    headers={'Authorization':'Bearer '+(stage/'config/coding-node-monitor.token').read_text().strip()})
with client.open(request,timeout=10) as response: node=json.load(response)
post={'status':'PASSED','finalParentsSucceeded':True,'finalCodingStopped':True,
    'reconciliationRequired':False,'activeNodeSessions':node['sessions']['active'],
    'pythonContainersReleased':sum(metrics[label]['pythonContainersReleased'] for label in labels),
    'pythonCleanupPending':sum(metrics[label]['pythonCleanupPending'] for label in labels),
    'javaAnalyticalReviewInvoked':False,'skillDeliveryModelCalls':0,'productionSwitched':False}
if post['activeNodeSessions']!=0: raise RuntimeError('Worker still has active sessions; retain pending evidence')
(release/'post-validation.json').write_text(json.dumps(post,indent=2))
files={
    'scenario-metrics.json':cases/'scenario-metrics.json',
    'before-reconciliation.json':cases/'scenario-before-reconciliation.json',
    **{name:release/name for name in ('module-manifest.json','module-package.json','node-manifest.json',
        'deployment.json','deployment-runner-analysis-fast.json','boundary.json','post-validation.json')},
    **{label+'-artifacts.json':cases/(label+'-artifacts.json') for label in ('scenario1-final','scenario2-final')},
}
for file in files.values():
    data=json.loads(file.read_text())
    def check(value):
        if isinstance(value,dict):
            for key,item in value.items():
                if key.lower() in ('password','apikey','api_key','authorization','baseurl','proxy','question',
                    'prompt','content','analysispreview','rows','records'):
                    raise RuntimeError('Private field cannot enter release evidence')
                check(item)
        elif isinstance(value,list):
            for item in value: check(item)
    check(data)
archive=release/'sanitized-scenario-evidence.tar.gz'
with tarfile.open(archive,'w:gz') as bundle:
    for name,file in files.items(): bundle.add(file,arcname=name,recursive=False)
print(json.dumps({'archive':str(archive),'files':sorted(files),**post}),flush=True)
