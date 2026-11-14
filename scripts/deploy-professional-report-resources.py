"""Server-side source build and optional activation of shared report resources."""
import json
import shutil
import subprocess
import sys
import time
import re
from pathlib import Path

def run(*args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT).strip()

def main():
    release=Path(sys.argv[1]).resolve();source=release/'source/frontend-resource-hub'
    deployed=json.loads(run('docker','inspect','frontend-resource-hub-frontend-resource-hub-1'))[0]
    base=deployed['Image'];tag='frontend-resource-hub:professional-reports-20261114'
    base_tag='runtime-resource-base:'+base.split(':')[1][:16];run('docker','tag',base,base_tag)
    with (release/'hub-build.log').open('w') as log:subprocess.run(['docker','build','--build-arg','HUB_IMAGE='+base_tag,'-f','Dockerfile.report-assets','-t',tag,'.'],cwd=source,stdout=log,stderr=subprocess.STDOUT,check=True)
    image=json.loads(run('docker','inspect',tag))[0]['Id'];record={'buildMode':'server-source','baseImage':base,'image':image,'activation':'NOT_ACTIVATED'}
    (release/'hub-deployment.json').write_text(json.dumps(record,indent=2))
    if '--activate' not in sys.argv:return
    active=json.loads(run('docker','exec','frontend-resource-hub-frontend-preview-worker-1','node','-e',"fetch('http://127.0.0.1:8081/health').then(r=>r.text()).then(console.log)"))
    if active.get('active')!=0:raise RuntimeError('Active preview jobs prevent replacement')
    folder=Path('/opt/frontend-resource-hub/1.0.0');compose=folder/'compose.yaml';backup=release/('hub-compose.rollback-'+image.split(':')[1][:12]+'.yaml')
    if backup.exists():raise RuntimeError('Existing rollback requires reconciliation')
    shutil.copy2(compose,backup);backup.chmod(0o600)
    text=compose.read_text();images=re.findall(r'^\s+image:\s+(\S+)',text,re.M)
    if len(images)!=2 or set(images)!={deployed['Config']['Image']}:raise RuntimeError('Unexpected deployed resource service baseline')
    updated=re.sub(r'^(\s+image:)\s+\S+',lambda m:m.group(1)+' '+image,text,flags=re.M)
    cmd=['docker','compose','--project-name','frontend-resource-hub','--env-file',str(folder/'.env'),'-f',str(compose)]
    try:
        compose.write_text(updated);run(*cmd,'up','-d','--no-deps','--force-recreate','frontend-resource-hub','frontend-preview-worker')
        for attempt in range(30):
            try:
                resources=json.loads(run('docker','exec','frontend-resource-hub-frontend-resource-hub-1','node','-e',"fetch('http://127.0.0.1:8080/api/v1/resources').then(r=>r.text()).then(console.log)"));break
            except subprocess.CalledProcessError:
                if attempt==29:raise
                time.sleep(1)
        if not any(r['id']=='professional-report' for r in resources):raise RuntimeError('Report resources not registered')
        record.update(activation='ACTIVE',resourceCount=len(resources),rollback=str(backup))
    except Exception:
        shutil.copy2(backup,compose);run(*cmd,'up','-d','--no-deps','--force-recreate','frontend-resource-hub','frontend-preview-worker');raise
    finally:(release/'hub-deployment.json').write_text(json.dumps(record,indent=2))
    print(json.dumps(record))

if __name__=='__main__':main()
