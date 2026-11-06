"""Publish only the verified API/MCP increment through the existing idle/backup/readiness gates.

Does not build or replace the Cline Worker, modify models/grants, migrate a database,
or reclaim images/data. A persisted intent forbids silent deployment replay.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request


def main():
    os.umask(0o077)
    root=Path(sys.argv[1]).resolve();expected=sys.argv[2]
    api_only=sys.argv[3:]==['--api-only'];assert api_only or not sys.argv[3:]
    assert root.parent==Path('/var/tmp') and root.name.startswith('runtime-empty-p0-release-')
    assert len(expected)==64 and all(c in '0123456789abcdef' for c in expected)
    intent=root/'release-intent.json';assert not intent.exists(),'Do not replay a release with an unknown outcome'
    bundle=root/'bundle.tar.gz';assert hashlib.sha256(bundle.read_bytes()).hexdigest()==expected
    with tarfile.open(bundle) as archive:
        assert all((root/member.name).resolve().is_relative_to(root) and not member.issym() and not member.islnk() for member in archive.getmembers())
        archive.extractall(root)
    before=json.loads(subprocess.check_output(['docker','inspect','runtime-os-app-stage-coding-agent-1'],text=True))[0]
    source=json.loads((root/'bundle/bundle-manifest.json').read_text())['sourceCommit']
    intent.write_text(json.dumps({'startedAt':time.time(),'sourceCommit':source,'bundleSha256':expected,'workerImageBefore':before['Image']}))
    previous=Path('/var/tmp/runtime-cline-acceptance-20261107-progress')
    for name in ['catalog-readiness-private.json','restrict-coding-worker.sh']:shutil.copy2(previous/name,root/name)
    with (root/'prepare.log').open('w') as stream:
        subprocess.run(['python3',str(root/'prepare-runtime-data-remediation.py'),str(root),'--api-only' if api_only else '--api-mcp'],check=True,stdout=stream,stderr=subprocess.STDOUT)
    manifest=json.loads((root/'manifest.json').read_text())
    assert manifest['sourceCommit']==source and not manifest.get('worker') and not manifest.get('news')
    assert not manifest.get('mcp') if api_only else manifest['backupFinancialStorage'] and manifest.get('mcp')
    manifest.update(catalogReadinessAuthFile='catalog-readiness-private.json',catalogReadinessExpectedTools=['web_search'])
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2))
    with (root/'deployment.log').open('w') as stream:
        subprocess.run(['python3',str(root/'deploy-integrated-runtime.py')],check=True,stdout=stream,stderr=subprocess.STDOUT)
    actual=json.loads(subprocess.check_output(['docker','inspect','runtime-os-app-stage-coding-agent-1'],text=True))[0]
    assert actual['Image']==before['Image'],'SDK Worker must remain unchanged'
    checks=[('runtime-os-app-stage-api-1',manifest['sha256'])]
    if not api_only:checks.append(('runtime-os-app-stage-mcp-1',manifest['mcp']['sha256']))
    for container,digest in checks:
        assert subprocess.check_output(['docker','exec',container,'sha256sum','/srv/app/app.jar'],text=True).split()[0]==digest
    credentials=json.loads((root/'catalog-readiness-private.json').read_text())
    request=urllib.request.Request('http://127.0.0.1:18180/api/v1/enterprise/auth/login',data=json.dumps(credentials).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=60) as response:auth=json.load(response)
    assert auth['code']==200;(root/'auth-private.json').write_text(json.dumps(auth['data']))
    mcp_sha=subprocess.check_output(['docker','exec','runtime-os-app-stage-mcp-1','sha256sum','/srv/app/app.jar'],text=True).split()[0]
    proof={'status':'DEPLOYED','sourceCommit':source,'apiJarSha256':manifest['sha256'],'mcpJarSha256':mcp_sha,'apiOnlyRelease':api_only,
        'workerImageUnchanged':True,'workerImage':actual['Image'],'sharedModelOrBudgetChanged':False,'dataAuthorizationExpanded':False,
        'existingBackupAndReadinessGatesUsed':True,'completedAt':time.time()}
    (root/'release-completed.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))


if __name__=='__main__':main()
