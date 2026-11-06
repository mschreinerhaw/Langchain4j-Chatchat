"""Recover verified dependency layers and rebuild byte-identical application images offline.

Prepares candidates only. Never exports a live container filesystem or copies its
secrets/mounted data. Archives the original images before preparing clean images.
"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

BASE = 'runtime-os-api-runtime:17-sqlglot-20261024'


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def runtime_prefix(config):
    history = config['history']
    boundary = next(i for i,h in enumerate(history)
                    if 'pip install' in h.get('created_by','') and '30.23.0' in h.get('created_by','')) + 1
    prefix = history[:boundary]
    if any('app.jar' in h.get('created_by','') for h in prefix):
        raise ValueError('Runtime prefix includes an application')
    count = sum(not h.get('empty_layer', False) for h in prefix)
    if not 0 < count < len(config['rootfs']['diff_ids']):
        raise ValueError('Cannot isolate clean dependency layers')
    clean = {k:copy.deepcopy(config[k]) for k in ('architecture','os','created') if k in config}
    clean['rootfs'] = {'type':'layers','diff_ids':config['rootfs']['diff_ids'][:count]}
    clean['history'] = copy.deepcopy(prefix)
    allowed = {'PATH','JAVA_HOME','JAVA_VERSION','LANG','LANGUAGE','LC_ALL','TZ'}
    clean['config'] = {'Env':[e for e in config['config'].get('Env',[]) if e.split('=',1)[0] in allowed],
                       'Cmd':['/bin/bash'], 'WorkingDir':'/', 'User':'',
                       'Labels':{}}
    return clean, count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--reuse-original-snapshot', action='store_true', help='Rebuild from preserved original containers/archive, retaining the prior candidate proof')
    args = parser.parse_args()
    os.umask(0o077)
    root = args.root.resolve();root.mkdir(parents=True, exist_ok=True)
    if (root/'candidates.json').exists():
        assert args.reuse_original_snapshot, 'Candidates already prepared'
        shutil.copyfile(root/'candidates.json',root/'candidates-before-metadata-correction.json')
    names = {'api':'runtime-os-app-stage-api-1','mcp':'runtime-os-app-stage-mcp-1',
             'rollback':'runtime-os-app-stage-api-1-before-studio-1793948121'}
    before = json.loads((root/'containers-private.json').read_text()) if args.reuse_original_snapshot else {key:json.loads(run('docker','inspect',name))[0] for key,name in names.items()}
    for old in before.values():
        assert json.loads(run('docker','inspect',old['Id']))[0]['Image']==old['Image'], 'Original source container changed'
    assert all(before[k]['State']['Running'] for k in ('api','mcp')) and not before['rollback']['State']['Running']
    assert not run('docker','ps','-q','--filter','name=chatchat-py-'), 'Active Python operation'
    if not args.reuse_original_snapshot:
        (root/'containers-private.json').write_text(json.dumps(before))
    archive = root/'original-images.tar'
    if not archive.exists():
        assert shutil.disk_usage(root).free > 16 * 1024**3, 'Insufficient space for original image archive'
        run('docker','save','-o',str(archive),*[before[k]['Config']['Image'] for k in names])
    original_sha = sha(archive)
    with tarfile.open(archive) as source:
        manifest=json.load(source.extractfile('manifest.json'))
        mcp=next(row for row in manifest if before['mcp']['Config']['Image'] in row.get('RepoTags',[]))
        raw=source.extractfile(mcp['Config']).read()
        assert 'sha256:'+hashlib.sha256(raw).hexdigest()==before['mcp']['Image'], 'Source image config identity mismatch'
        full=json.loads(raw)
        clean,count=runtime_prefix(full)
        data=json.dumps(clean,separators=(',',':')).encode()
        config_name=hashlib.sha256(data).hexdigest()+'.json'
        layers=mcp['Layers'][:count]
        with tarfile.open(root/'clean-runtime.tar','w') as output:
            for name in layers:
                entry=source.getmember(name)
                assert entry.isfile(), 'Layer must be a regular archive member'
                output.addfile(copy.copy(entry),source.extractfile(entry))
            for name,body in [(config_name,data),('manifest.json',json.dumps([{'Config':config_name,'RepoTags':[BASE],'Layers':layers}]).encode())]:
                entry=tarfile.TarInfo(name);entry.size=len(body);entry.mode=0o644
                output.addfile(entry,io.BytesIO(body))
    run('docker','load','-i',str(root/'clean-runtime.tar'))
    base=json.loads(run('docker','image','inspect',BASE))[0]
    assert base['RootFS']['Layers']==clean['rootfs']['diff_ids']
    assert 'app.jar' not in run('docker','history','--no-trunc','--format','{{.CreatedBy}}',BASE)
    flags=['--rm','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true']
    run('docker','run',*flags,'--entrypoint','/usr/bin/python3',BASE,'-c',"import sqlglot;assert sqlglot.__version__=='30.23.0'")
    java=run('docker','run',*flags,'--entrypoint','java',BASE,'-version')
    candidates={}
    for kind,old in before.items():
        context=root/kind;context.mkdir(exist_ok=True)
        run('docker','cp',old['Id']+':/srv/app/app.jar',str(context/'app.jar'))
        jar_sha=sha(context/'app.jar')
        docker_sha=None
        extra=''
        if kind=='mcp':
            run('docker','cp',old['Id']+':/usr/local/bin/docker',str(context/'docker'))
            docker_sha=sha(context/'docker')
            extra='COPY --chmod=0755 docker /usr/local/bin/docker\n'
        tag='runtime-os-'+('mcp' if kind=='mcp' else 'api')+':compact-'+('rollback-' if kind=='rollback' else '')+jar_sha[:12]+'-20261106'
        dockerfile='FROM '+BASE+'\n'+extra+'WORKDIR /srv/app\nRUN mkdir -p /srv/app/config /srv/app/data /srv/app/logs\nCOPY app.jar /srv/app/app.jar\nENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai\nEXPOSE '+('8090 9091' if kind=='mcp' else '8080')+'\nENTRYPOINT ["java","-jar","/srv/app/app.jar"]\n'
        (context/'Dockerfile').write_text(dockerfile)
        (context/'.dockerignore').write_text('*\n!Dockerfile\n!app.jar\n!docker\n')
        with (context/'build.log').open('w') as log:
            subprocess.run(['docker','build','--network','none','--label','org.opencontainers.image.revision='+args.source_commit,'-t',tag,str(context)],stdout=log,stderr=subprocess.STDOUT,check=True)
        image=json.loads(run('docker','image','inspect',tag))[0]
        assert image['Size']<1200*1024**2
        history=run('docker','history','--no-trunc','--format','{{.CreatedBy}}',tag)
        assert sum('COPY ' in line and 'app.jar' in line for line in history.splitlines())==1
        assert run('docker','run',*flags,'--entrypoint','sha256sum',tag,'/srv/app/app.jar').split()[0]==jar_sha
        if docker_sha:
            assert run('docker','run',*flags,'--entrypoint','sha256sum',tag,'/usr/local/bin/docker').split()[0]==docker_sha
            run('docker','run',*flags,'--entrypoint','docker',tag,'--version')
        candidates[kind]={'image':tag,'imageId':image['Id'],'bytes':image['Size'],'jarSha256':jar_sha,'dockerCliSha256':docker_sha,
                          'oldImageId':old['Image'],'oldImage':old['Config']['Image'],'oldBytes':json.loads(run('docker','image','inspect',old['Image']))[0]['Size'],
                          'oneBusinessJarLayer':True,'jarByteIdentical':True}
    proof={'status':'PREPARED','sourceCommit':args.source_commit,'baseImage':BASE,'baseImageId':base['Id'],'baseBytes':base['Size'],
           'baseHasNoBusinessJar':True,'dependencyLayerDiffIdsUnchanged':True,'java':java,'originalImagesArchive':str(archive),
           'originalImagesArchiveSha256':original_sha,'images':candidates,'trafficSwitched':False,'at':datetime.now(timezone.utc).isoformat()}
    (root/'candidates.json').write_text(json.dumps(proof,indent=2))
    print(json.dumps(proof),flush=True)


if __name__=='__main__':
    main()
