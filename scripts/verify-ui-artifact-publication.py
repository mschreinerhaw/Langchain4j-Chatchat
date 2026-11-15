"""Verify a real authenticated report replay against its physical UI object.

Run on the source-build server: RELEASE CASE_DIRECTORY [STORAGE_ROOT].
Uses RELEASE/auth.private.json, never prints the token or report body.
"""
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.parse
import urllib.request


def main():
    release=Path(sys.argv[1]);case=release/sys.argv[2]
    storage=Path(sys.argv[3] if len(sys.argv)>3 else '/opt/chatchat-1.0.0-SNAPSHOT/data/ui-artifacts').resolve()
    auth=json.loads((release/'auth.private.json').read_text())
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def api(path):
        request=urllib.request.Request('http://127.0.0.1:18100/api/v1'+path,headers={'Authorization':'Bearer '+auth['token']})
        with client.open(request,timeout=30) as response:
            value=json.load(response)
            if value['code']!=200:raise RuntimeError('Authenticated resource readback failed')
            return value['data']
    task=json.loads((case/'task.json').read_text())
    if task['canonicalState'] not in ['SUCCEEDED','PARTIAL']:
        raise RuntimeError('The actual business task did not publish a supported report')
    payload=json.loads((case/'response.json').read_text())
    reference=payload.get('uiArtifact') or payload.get('uiResponse',{}).get('uiArtifact')
    if not reference:raise RuntimeError('No actual UI artifact reference')
    artifact=reference['artifactId']
    if not re.fullmatch(r'ui_[a-zA-Z0-9_-]+',artifact):raise RuntimeError('Invalid artifact identity')
    manifest=api('/ui-artifacts/'+artifact)
    entry=manifest['resources']['answer']
    if entry['objectKey']!='resources/answer.json':raise RuntimeError('Existing object path contract changed')
    answer=api('/ui-artifacts/'+artifact+'/resources/answer')
    tenant=auth['user']['tenantId'].strip()
    path=(storage/hashlib.sha256(tenant.encode()).hexdigest()/artifact/entry['objectKey']).resolve()
    if not path.is_relative_to(storage):raise RuntimeError('Artifact path escaped the storage root')
    raw=path.read_bytes();digest=hashlib.sha256(raw).hexdigest()
    metadata=json.loads(path.with_name(path.name+'.metadata.json').read_text())
    if digest!=entry['sha256'] or len(raw)!=entry['byteLength'] or json.loads(raw)!=answer:
        raise RuntimeError('Physical bytes, manifest and API resource differ')
    if metadata['sha256']!=digest or metadata['contentLength']!=len(raw):
        raise RuntimeError('Physical object metadata differs')
    original=json.loads((release/'original-failure.json').read_text())
    old=api('/agent/tasks/'+original['taskId']+'?tenantId='+urllib.parse.quote(tenant))
    if old['canonicalState']!='FAILED':raise RuntimeError('Historical failure was unexpectedly rewritten')
    if task['agentId']!=old['agentId'] or hashlib.sha256(task['question'].encode()).hexdigest()!=original['questionSha256']:
        raise RuntimeError('Replay did not use the original question and Agent')
    receipt=dict(status='PASSED',taskId=task['taskId'],businessState=task['canonicalState'],
                 originalFailedTaskId=original['taskId'],originalFailurePreserved=True,
                 artifactId=artifact,objectKey=entry['objectKey'],byteLength=len(raw),sha256=digest,
                 authenticatedReadback=True,physicalObjectVerified=True,metadataVerified=True,
                 sameQuestionAndAgent=True,agentId=task['agentId'],
                 publicEntryPort=18100)
    (case/'publication-verification.json').write_text(json.dumps(receipt,indent=2))
    print(json.dumps(receipt))


if __name__=='__main__':
    main()
