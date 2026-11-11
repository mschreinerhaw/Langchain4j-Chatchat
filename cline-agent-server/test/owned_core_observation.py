"""Read-only observation of an already registered acceptance container.

No create/start/stop/delete capability. A dedicated daemon must match the private
registered record and the task's control-owned ownership receipt.
"""
from pathlib import Path
import json, re, subprocess

def observe_stopped(root, folder):
    root,folder=Path(root),Path(folder)
    final=json.loads((folder/'container-final.json').read_text())
    if not re.fullmatch(r'[0-9a-f]{64}',str(final.get('id',''))):raise RuntimeError('Full registered container ID required')
    command=['docker']
    if final.get('daemonSocket'):
        socket_path=Path(final['daemonSocket'])
        if socket_path.parent.name!='run' or socket_path.name!='docker.sock' or socket_path.parent.parent.parent!=root:
            raise RuntimeError('Unregistered daemon anchor')
        name=socket_path.parent.parent.name
        if not re.fullmatch(r'strict-daemon-[1-9][0-9]?',name):raise RuntimeError('Unregistered daemon name')
        record=json.loads((root/'evidence'/(name+'.json')).read_text())
        ownership=json.loads((folder/'restricted-worker-ownership.json').read_text())
        if record['socketPath']!=str(socket_path) or record['daemonId']!=final['daemonId']:
            raise RuntimeError('Registered daemon binding changed')
        if any(ownership[key]!=expected for key,expected in [('containerId',final['id']),('daemonId',final['daemonId']),('daemonSocket',str(socket_path))]):
            raise RuntimeError('Task ownership receipt differs')
        command+=['--host','unix://'+str(socket_path)]
        actual=json.loads(subprocess.check_output(command+['info','--format','{{json .}}'],timeout=20))
        if actual['ID']!=record['daemonId'] or actual['DockerRootDir']!=record['anchor']+'/data':
            raise RuntimeError('Wrong live dedicated daemon')
    observed=json.loads(subprocess.check_output(command+['inspect',final['id']],timeout=20))[0]
    if observed['Id']!=final['id'] or observed['State']['Running']:
        raise RuntimeError('Actual registered Agent container must be stopped')
    if final.get('daemonSocket') and observed['Config']['Labels']!=ownership['labels']:
        raise RuntimeError('Actual container ownership labels differ')
    return observed
