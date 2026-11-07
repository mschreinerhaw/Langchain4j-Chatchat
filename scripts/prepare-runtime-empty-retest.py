"""Create new independent nodes for the three failed file cases after the reviewed repair.

Reuses exact original asset IDs, bytes, model and question. No old task is replayed,
no historical report is included and no model, grant or per-task budget is changed.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import time
import urllib.request


def main():
    os.umask(0o077)
    original=Path('/var/tmp/runtime-empty-p0-20261107');root=Path('/var/tmp/runtime-empty-p0-20261107-retest')
    root.mkdir(mode=0o700,exist_ok=True);intent=root/'prepare-intent.json';assert not intent.exists()
    release=Path('/var/tmp/runtime-empty-p0-release-20261107-receipt');assert (release/'release-completed.json').exists()
    shutil.copy2(release/'auth-private.json',root/'auth-private.json');token=json.loads((root/'auth-private.json').read_text())['token']
    intent.write_text(json.dumps({'startedAt':time.time(),'cases':['E04','E05','E08'],'policy':'New nodes, unchanged original files/question/model; no previous task replay'}))
    prepared=json.loads((original/'model-cases-prepared-private.json').read_text());cases=[]
    def api(path,body=None):
        request=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+token,'X-Workspace-Id':'data-exploration'})
        with urllib.request.urlopen(request,timeout=60) as response:value=json.load(response)
        assert value['code'] in [200,201,202];return value['data']
    for old in prepared['cases']:
        if old['case'] not in ['E04','E05','E08']:continue
        for f in old['inputFiles']:
            request=urllib.request.Request('http://127.0.0.1:18180/api/v1/data-science/user-assets/'+f['fileId']+'/content',headers={'Authorization':'Bearer '+token})
            with urllib.request.urlopen(request,timeout=60) as response:raw=response.read()
            assert hashlib.sha256(raw).hexdigest()==f['sha256']
        space=api('/data-science/explorations/'+old['workspaceId']+'/nodes/'+old['nodeId']+'/status')
        space=api('/data-science/explorations/'+old['workspaceId']+'/nodes',{'version':space['version'],'parentId':'','fileIds':[f['fileId'] for f in old['inputFiles']],'question':old['question'],'modelName':old['model']})
        new={**old,'nodeId':space['nodes'][-1]['id'],'previousNodeId':old['nodeId']};assert new['nodeId']!=old['nodeId'];cases.append(new)
        (root/'model-cases-prepared-private.json').write_text(json.dumps({'cases':cases,'level':'FILE_ONLY_NATIVE_REPORTING'},ensure_ascii=False,indent=2))
    assert len(cases)==3
    proof={'newIndependentTasks':True,'previousFailuresRetained':True,'sharedModelOrAuthorizationChanged':False,
        'cases':[{'case':c['case'],'previousNodeId':c['previousNodeId'],'nodeId':c['nodeId'],'sameAssetIdsAndHashes':True,
            'sameQuestionSha256':hashlib.sha256(c['question'].encode()).hexdigest(),'sameModel':c['model'],
            'originalFiles':c['inputFiles']} for c in cases]}
    (root/'retest-comparability-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps({'prepared':len(cases),'sameOriginalFiles':True}))


if __name__=='__main__':main()
