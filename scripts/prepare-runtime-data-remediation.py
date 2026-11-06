"""Prepare hash-pinned incremental images from live JARs; does not switch traffic.

Run on server after verifying/extracting bundle.tar.gz: python3 SCRIPT RELEASE_DIR
Uses existing deploy-integrated-runtime.py for idle gates, backup and deployment.
"""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def rewrite(original, changes):
    output = io.BytesIO()
    remaining = dict(changes)
    unchanged = {}
    with zipfile.ZipFile(io.BytesIO(original)) as before, zipfile.ZipFile(output, 'w') as after:
        for info in before.infolist():
            body = before.read(info.filename)
            if info.filename in remaining:
                body = remaining.pop(info.filename)
            else:
                unchanged[info.filename] = sha(body)
            after.writestr(info, body)
        for name, body in remaining.items():
            after.writestr(zipfile.ZipInfo(name, date_time=(1980,1,1,0,0,0)), body)
    with zipfile.ZipFile(io.BytesIO(output.getvalue())) as check:
        assert all(sha(check.read(k)) == v for k, v in unchanged.items())
    return output.getvalue(), len(unchanged)


def main():
    root = Path(sys.argv[1]).resolve()
    bundle = root / 'bundle'
    manifest = json.loads((bundle / 'bundle-manifest.json').read_text())
    for name, digest in manifest['files'].items():
        path = (bundle / name).resolve()
        assert path.is_relative_to(bundle.resolve()) and path.is_file() and sha(path.read_bytes()) == digest
    groups = {group: {name[len(group)+1:]: (bundle/name).read_bytes()
                     for name in manifest['files'] if name.startswith(group+'/')}
              for group in ['api', 'mcp', 'common', 'market', 'news', 'node', 'static']}
    libraries = {'common': 'chatchat-common-', 'market': 'chatchat-runtime-market-', 'news': 'chatchat-runtime-news-'}
    report = {'sourceCommit': manifest['sourceCommit'], 'preserveRuntimeConfiguration': True,
              'removeInheritedRuntimeBaseLabels': True, 'backupFinancialStorage': True,
              'preparationScriptSha256':sha(Path(__file__).read_bytes())}
    proof = {'trafficSwitched': False, 'services': {}}
    for service in ['api', 'mcp', 'news']:
        name = 'runtime-os-app-stage-'+service+'-1'
        current = json.loads(run('docker', 'inspect', name))[0]
        original = subprocess.check_output(['docker', 'exec', name, 'cat', '/srv/app/app.jar'])
        (root/(service+'-baseline.jar')).write_bytes(original)
        replacements = {}
        with zipfile.ZipFile(io.BytesIO(original)) as archive:
            for group, prefix in libraries.items():
                nested = [n for n in archive.namelist() if n.startswith('BOOT-INF/lib/'+prefix) and n.endswith('.jar')]
                if nested:
                    assert len(nested) == 1
                    replacements[nested[0]], _ = rewrite(archive.read(nested[0]), groups[group])
            replacements.update({'BOOT-INF/classes/'+k: v for k, v in groups[service].items()})
            if service == 'api':
                replacements.update({'BOOT-INF/classes/static/'+k: v for k, v in groups['static'].items()})
        candidate, retained = rewrite(original, replacements)
        jar = root/('app.jar' if service == 'api' else service+'.jar')
        jar.write_bytes(candidate)
        image = 'runtime-os-'+service+':data-remediation-'+sha(candidate)[:12]
        directory = root/('build-'+service)
        directory.mkdir(exist_ok=True)
        (directory/'app.jar').write_bytes(candidate)
        # Existing clean runtime layers; do not retain previous business JARs in ancestry.
        base = 'runtime-os-api-runtime:17-sqlglot-20261024'
        assert 'app.jar' not in run('docker','history','--no-trunc','--format','{{.CreatedBy}}',base)
        extra=''
        if service=='mcp':
            # The clean Java base intentionally excludes the deployment's Docker CLI.
            # Python runtime probing/execution requires the exact existing binary.
            binary=subprocess.check_output(['docker','exec',name,'cat','/usr/local/bin/docker'])
            (directory/'docker').write_bytes(binary)
            extra='COPY --chmod=0755 docker /usr/local/bin/docker\n'
        (directory/'Dockerfile').write_text('FROM '+base+'\n'+extra+'WORKDIR /srv/app\nCOPY app.jar /srv/app/app.jar\n')
        subprocess.run(['docker','build','--network','none','-t',image,str(directory)],check=True,stdout=sys.stderr)
        entry = {'image':image,'sha256':sha(candidate),'baselineSha256':sha(original),
                 'baselineImageId':current['Image'],'preparedImageId':run('docker','image','inspect','-f','{{.Id}}',image)}
        if service=='mcp':
            entry['dockerCliSha256']=sha(binary)
            assert run('docker','run','--rm','--network','none','--read-only','--cap-drop','ALL','--entrypoint','sha256sum',image,'/usr/local/bin/docker').split()[0]==sha(binary)
            run('docker','run','--rm','--network','none','--read-only','--cap-drop','ALL','--entrypoint','docker',image,'--version')
        if service == 'api':
            report.update({k: entry[k] for k in ['image','sha256','baselineSha256']})
        else:
            report[service] = entry
        proof['services'][service] = dict(entry, unchangedJarEntries=retained, replacedEntries=sorted(replacements))
    name='runtime-os-app-stage-coding-agent-1'
    current=json.loads(run('docker','inspect',name))[0]
    directory=root/'build-worker';directory.mkdir(exist_ok=True)
    for name,body in groups['node'].items():(directory/name).write_bytes(body)
    tag='runtime-os-coding-agent:data-remediation-'+manifest['sourceCommit'][:12]
    base_tag='runtime-os-coding-agent:data-remediation-base-'+current['Image'].split(':')[-1][:12]
    subprocess.run(['docker','tag',current['Image'],base_tag],check=True)
    # Runtime worker keeps its installed, hash-pinned SDK and all original monitor assets.
    (directory/'Dockerfile').write_text('FROM '+base_tag+'\nCOPY --chown=node:node *.js /srv/coding-agent/dist/\n')
    subprocess.run(['docker','build','--network','none','-t',tag,str(directory)],check=True,stdout=sys.stderr)
    image=run('docker','image','inspect','-f','{{.Id}}',tag)
    report['worker']={'image':tag,'preparedImageId':image,'baselineImageId':current['Image'],
                      'modules':{k:sha(v) for k,v in groups['node'].items()}}
    (root/'worker-build.json').write_text(json.dumps({'imageId':image,'offlineBuildAndTests':False}))
    (root/'manifest.json').write_text(json.dumps(report,indent=2))
    (root/'artifact-proof.json').write_text(json.dumps(proof,indent=2))
    print(json.dumps({'prepared':True,'trafficSwitched':False,'sourceCommit':manifest['sourceCommit']}))


if __name__ == '__main__':
    main()
