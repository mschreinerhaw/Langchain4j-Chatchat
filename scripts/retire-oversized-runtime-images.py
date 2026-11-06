"""Replace stopped oversized rollback containers with verified compact equivalents.

Requires a successful compact deployment and a hash-verified offline archive.
Only the exact three source image IDs from candidates.json can be removed.
Never stops a running container, removes volumes, prunes, or uses force.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time


def run(*args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT).strip()
def inspect(ref):return json.loads(run('docker','container','inspect',ref))[0]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    args=parser.parse_args();os.umask(0o077)
    root=args.root.resolve()
    assert not (root/'retirement.json').exists(), 'Retirement already recorded'
    candidates=json.loads((root/'candidates.json').read_text())
    deployment=json.loads((root/'deployment.json').read_text());assert deployment['status']=='DEPLOYED'
    verification=json.loads((root/'verification-public.json').read_text());assert verification['status']=='PASS'
    for kind,name in [('api','runtime-os-app-stage-api-1'),('mcp','runtime-os-app-stage-mcp-1')]:
        assert inspect(name)['Image']==candidates['images'][kind]['imageId']==verification['services'][kind]['imageId']
    archive=Path(candidates['originalImagesArchive'])
    with archive.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==candidates['originalImagesArchiveSha256']
    mapping={v['oldImageId']:v for v in candidates['images'].values()}
    assert len(mapping)==3
    all_containers=json.loads(run('docker','container','inspect',*run('docker','ps','-aq','--no-trunc').split()))
    before={c['Id']:c for c in all_containers}
    selected={c['Id']:c for c in all_containers if c['Image'] in mapping}
    assert selected and all(not c['State']['Running'] for c in selected.values()), 'Oversized images still running'
    lookup={key:c['Id'] for c in all_containers for key in (c['Id'],c['Id'][:12],c['Name'].lstrip('/'))}
    def dependencies(c):
        return {lookup.get(c['HostConfig'].get(field,'').split(':',1)[1])
                for field in ('NetworkMode','PidMode','IpcMode') if c['HostConfig'].get(field,'').startswith('container:')}
    while True:
        additions={c['Id']:c for c in all_containers if dependencies(c)&selected.keys()}
        if additions.keys()<=selected.keys():break
        selected.update(additions)
    assert all(not c['State']['Running'] for c in selected.values()), 'Running namespace dependency prevents retirement'
    (root/'retired-containers-private.json').write_text(json.dumps(selected))
    spec=importlib.util.spec_from_file_location('deployment',root/'deploy-integrated-runtime.py')
    helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
    created={};stamp=str(int(time.time()));pending=dict(selected)
    while pending:
        progress=False
        for identity,old in list(pending.items()):
            if any(d in selected and d not in created for d in dependencies(old)):continue
            assert not inspect(identity)['State']['Running']
            config,host=copy.deepcopy(old['Config']),copy.deepcopy(old['HostConfig'])
            replacement=mapping.get(old['Image'])
            config['Image']=replacement['image'] if replacement else old['Image']
            host['Mounts']=[]
            host['Binds']=[(m.get('Name') if m['Type']=='volume' else m['Source'])+':'+m['Destination']+('' if m['RW'] else ':ro') for m in old['Mounts']]
            for field in ('NetworkMode','PidMode','IpcMode'):
                value=host.get(field,'')
                if value.startswith('container:') and lookup.get(value.split(':',1)[1]) in created:
                    host[field]='container:'+created[lookup[value.split(':',1)[1]]]['id']
            body={**config,'HostConfig':host}
            if host.get('NetworkMode','').startswith('container:'):
                for field in ('Hostname','Domainname','ExposedPorts'):body.pop(field,None)
            else:
                body['NetworkingConfig']={'EndpointsConfig':{net:{'Aliases':details.get('Aliases') or []} for net,details in old['NetworkSettings']['Networks'].items()}}
            name=old['Name'].lstrip('/');temporary=name+'-compact-'+stamp
            result=helper.docker('/containers/create?name='+temporary,body)
            current=inspect(result['Id'])
            assert not current['State']['Running']
            assert current['Image']==(replacement['imageId'] if replacement else old['Image'])
            for field in ('Env','Cmd','Entrypoint','User','WorkingDir','Labels'):
                assert current['Config'].get(field)==old['Config'].get(field), 'Container configuration changed: '+field
            for field in ('ReadonlyRootfs','CapDrop','Privileged','SecurityOpt','RestartPolicy','Memory','NanoCpus','PidsLimit','NetworkMode','PidMode','IpcMode'):
                assert current['HostConfig'].get(field)==host.get(field), 'Runtime policy changed: '+field
            normalize=lambda c:sorted((m['Type'],m['Source'],m['Destination'],m['RW']) for m in c['Mounts'])
            assert normalize(current)==normalize(old),'Mounts changed'
            created[identity]={'id':current['Id'],'temporary':temporary,'name':name,'originalId':identity,'imageId':current['Image']}
            del pending[identity];progress=True
        assert progress,'Cannot resolve stopped namespace dependencies'
    moved=[]
    try:
        for identity,replacement in created.items():
            assert not inspect(identity)['State']['Running']
            run('docker','rename',identity,replacement['name']+'-oversized-'+stamp)
            moved.append(identity)
            run('docker','rename',replacement['id'],replacement['name'])
    except Exception:
        for identity in reversed(moved):
            r=created[identity]
            if inspect(r['id'])['Name'].lstrip('/')!=r['temporary']:
                run('docker','rename',r['id'],r['temporary'])
            run('docker','rename',identity,r['name'])
        raise
    # Sidecars first so their stopped namespace references cannot pin old APIs.
    for identity in reversed(list(created)):
        assert not inspect(identity)['State']['Running']
        run('docker','container','rm',identity)
    removed=[]
    for identity,value in sorted(mapping.items(),key=lambda item:item[1]['oldBytes'],reverse=True):
        active=json.loads(run('docker','container','inspect',*run('docker','ps','-aq','--no-trunc').split()))
        assert not any(c['Image']==identity for c in active)
        image=json.loads(run('docker','image','inspect',identity))[0]
        tags=image.get('RepoTags') or []
        for tag in tags:assert run('docker','image','inspect','--format','{{.Id}}',tag)==identity
        run('docker','image','rm',*(tags or [identity]))
        assert subprocess.run(['docker','image','inspect',identity],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0
        removed.append({'imageId':identity,'tags':tags,'oldBytes':value['oldBytes']})
    after={c['Id']:c for c in json.loads(run('docker','container','inspect',*run('docker','ps','-aq','--no-trunc').split()))}
    for identity,old in before.items():
        if identity not in selected:
            assert identity in after and after[identity]['State']['Running']==old['State']['Running']
            assert after[identity]['State'].get('StartedAt')==old['State'].get('StartedAt')
    proof={'status':'RETIRED_AFTER_ARCHIVE','originalImagesArchiveSha256':candidates['originalImagesArchiveSha256'],
           'compactRollbackContainers':list(created.values()),'removedOversizedImages':removed,
           'unrelatedContainersUnchanged':True,'runningContainersUnchanged':True,'volumesRemoved':False,'usedForce':False}
    (root/'retirement.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof),flush=True)


if __name__=='__main__':main()
