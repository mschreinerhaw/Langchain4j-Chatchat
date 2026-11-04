"""Extend the existing reviewed knowledge release with MCP/news class overlays.

No new service or data store is created. Existing deployment guards, container cloning,
backups, network boundaries and rollback remain the deployment owner.
"""
import hashlib,importlib.util,io,json,os,subprocess,sys,zipfile
from pathlib import Path

def run(*args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT).strip()
def sha(data):return hashlib.sha256(data).hexdigest()
def replaced(name,prefixes):return any(name==prefix+'.class' or name.startswith(prefix+'$') for prefix in prefixes)

def verify_reused_service(root,key,module,dependencies):
    """API-only releases must prove that every proposed engine overlay is already deployed."""
    container='runtime-os-app-stage-'+key+'-1';current=json.loads(run('docker','inspect',container))[0]
    release=Path('/opt/chatchat-releases')/current['Config']['Image'].rsplit(':',1)[-1]
    assert release.parent==Path('/opt/chatchat-releases') and release.name.startswith('knowledge-exploration-')
    artifact=release/(key+'.jar');assert artifact.is_file()
    digest=sha(artifact.read_bytes());assert run('docker','exec',container,'sha256sum','/srv/app/app.jar').split()[0]==digest
    groups=json.loads((root/'prefixes.json').read_text())
    def verify_classes(source,group,prefix=''):
        actual={name.removeprefix(prefix):source.read(name) for name in source.namelist() if name.startswith(prefix) and replaced(name.removeprefix(prefix),groups[group])}
        expected={path.relative_to(root/'patch'/group).as_posix():path.read_bytes() for path in (root/'patch'/group).rglob('*.class')}
        assert actual==expected,'API-only release contains changed '+group+' engine classes'
    with zipfile.ZipFile(artifact) as source:
        verify_classes(source,module,'BOOT-INF/classes/')
        for path in (root/'patch'/module).rglob('*'):
            if path.is_file() and path.suffix!='.class':assert source.read('BOOT-INF/classes/'+path.relative_to(root/'patch'/module).as_posix())==path.read_bytes()
        for group in dependencies:
            name=next(name for name in source.namelist() if name.startswith('BOOT-INF/lib/'+group+'-') and name.endswith('.jar'))
            with zipfile.ZipFile(io.BytesIO(source.read(name))) as library:verify_classes(library,group)
    return {'image':current['Config']['Image'],'imageId':current['Image'],'sha256':digest,'proposedEngineOverlayAlreadyDeployed':True}

