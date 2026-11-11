"""Private acceptance-resource ownership registry; never a Docker wildcard.

The registry does not authorize deletion. Final cleanup separately requires all
acceptance/archive/push gates and fresh ownership/activity checks for each ID.
"""
import fcntl, json, os, uuid
from pathlib import Path

def register_container(root,inspection,name,purpose,case):
    root=Path(root);path=root/'evidence/resource-registry.json'
    with path.with_suffix('.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        registry=json.loads(path.read_text()) if path.exists() else {'resources':[]}
        ident=inspection['Id']
        if any(item.get('id')==ident for item in registry['resources']):return
        registry['resources'].append({'type':'container','id':ident,'name':name,'image':inspection['Image'],
            'created':inspection['Created'],'purpose':purpose,'case':case,'retainedUntilAcceptanceAndPush':True})
        temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
        with temp.open('x') as stream:
            stream.write(json.dumps(registry,indent=2));stream.flush();os.fsync(stream.fileno())
        temp.replace(path)
        directory=os.open(str(path.parent),os.O_RDONLY)
        try:os.fsync(directory)
        finally:os.close(directory)
