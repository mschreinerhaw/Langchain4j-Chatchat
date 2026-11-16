"""Load the verified full offline TAR into ONLY the acceptance Runtime's private daemon.

The host daemon and pre-existing business containers are never mutated. Images are
retained for inspection; this runner performs no container, image or volume delete.
"""
import hashlib,json,os,subprocess,sys,time
from pathlib import Path

root=Path('/mnt/disk01/ops/r/99f2-076e271c2551')
socket=root/'d/strict/run/docker.sock'
state=Path('/mnt/disk01/deployment-ops-v1-release/state')
media=state/'packages/666f59d853dfc042e1307b0351d395fedcaca07197d6ab65e09a44738e21467c'
report=Path('/mnt/disk01/deployment-ops-v1-validation/final-full-media-load.json')
item=json.loads(subprocess.check_output(['docker','inspect','ops-env-runtime-release-v1-executor-daemon']))[0]
assert item['State']['Running'] and item['Config']['Labels']['com.docker.compose.project']=='ops-env-runtime-release-v1'
assert any(m['Source']==str(root/'d/strict') for m in item['Mounts'])
assert socket.is_socket() and socket.resolve()==socket and str(socket)!='/var/run/docker.sock'
home=Path(next(line.split('=',1)[1].strip().strip('"') for line in (state/'manager.env').read_text().splitlines() if line.startswith('OPS_HOME=')))
work=report.parent/'full-media-loader-state';work.mkdir(exist_ok=True)
os.environ.update(EXECUTOR_TOKEN='isolated-offline-acceptance',OPS_STATE=str(work),DOCKER_SOCKET=str(socket))
sys.path.insert(0,str(home/'executor'))
import agent

def business():
    ids=subprocess.check_output(['docker','ps','-q'],text=True).split()
    return sorted(subprocess.check_output(['docker','inspect','--format','{{.Id}} {{.State.StartedAt}} {{.State.Running}}',*ids],text=True).splitlines())

result={'socket':str(socket),'hostDockerMutations':False,'before':business(),'startedAt':time.time()}
try:
    manifest=json.loads((media/'manifest.json').read_text());checks=json.loads((media/'checksums.json').read_text())
    archive=media/'images/all.tar';digest=hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()
    expected=checks.get('files',checks)['images/all.tar']
    if isinstance(expected,dict):expected=expected.get('sha256')
    assert digest==expected
    result.update(archiveSha256=digest,archiveBytes=archive.stat().st_size)
    info=agent.docker('GET','/info');result['privateDaemonId']=info['ID']
    result['imagesBefore']=[i['Id'] for i in agent.docker('GET','/images/json')]
    pins={v['id'] for v in manifest['images'].values()}
    assert pins-set(result['imagesBefore']),'Acceptance requires actual missing images'
    agent.raw_load(archive)
    result['imagesAfter']=[i['Id'] for i in agent.docker('GET','/images/json')]
    assert pins.issubset(result['imagesAfter'])
    assert result['privateDaemonId']==agent.docker('GET','/info')['ID']
    result['allPinnedImagesVerified']=True;result['pinnedImages']=len(pins)
    result['after']=business();assert result['before']==result['after']
    result['businessUnchanged']=True;result['passed']=True
except Exception as error:
    result['passed']=False;result['error']=str(error);raise
finally:
    result['finishedAt']=time.time();report.write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k not in ['before','after','imagesBefore','imagesAfter']}))
