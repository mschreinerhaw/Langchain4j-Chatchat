"""Assemble a reviewed class/static overlay on the running release, test the existing Cline image, prepare rollback deployment."""
import hashlib,io,json,os,shutil,subprocess,sys,zipfile
from pathlib import Path

def run(*args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT).strip()
def sha(data):return hashlib.sha256(data).hexdigest()

def main():
    os.umask(0o077)
    root=Path(sys.argv[1]).resolve()
    assert root.parent==Path('/opt/chatchat-releases') and root.name.startswith('knowledge-exploration-')
    archive=root/'release.zip'
    assert sha(archive.read_bytes())==sys.argv[2], 'Upload hash mismatch'
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            target=(root/entry.filename).resolve()
            assert target.is_relative_to(root), 'Unsafe release member'
        source.extractall(root)
    api='runtime-os-app-stage-api-1';node='runtime-os-app-stage-coding-agent-1'
    before=json.loads(run('docker','inspect',api))[0]
    worker=json.loads(run('docker','inspect',node))[0]
    run('docker','cp',api+':/srv/app/app.jar',str(root/'before.jar'))
    original=root/'before.jar';groups=json.loads((root/'prefixes.json').read_text())
    def replaced(name,prefixes):return any(name==prefix+'.class' or name.startswith(prefix+'$') for prefix in prefixes)
    def overlay_jar(data,module):
        output=io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(data)) as source,zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                if not replaced(item.filename,groups[module]) and not (module=='chatchat-chat' and item.filename=='coding-skills/catalog.json'):
                    target.writestr(item,source.read(item.filename))
            for item in (root/'patch'/module).rglob('*'):
                if item.is_file():target.write(item,item.relative_to(root/'patch'/module).as_posix())
        return output.getvalue()
    changed=[]
    with zipfile.ZipFile(original) as source,zipfile.ZipFile(root/'app.jar','w') as target:
        for item in source.infolist():
            name=item.filename
            if name.startswith('BOOT-INF/classes/static/') or name.startswith('BOOT-INF/classes/') and replaced(name.removeprefix('BOOT-INF/classes/'),groups['api']):continue
            module=next((m for m in groups if m!='api' and name.startswith('BOOT-INF/lib/'+m+'-') and name.endswith('.jar')),None)
            if module:target.writestr(item,overlay_jar(source.read(name),module));changed.append(name)
            else:target.writestr(item,source.read(name))
        for folder,prefix in [(root/'patch/api','BOOT-INF/classes'),(root/'static','BOOT-INF/classes/static')]:
            for path in folder.rglob('*'):
                if path.is_file():target.write(path,prefix+'/'+path.relative_to(folder).as_posix())
    # Resource extension keeps News Runtime and MCP as independently hosted existing services.
    api_modules=set(groups)-{'api','chatchat-mcp-server','chatchat-runtime-news','chatchat-runtime-market'}
    assert len(changed)==len(api_modules), 'Missing patched API dependency'
    (root/'app.jar').chmod(0o644)
    with zipfile.ZipFile(original) as source,zipfile.ZipFile(root/'app.jar') as target:
        assert target.testzip() is None
        assert all(source.read(n)==target.read(n) for n in source.namelist() if n not in changed and not n.startswith('BOOT-INF/classes/static/')
            and not (n.startswith('BOOT-INF/classes/') and replaced(n.removeprefix('BOOT-INF/classes/'),groups['api'])))
    base='runtime-os-coding-agent:knowledge-baseline-'+worker['Image'].split(':')[-1][:16]
    run('docker','tag',worker['Image'],base)
    image='runtime-os-coding-agent:'+root.name
    build=root/'worker-build';build.mkdir(exist_ok=True)
    shutil.copytree(root/'worker',build/'worker',dirs_exist_ok=True)
    (build/'Dockerfile').write_text('FROM '+base+'\nUSER root\nCOPY worker/dist/ /srv/coding-agent/dist/\nCOPY worker/skills/ /srv/coding-agent/skills/\nRUN chmod -R a+rX /srv/coding-agent/dist /srv/coding-agent/skills\nUSER '+(worker['Config'].get('User') or 'root')+'\n')
    run('docker','build','--network','none','-t',image,str(build))
    verification=root/'verification'
    verification.chmod(0o755)
    for path in verification.rglob('*'):
        if not path.is_symlink():path.chmod(0o755 if path.is_dir() else 0o644)
    shared=root/'verification-common';shared.chmod(0o755)
    for path in shared.rglob('*'):path.chmod(0o755 if path.is_dir() else 0o644)
    for name,destination in [('skills','/srv/coding-agent/skills'),('node_modules','/srv/coding-agent/node_modules')]:
        link=verification/name
        if not link.is_symlink():link.symlink_to(destination,target_is_directory=True)
    command=['docker','run','--rm','--network','none','--mount','type=bind,src='+str(verification)+',dst=/verification,readonly',
        '--mount','type=bind,src='+str(shared)+',dst=/chatchat-common,readonly',
        '--workdir','/verification','--entrypoint','sh',image,'-c','node --test --test-concurrency=1 test/*.test.js']
    with (root/'worker-tests.log').open('w') as log:subprocess.run(command,check=True,stdout=log,stderr=subprocess.STDOUT,timeout=240)
    modules={path.name:sha(path.read_bytes()) for path in (root/'worker/dist').glob('*.js')}
    (root/'worker-build.json').write_text(json.dumps({'imageId':run('docker','image','inspect','-f','{{.Id}}',image),'offlineBuildAndTests':True,'modules':modules},indent=2))
    provenance=json.loads((root/'build-provenance.json').read_text()) if (root/'build-provenance.json').exists() else {}
    manifest={'image':'runtime-os-api:'+root.name,'sourceCommit':provenance.get('sourceCommit','743a01304-runtime-knowledge-p0'),
        'baselineSha256':sha(original.read_bytes()),'sha256':sha((root/'app.jar').read_bytes()),'knowledgeCallbacks':True,
        'worker':{'image':image,'modules':modules,'preparedImageId':run('docker','image','inspect','-f','{{.Id}}',image),'baselineImageId':worker['Image']}}
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2))
    (root/'artifact-verification.json').write_text(json.dumps({'allUnrelatedEntriesPreserved':True,'changedDependencyJars':changed,'jarSha256':manifest['sha256']},indent=2))
    previous=Path('/opt/chatchat-releases/studio-history-20261103-r1')
    source=(previous/'deploy.py').read_text()
    source=source.replace("config['Image'] = images[name]", "config['Image'] = images[name]\n        if name==api:\n            config['Env']=[v for v in config['Env'] if not v.startswith('CHATCHAT_SEARCH_OPENSEARCH_KNOWLEDGE')]+['CHATCHAT_SEARCH_OPENSEARCH_KNOWLEDGEINDEXNAMES=runtime_os_stage_documents,chatchat_documents_dev']")
    source=source.replace("if name==node:\n", "if name==api:\n            config['Cmd']=[v for v in (config.get('Cmd') or []) if not v.startswith('--chatchat.search.open-search.knowledge-index-names=')]+['--chatchat.search.open-search.knowledge-index-names=runtime_os_stage_documents,chatchat_documents_dev']\n        if name==node:\n",1)
    source=source.replace("if manifest.get('sqlMetadataCallbacks'):","if manifest.get('sqlMetadataCallbacks') or manifest.get('knowledgeCallbacks'):")
    source=source.replace("config=configs['config/node-proxy.conf'].decode()","config=configs['config/node-proxy.conf'].decode()\n            if manifest.get('knowledgeCallbacks') and '|knowledge|' not in config: config=config.replace('|submit-report|','|submit-report|knowledge|',1)")
    source=source.replace("config['services']['api']['image'] = manifest['image']","config['services']['api']['image'] = manifest['image']\n        config['services']['api'].setdefault('environment',{}).pop('CHATCHAT_SEARCH_OPENSEARCH_KNOWLEDGE_INDEX_NAMES',None)\n        config['services']['api']['environment']['CHATCHAT_SEARCH_OPENSEARCH_KNOWLEDGEINDEXNAMES']='runtime_os_stage_documents,chatchat_documents_dev'")
    source=source.replace("replacements={'API_IMAGE':manifest['image']}","config['services']['api']['command']=[v for v in (before[api]['Config'].get('Cmd') or []) if not v.startswith('--chatchat.search.open-search.knowledge-index-names=')]+['--chatchat.search.open-search.knowledge-index-names=runtime_os_stage_documents,chatchat_documents_dev']\n        (stage / 'compose.yaml').write_text(json.dumps(config, indent=2))\n        replacements={'API_IMAGE':manifest['image']}")
    compile(source,str(root/'deploy.py'),'exec')
    assert "config['Cmd']" in source and '--chatchat.search.open-search.knowledge-index-names=' in source
    (root/'deploy.py').write_text(source)
    shutil.copy2(previous/'restrict-coding-worker.sh',root/'restrict-coding-worker.sh')
    print(json.dumps({'status':'PREPARED','api':manifest['image'],'worker':image,'unrelatedEntriesPreserved':True,'workerTests':'PASSED'}))

if __name__=='__main__':main()