def patch_service(root,key,module,dependencies):
    container='runtime-os-app-stage-'+key+'-1';before=json.loads(run('docker','inspect',container))[0]
    baseline=root/(key+'-before.jar');run('docker','cp',container+':/srv/app/app.jar',str(baseline))
    groups=json.loads((root/'prefixes.json').read_text());changed=[];financial_store_changed=False
    def library(data,group):
        nonlocal financial_store_changed
        result=io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(data)) as source,zipfile.ZipFile(result,'w',zipfile.ZIP_DEFLATED) as target:
            if group=='chatchat-runtime-market':
                family=['com/chatchat/runtime/market/storage/FinancialDataStore']
                previous={name:source.read(name) for name in source.namelist() if replaced(name,family)}
                incoming={path.relative_to(root/'patch'/group).as_posix():path.read_bytes() for path in (root/'patch'/group).rglob('*')
                    if path.is_file() and replaced(path.relative_to(root/'patch'/group).as_posix(),family)}
                financial_store_changed=bool(incoming) and previous!=incoming
            for item in source.infolist():
                if not replaced(item.filename,groups[group]):target.writestr(item,source.read(item.filename))
            for path in (root/'patch'/group).rglob('*'):
                if path.is_file():target.write(path,path.relative_to(root/'patch'/group).as_posix())
        return result.getvalue()
    artifact=root/(key+'.jar')
    resources={path.relative_to(root/'patch'/module).as_posix():path.read_bytes()
        for path in (root/'patch'/module).rglob('*') if path.is_file() and path.suffix!='.class'}
    with zipfile.ZipFile(baseline) as source,zipfile.ZipFile(artifact,'w') as target:
        if key=='mcp':
            # Extend the existing licensed news/financial-query module; never bypass its gate.
            old=json.loads(source.read('BOOT-INF/classes/mcp-admin-menus.json'))
            new=json.loads(resources['mcp-admin-menus.json'])
            assert next(row for row in new if row['key']=='newsCollection')['apiPrefixes'].count('/api/v1/resource-exploration')==1
            for rows in (old,new):
                for row in rows:
                    if row['key']=='newsCollection':
                        row['apiPrefixes']=[p for p in row['apiPrefixes'] if p!='/api/v1/resource-exploration']
            assert old==new,'Unrelated License catalog changes require a separate review'
        for item in source.infolist():
            name=item.filename
            if name.startswith('BOOT-INF/classes/') and name.removeprefix('BOOT-INF/classes/') in resources:continue
            if name.startswith('BOOT-INF/classes/') and replaced(name.removeprefix('BOOT-INF/classes/'),groups[module]):continue
            dependency=next((group for group in dependencies if name.startswith('BOOT-INF/lib/'+group+'-') and name.endswith('.jar')),None)
            if dependency:target.writestr(item,library(source.read(name),dependency));changed.append(name)
            else:target.writestr(item,source.read(name))
        for path in (root/'patch'/module).rglob('*'):
            if path.is_file():target.write(path,'BOOT-INF/classes/'+path.relative_to(root/'patch'/module).as_posix())
    assert len(changed)==len(dependencies),'Missing service dependency'
    with zipfile.ZipFile(baseline) as source,zipfile.ZipFile(artifact) as target:
        baseline_readiness='BOOT-INF/classes/com/chatchat/mcpserver/health/McpReadinessController.class' in source.namelist()
        assert target.testzip() is None
        assert all(source.read(n)==target.read(n) for n in source.namelist() if n not in changed
            and not(n.startswith('BOOT-INF/classes/') and n.removeprefix('BOOT-INF/classes/') in resources)
            and not(n.startswith('BOOT-INF/classes/') and replaced(n.removeprefix('BOOT-INF/classes/'),groups[module])))
    artifact.chmod(0o644);build=root/(key+'-prepared-build');build.mkdir(exist_ok=True)
    import shutil
    os.link(artifact,build/'app.jar')
    baseline_image='runtime-os-'+key+':resource-baseline-'+before['Image'].split(':')[-1][:16]
    run('docker','tag',before['Image'],baseline_image)
    (build/'Dockerfile').write_text('FROM '+baseline_image+'\nCOPY app.jar /srv/app/app.jar\n')
    image='runtime-os-'+key+':'+root.name;run('docker','build','--network','none','-t',image,str(build))
    return {'image':image,'baselineImageId':before['Image'],'baselineApplicationReadiness':baseline_readiness,'financialDataStoreChanged':financial_store_changed,'preparedImageId':run('docker','image','inspect','-f','{{.Id}}',image),
        'baselineSha256':sha(baseline.read_bytes()),'sha256':sha(artifact.read_bytes()),'allUnrelatedEntriesPreserved':True,'changedDependencyJars':changed,
        'overlaidResources':{name:sha(data) for name,data in resources.items()}}

