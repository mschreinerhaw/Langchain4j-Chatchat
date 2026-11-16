"""Restore into new replacement manager volumes, preserving current volumes."""
import json
from pathlib import Path
import subprocess
import shutil
import sys
import time

backup=Path(sys.argv[1]).resolve()
if sys.argv[2]!='RESTORE-MANAGEMENT' or not (backup/'COMPLETE').is_file():raise SystemExit('Complete backup and exact confirmation required')
if not (backup/'.env').is_file() or not all((backup/name).is_dir() for name in ('data','state')):raise SystemExit('Backup data, state or environment is missing')
if not (backup/'trust').is_dir():raise SystemExit('Backup trust configuration is missing')
if json.loads((backup/'images.lock.json').read_text())!=json.loads(Path('images.lock.json').read_text()):raise SystemExit('Restore requires the matching management release; review cross-version database compatibility separately')
compose=Path('compose.yaml');current=json.loads(compose.read_text())
if not {'web','executor'}<=set(current['services']):raise SystemExit('Unexpected management topology')
for service in ('web','executor'):
    info=subprocess.run(['docker','compose','ps','-q','--status','running',service],capture_output=True,text=True,check=True)
    if info.stdout.strip():raise SystemExit('Stop management web and executor before restore; workloads must remain running')
stamp=str(time.time_ns());candidate=json.loads(json.dumps(current));created=[]
try:
    for key,folder in [('ops-data','data'),('ops-state','state')]:
        volume='deployment-ops-restored-'+key+'-'+stamp
        subprocess.run(['docker','volume','create',volume],check=True,stdout=subprocess.DEVNULL);created.append(volume)
        candidate['volumes'][key]={'external':True,'name':volume}
    temporary=Path('restore-candidate-'+stamp+'.json');temporary.write_text(json.dumps(candidate,indent=2))
    # Changing a top-level external volume name is not always part of Compose's
    # service hash. Recreate only these already-stopped management containers.
    subprocess.run(['docker','compose','-f',str(temporary),'create','--force-recreate','--no-build','--pull','never','web','executor'],check=True)
    # A never-started container may not expose its named volume through archive
    # APIs. Restore through a running, isolated helper with the fresh volume.
    for service,folder,key in [('web','data','ops-data'),('executor','state','ops-state')]:
        volume=candidate['volumes'][key]['name']
        helper=subprocess.check_output(['docker','run','-d','--rm','--init','--network','none','--user','0:0','--label','deployment-ops.control-plane=true','-v',volume+':/restore','--entrypoint','sleep',current['services']['web']['image'],'600'],text=True).strip()
        try:
            subprocess.run(['docker','cp',str(backup/folder)+'/.',helper+':/restore'],check=True)
            if service=='web':subprocess.run(['docker','exec',helper,'chown','-R','10001:10001','/restore'],check=True)
        finally:subprocess.run(['docker','stop','-t','2',helper],check=True,stdout=subprocess.DEVNULL)
    compose.rename('compose.before-restore-'+stamp+'.json');temporary.rename(compose)
    Path('.env').rename('.env.before-restore-'+stamp)
    shutil.copy2(backup/'.env','.env');Path('.env').chmod(0o600)
    Path('trust').rename('trust.before-restore-'+stamp)
    shutil.copytree(backup/'trust','trust')
    print('Restored management data and its matching credentials into fresh volumes. Original volumes and configuration retained. Start management services explicitly after review.')
except Exception:
    print('Restore incomplete. Original data volumes retained. Candidate volumes: '+','.join(created),file=sys.stderr)
    raise
