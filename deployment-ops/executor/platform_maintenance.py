"""Allowlisted native platform maintenance. Never invokes Docker or business services."""
import argparse,hashlib,json,os,re,shlex,shutil,stat,subprocess,sys,time,uuid,zipfile
from pathlib import Path,PurePosixPath
from package_store import atomic,read,require

def below(path,root):
    resolved=Path(path).resolve();require(Path(root).resolve() in resolved.parents,'Maintenance path escapes allowed storage');return resolved

def checksum(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda:source.read(1024**2),b''):digest.update(chunk)
    return digest.hexdigest()

def checked_archive(path):
    require(not path.is_symlink() and path.is_file(),'Archive missing or symlinked')
    expected=path.with_name(path.name+'.sha256').read_text().split()[0]
    require(re.fullmatch('[a-f0-9]{64}',expected) and checksum(path)==expected,'Archive SHA-256 mismatch')

def submit(spec,actor):
    require(os.getenv('OPS_NATIVE')=='1' and actor,'Native authenticated maintenance required')
    action=spec.get('action');name=spec.get('name','')
    require(action in ('restore','upgrade'),'Unsupported maintenance action')
    require(re.fullmatch(('backup-' if action=='restore' else 'upgrade-')+r'[a-f0-9-]{36}\.zip',name),'Invalid archive name')
    require(spec.get('confirmation')==action.upper()+':'+name,'Exact maintenance confirmation required')
    state=Path(os.environ['OPS_DATA_DIR']).resolve().parent
    require(Path('/mnt/disk01').is_mount(),'Required data mount is unavailable')
    source=below(state/('backups' if action=='restore' else 'upgrades')/name,state);checked_archive(source)
    service=os.getenv('OPS_PLATFORM_SERVICE','');config=Path(os.getenv('OPS_CONFIG_FILE',''))
    require(re.fullmatch(r'deployment-ops(?:-[a-z0-9-]+)?',service or ''),'Platform service is not configured')
    require(config.is_file() and config.resolve()==state/'manager.env','Unexpected platform configuration path')
    work=state/'maintenance';work.mkdir(mode=0o700,exist_ok=True)
    lock=work/'active.lock'
    try:descriptor=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    except FileExistsError:raise ValueError('Maintenance already active; inspect retained journal before recovery')
    ident=uuid.uuid4().hex;os.write(descriptor,ident.encode());os.close(descriptor)
    job={'id':ident,'action':action,'archive':str(source),'actor':actor,'service':service,'config':str(config),'state':str(state),'status':'queued','createdAt':time.time()}
    file=work/(ident+'.json');atomic(file,job)
    try:
        subprocess.run(['systemd-run','--unit=deployment-ops-maintenance-'+ident,sys.executable,str(Path(__file__).resolve()),'--job',str(file)],check=True,capture_output=True,text=True)
    except Exception:
        lock.unlink();raise
    return {'id':ident,'status':'queued','journal':str(file)}

def environment(config):
    values={}
    for line in config.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):continue
        key,separator,value=line.partition('=');require(separator and re.fullmatch('[A-Z][A-Z0-9_]*',key),'Invalid configuration entry')
        tokens=shlex.split(value);require(len(tokens)==1,'Invalid configuration value');values[key]=tokens[0]
    return values

def systemctl(action,service):
    require(action in ('start','stop','restart'),'Unsupported service operation')
    require(re.fullmatch(r'deployment-ops(?:-[a-z0-9-]+)?(?:-executor)?',service),'Invalid maintenance service')
    subprocess.run(['systemctl',action,service],check=True,timeout=90)

def wait_healthy(values):
    import urllib.request
    op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(60):
        try:
            with op.open('http://127.0.0.1:'+values.get('OPS_PORT','19240')+'/api/health',timeout=2) as response:
                if json.load(response).get('status')=='UP':return
        except Exception:pass
        time.sleep(1)
    raise ValueError('Platform health check failed after maintenance')

def restore(job,values,work):
    archive=Path(job['archive']);checked_archive(archive)
    with zipfile.ZipFile(archive) as package:
        require(set(package.namelist())=={'manifest.json','database.zip'},'Unsupported backup contents')
        require(package.getinfo('manifest.json').file_size<=65536 and package.getinfo('database.zip').file_size<=8*1024**3,'Backup exceeds limits')
        manifest=json.loads(package.read('manifest.json'));require(manifest['format']=='deployment-ops-h2/v1' and manifest['schemaVersion']<=2,'Incompatible backup')
        database=work/'database.zip'
        with package.open('database.zip') as source,database.open('xb') as output:shutil.copyfileobj(source,output,1024**2)
    require(checksum(database)==manifest['databaseSha256'],'Backup database checksum mismatch')
    verified=work/'verified';verified.mkdir()
    with zipfile.ZipFile(database) as package:
        require(package.namelist()==['deployment-ops.mv.db'],'Backup must contain one embedded database')
        require(package.getinfo('deployment-ops.mv.db').file_size<=16*1024**3,'Database exceeds restore limit')
        with package.open('deployment-ops.mv.db') as source,(verified/'deployment-ops.mv.db').open('xb') as output:shutil.copyfileobj(source,output,1024**2)
    runtime_env={**os.environ,**values}
    subprocess.run([values['JAVA_BIN'],'-jar',str(Path(values['OPS_HOME'])/'lib/deployment-ops.jar'),'--prepare-restore',str(verified)],check=True,env=runtime_env,timeout=120,capture_output=True,text=True)
    data=below(Path(values['OPS_DATA_DIR'])/'deployment-ops.mv.db',job['state']);saved=work/'before-restore.mv.db'
    systemctl('stop',job['service'])
    try:
        shutil.copy2(data,saved);identity=data.stat();replacement=verified/'deployment-ops.mv.db'
        os.chown(replacement,identity.st_uid,identity.st_gid);os.chmod(replacement,stat.S_IMODE(identity.st_mode));os.replace(replacement,data)
        systemctl('start',job['service']);wait_healthy(values)
    except Exception:
        systemctl('stop',job['service'])
        if saved.exists():shutil.copy2(saved,data)
        systemctl('start',job['service']);raise