def main():
    os.umask(0o077)
    spec=importlib.util.spec_from_file_location('knowledge_prepare',Path(__file__).with_name('prepare-knowledge-exploration-stage.py'))
    base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base);base.main()
    root=Path(sys.argv[1]).resolve();manifest=json.loads((root/'manifest.json').read_text())
    if '--api-only' in sys.argv[3:]:
        manifest['reusedServices']={'mcp':verify_reused_service(root,'mcp','chatchat-mcp-server',['chatchat-runtime-market']),
            'news':verify_reused_service(root,'news','chatchat-runtime-news',[])}
        manifest['resourceExploration']=True;manifest['financialLookupSchemaOptimization']=False
        (root/'manifest.json').write_text(json.dumps(manifest,indent=2))
        source=(root/'deploy.py').read_text()
        old="                run('docker', 'start', name)\n        def literal(value):"
        assert old in source;source=source.replace(old,"            run('docker', 'start', name)\n        def literal(value):",1)
        compile(source,str(root/'deploy.py'),'exec');(root/'deploy.py').write_text(source)
        print(json.dumps({'status':'PREPARED_API_WITH_VERIFIED_EXISTING_RESOURCE_ENGINES','sourceCommit':manifest['sourceCommit'],'reusedServices':manifest['reusedServices']}));return
    manifest['mcp']=patch_service(root,'mcp','chatchat-mcp-server',['chatchat-runtime-market'])
    manifest['news']=patch_service(root,'news','chatchat-runtime-news',[])
    manifest['resourceExploration']=True
    # This release changes the query projection, while the R11 startup already
    # completed the only required H2 column/index migration. Require retained
    # migration evidence before omitting a second offline database backup.
    migrated=False
    if '--reuse-migrated-financial-schema' in sys.argv[3:]:
        live=json.loads(run('docker','inspect','runtime-os-app-stage-mcp-1'))[0]
        previous=Path('/opt/chatchat-releases')/live['Config']['Image'].rsplit(':',1)[-1]
        report=json.loads((previous/'manifest.json').read_text())
        log=run('docker','logs','runtime-os-app-stage-mcp-1')
        migrated=(previous.name=='knowledge-exploration-20261104-resources-r11'
            and report.get('financialLookupSchemaOptimization') is True
            and (previous/'financial-storage-backup.json').is_file()
            and log.count('Financial quote lookup index ready table=market_quote_daily')>=1
            and log.count('Financial quote lookup index ready table=market_quote_daily_weekly_snapshot')>=1)
        assert migrated,'Prior financial schema migration is not proven; refusing backup exemption'
    manifest['financialLookupSchemaOptimization']=manifest['mcp']['financialDataStoreChanged'] and not migrated
    manifest['financialSchemaPreviouslyMigrated']=migrated
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2))
    source=(root/'deploy.py').read_text()
    def replace(old,new):
        nonlocal source
        assert old in source,'Deployment template changed; review '+old[:80]
        source=source.replace(old,new,1)
    replace("node, mcp = 'runtime-os-app-stage-coding-agent-1', 'runtime-os-app-stage-mcp-1'",
        "node, mcp = 'runtime-os-app-stage-coding-agent-1', 'runtime-os-app-stage-mcp-1'\n    news='runtime-os-app-stage-news-1'")
    replace("if manifest.get('mcp'): images[mcp] = manifest['mcp']['image']",
        "if manifest.get('mcp'): images[mcp] = manifest['mcp']['image']\n    if manifest.get('news'): images[news]=manifest['news']['image']")
    replace("+ [proxy]\n", "+ [proxy] + ([news] if news in images else [])\n")
    start=source.index("    if mcp in images:\n        entry=manifest['mcp']")
    end=source.index("    if node in images:\n",start)
    source=source[:start]+'''    for service,key in [(mcp,'mcp'),(news,'news')]:
        if service not in images:continue
        entry=manifest[key]
        assert before[service]['Image']==entry['baselineImageId']
        assert run('docker','exec',service,'sha256sum','/srv/app/app.jar').split()[0]==entry['baselineSha256']
        assert hashlib.sha256((root/(key+'.jar')).read_bytes()).hexdigest()==entry['sha256']
        assert run('docker','image','inspect','-f','{{.Id}}',entry['image'])==entry['preparedImageId']
'''+source[end:]
    replace("    def ready():",'''    def mcp_ready_now(client):
        if mcp not in images:return True
        try:
            current=inspect(mcp)['Image']
            if current==manifest['mcp']['preparedImageId'] or current==before[mcp]['Image'] and manifest['mcp'].get('baselineApplicationReadiness'):
                with client.open('http://127.0.0.1:18190/readyz',timeout=3) as result:
                    return result.status==200 and json.load(result).get('status')=='READY'
            # Rollback may restore an older image without the new infrastructure endpoint.
            if inspect(mcp)['Image']!=before[mcp]['Image']:return False
            return client.open('http://127.0.0.1:18190/api/v1/admin/auth/me',timeout=3).status in [200,401,403]
        except urllib.error.HTTPError as error:
            return inspect(mcp)['Image']==before[mcp]['Image'] and not manifest['mcp'].get('baselineApplicationReadiness') and error.code in [200,401,403]
        except (OSError,urllib.error.URLError,ValueError):return False

    def backup_financial_storage():
        if not manifest.get('financialLookupSchemaOptimization'):return
        database=Path('/srv/app/data/h2/financial-market.mv.db')
        candidates=[mount for mount in before[mcp]['Mounts'] if database==Path(mount['Destination']) or Path(mount['Destination']) in database.parents]
        assert candidates,'Financial writer mount must be identified before schema optimization'
        mount=max(candidates,key=lambda value:len(value['Destination']))
        owner=Path(mount['Source']).resolve()
        source=(owner/database.relative_to(mount['Destination'])).resolve()
        assert source.is_relative_to(owner) and source.is_file(),'Financial writer database backup source unavailable'
        assert shutil.disk_usage(root).free>source.stat().st_size+1024**3,'Insufficient space for financial storage backup'
        destination=root/'backup'/'financial-h2';destination.mkdir(mode=0o700)
        target=destination/database.name;shutil.copy2(source,target);target.chmod(0o600)
        with target.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
        (root/'financial-storage-backup.json').write_text(json.dumps({'database':database.name,'bytes':target.stat().st_size,'sha256':digest,'copiedAfterWriterStopped':True},indent=2))

    def wait_mcp_before_producer():
        client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline=time.monotonic()+(600 if manifest.get('financialLookupSchemaOptimization') else 240)
        while time.monotonic()<deadline:
            if mcp_ready_now(client):return
            time.sleep(2)
        raise RuntimeError('MCP application readiness timed out before news producer startup')

    def ready():''')
    start=source.index("            mcp_ready=mcp not in images\n")
    end=source.index("            if status in [200, 401, 403] and mcp_ready and",start)
    source=source[:start]+"            mcp_ready=mcp_ready_now(client)\n"+source[end:]
    replace("            retired[name] = name + '-before-studio-' + stamp", "            if name==news:wait_mcp_before_producer()\n            retired[name] = name + '-before-studio-' + stamp")
    replace("        run('docker', 'stop', '-t', '60', *reversed(order))", "        run('docker', 'stop', '-t', '60', *reversed(order))\n        backup_financial_storage()")
    replace("                run('docker', 'start', name)\n        def literal(value):", "            if name==news:wait_mcp_before_producer()\n            run('docker', 'start', name)\n        def literal(value):")
    replace("            if status in [200, 401, 403] and mcp_ready and",'''            news_ready=news not in images
            if news in images:
                try:
                    address=next(value['IPAddress'] for value in inspect(news)['NetworkSettings']['Networks'].values() if value.get('IPAddress'))
                    news_ready=client.open('http://'+address+':8091/internal/v1/news/health',timeout=3).status in [200,401,403]
                except urllib.error.HTTPError as error:news_ready=error.code in [200,401,403]
                except (OSError,urllib.error.URLError,StopIteration):pass
            if status in [200, 401, 403] and mcp_ready and news_ready and''')
    replace("if mcp in images: config['services']['mcp']['image']=images[mcp]",
        "if mcp in images: config['services']['mcp']['image']=images[mcp]\n        if news in images:config['services']['news']['image']=images[news]")
    replace("if mcp in images: replacements['MCP_IMAGE']=images[mcp]",
        "if mcp in images: replacements['MCP_IMAGE']=images[mcp]\n        if news in images:replacements['NEWS_IMAGE']=images[news]")
    replace("if mcp in images: assert run('docker','exec',mcp,'sha256sum','/srv/app/app.jar').split()[0]==manifest['mcp']['sha256']",
        "if mcp in images: assert run('docker','exec',mcp,'sha256sum','/srv/app/app.jar').split()[0]==manifest['mcp']['sha256']\n        if news in images:assert run('docker','exec',news,'sha256sum','/srv/app/app.jar').split()[0]==manifest['news']['sha256']")
    compile(source,str(root/'deploy.py'),'exec');(root/'deploy.py').write_text(source)
    (root/'service-artifact-verification.json').write_text(json.dumps({k:manifest[k] for k in ['mcp','news']},indent=2))
    print(json.dumps({'status':'PREPARED_RESOURCE_EXTENSION','images':{k:manifest[k]['image'] for k in ['mcp','news','worker']},'sourceCommit':manifest['sourceCommit']}))

if __name__=='__main__':main()