def extract_release(archive,target):
    with zipfile.ZipFile(archive) as package:
        entries=package.infolist();require(len(entries)<20000,'Too many release files')
        total=0;seen=set()
        for entry in entries:
            path=PurePosixPath(entry.filename)
            require(entry.filename not in seen and not path.is_absolute() and '..' not in path.parts and '\\' not in entry.filename and chr(92) not in entry.filename and ':' not in entry.filename,'Unsafe release path')
            seen.add(entry.filename);require(not stat.S_ISLNK(entry.external_attr>>16),'Release symlinks are forbidden');total+=entry.file_size
            require(total<=2*1024**3,'Release exceeds extraction limit')
        require('release.json' in seen,'Release manifest is missing')
        metadata=json.loads(package.read('release.json'));require(metadata.get('format')=='deployment-ops-native/v1','Unsupported release format')
        require(metadata.get('schemaVersion')==2,'Review schema migration compatibility before upgrade')
        for entry in entries:
            path=target/entry.filename
            if entry.is_dir():path.mkdir(parents=True,exist_ok=True);continue
            path.parent.mkdir(parents=True,exist_ok=True)
            with package.open(entry) as source,path.open('xb') as output:shutil.copyfileobj(source,output,1024**2)
            os.chmod(path,0o644)
        for name,expected in metadata['files'].items():
            require(name in seen and re.fullmatch('[a-f0-9]{64}',expected) and checksum(target/name)==expected,'Release file checksum mismatch: '+name)
        require(set(metadata['files'])==seen-{'release.json'}-set(e.filename for e in entries if e.is_dir()),'Release inventory mismatch')
        require((target/'lib/deployment-ops.jar').is_file() and (target/'executor/agent.py').is_file(),'Incomplete native release')

def upgrade(job,values,work):
    archive=Path(job['archive']);checked_archive(archive)
    target=Path(job['state'])/'releases'/job['id'];target.mkdir(parents=True)
    extract_release(archive,target)
    # Validate candidate against an isolated copy before replacing either process.
    systemctl('stop',job['service']);data=Path(values['OPS_DATA_DIR'])/'deployment-ops.mv.db';snapshot=work/'before-upgrade.mv.db';shutil.copy2(data,snapshot)
    config=Path(job['config']);old=config.read_bytes();(work/'manager.env.before').write_bytes(old);executor=job['service']+'-executor'
    try:
        systemctl('stop',executor)
        updated=dict(values,OPS_HOME=str(target),OPS_JAR=str(target/'lib/deployment-ops.jar'))
        text=''.join(k+'="'+str(v).replace('\\','\\\\').replace('"','\\"')+'"\n' for k,v in updated.items())
        config.write_text(text);systemctl('start',executor);systemctl('start',job['service']);wait_healthy(updated)
    except Exception:
        systemctl('stop',job['service']);systemctl('stop',executor);config.write_bytes(old);shutil.copy2(snapshot,data);systemctl('start',executor);systemctl('start',job['service']);raise

def execute(file):
    job=read(file);state=Path(job['state']);require(Path('/mnt/disk01').is_mount() and Path('/mnt/disk01') in state.resolve().parents,'Required storage unavailable')
    work=state/'maintenance'/job['id'];work.mkdir(mode=0o700)
    try:
        job.update(status='running',startedAt=time.time());atomic(file,job)
        values=environment(Path(job['config']))
        require(values['OPS_PLATFORM_SERVICE']==job['service'] and Path(values['OPS_DATA_DIR']).resolve().parent==state.resolve(),'Maintenance scope mismatch')
        (restore if job['action']=='restore' else upgrade)(job,values,work)
        job.update(status='succeeded',finishedAt=time.time());atomic(file,job)
    except Exception as error:
        job.update(status='failed',error=type(error).__name__+': '+str(error),finishedAt=time.time());atomic(file,job)
    finally:
        lock=state/'maintenance/active.lock'
        if lock.exists() and lock.read_text()==job['id']:lock.unlink()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--job',type=Path,required=True);args=parser.parse_args();execute(args.job)
