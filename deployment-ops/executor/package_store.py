"""Signed, bounded, resumable offline package storage. Python standard library only."""
import base64, hashlib, hmac, io, json, os, re, shutil, tarfile, threading, uuid, zipfile
from pathlib import Path, PurePosixPath

NAME = re.compile(r'[a-z][a-z0-9-]{0,39}')
DIGEST = re.compile(r'[a-f0-9]{64}')
IMAGE = re.compile(r'sha256:[a-f0-9]{64}')
MAX_CHUNK = 4 * 1024**2

def require(ok, message):
    if not ok: raise ValueError(message)

def atomic(path, value):
    if os.getenv('OPS_NATIVE')=='1':
        from native_storage import validate
        validate()
    path.parent.mkdir(parents=True, exist_ok=True)
    temp=path.with_name(path.name+'.tmp')
    with open(temp,'w',encoding='utf-8') as stream:
        os.chmod(temp,0o600); json.dump(value,stream,ensure_ascii=True); stream.flush(); os.fsync(stream.fileno())
    os.replace(temp,path)

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def safe_name(name):
    require(isinstance(name,str) and NAME.fullmatch(name), 'Use a lowercase name (letters, numbers, hyphens; max 40).')
    return name

def member_name(name):
    require(isinstance(name,str) and re.fullmatch(r'[A-Za-z0-9_./-]{1,240}',name), 'Invalid archive member name')
    parts=PurePosixPath(name)
    require(not parts.is_absolute() and all(p not in ('..','.') for p in parts.parts) and str(parts)==name, 'Unsafe archive path')
    return name

def verify_signature(data, signature, key):
    n,e=int(key['n'],16),int(key['e'],16); size=(n.bit_length()+7)//8
    require(n.bit_length()>=2048 and e>=65537, 'Signing key is too weak')
    signature=base64.b64decode(signature,validate=True)
    require(len(signature)==size and int.from_bytes(signature,'big')<n, 'Invalid signature length')
    prefix=bytes.fromhex('3031300d060960864801650304020105000420')
    tail=prefix+hashlib.sha256(data).digest()
    expected=b'\x00\x01'+b'\xff'*(size-len(tail)-3)+b'\x00'+tail
    actual=pow(int.from_bytes(signature,'big'),e,n).to_bytes(size,'big')
    require(hmac.compare_digest(actual,expected), 'Package signature is invalid')

def authorize_manifest(manifest,key):
    if key.get('allowedPackages') is not None:require(manifest.get('name') in key['allowedPackages'],'Signer is not authorized for this application')
    if manifest.get('runtimeInstaller'):require('runtime-os-native/v1' in key.get('capabilities',[]),'Signer is not authorized for privileged native Runtime OS deployment')

class PackageStore:
    def __init__(self,root,trust,engine,raw_load):
        self.root=Path(root); self.trust=Path(trust); self.engine=engine; self.raw_load=raw_load
        self.root.mkdir(parents=True,exist_ok=True); (self.root/'uploads').mkdir(exist_ok=True)
        self.lock=threading.RLock(); self.importing=set(); self.verified_cache={}; self.reserve=int(os.getenv('OPS_DISK_RESERVE_BYTES',str(1024**3)))
        for file in (self.root/'uploads').glob('*.json'):
            meta=read(file)
            if meta.get('status')=='verifying':
                meta['status']='uploading';meta['message']='Verification interrupted; recommit the completed upload to resume safely';atomic(file,meta)
    def free(self,need):
        require(shutil.disk_usage(self.root).free >= need+self.reserve, 'Insufficient staging disk space; existing images/volumes will not be cleaned automatically')
    def upload_file(self,ident):
        require(re.fullmatch(r'[a-f0-9]{32}',ident or ''), 'Invalid upload ID')
        return self.root/'uploads'/ident
    def create_upload(self,spec):
        require(set(spec)<= {'name','size','sha256'}, 'Unknown upload fields')
        size=spec.get('size'); require(type(size) is int and 0<size<=int(os.getenv('OPS_MAX_PACKAGE_BYTES',str(16*1024**3))), 'Invalid package size')
        require(DIGEST.fullmatch(spec.get('sha256','')), 'Offline SHA-256 checksum required')
        with self.lock:
            self.free(size);ident=uuid.uuid4().hex;file=self.upload_file(ident)
            atomic(file.with_suffix('.json'),{'id':ident,'name':str(spec.get('name','package.zip'))[:200],'size':size,'sha256':spec['sha256'],'status':'uploading'})
            file.touch(mode=0o600);return self.upload_status(ident)
    def upload_status(self,ident):
        file=self.upload_file(ident);meta=read(file.with_suffix('.json'));meta['offset']=file.stat().st_size if file.exists() else meta['size'];return meta
    def chunk(self,ident,offset,body):
        with self.lock:
            file=self.upload_file(ident);meta=read(file.with_suffix('.json'))
            require(meta['status']=='uploading','Upload is already committed')
            require(file.stat().st_size==offset,'Offset mismatch; query the upload and resume from the reported offset')
            require(0<len(body)<=MAX_CHUNK and offset+len(body)<=meta['size'],'Invalid upload chunk')
            self.free(len(body))
            with open(file,'ab') as stream:stream.write(body);stream.flush();os.fsync(stream.fileno())
            return self.upload_status(ident)
    def catalog(self):
        result=[]
        for folder in self.root.glob('[a-f0-9]'*64):
            if folder.is_dir() and (folder/'receipt.json').exists() and not (folder/'retired.json').exists():
                manifest=read(folder/'manifest.json');result.append({'digest':folder.name,'name':manifest['name'],'version':manifest['version'],'description':manifest.get('description',''),'parameters':manifest.get('parameters',{}),'components':len(manifest['components']),'platform':manifest['platform'],'requirements':manifest.get('requirements',{}),'keyId':read(folder/'receipt.json')['keyId']})
        return result
    def package(self,digest):
        require(DIGEST.fullmatch(digest or ''),'Invalid package digest')
        folder=self.root/digest;require((folder/'receipt.json').is_file(),'Package is not verified')
        receipt=read(folder/'receipt.json');manifest=read(folder/'manifest.json')
        checksum_bytes=(folder/'checksums.json').read_bytes();signature=read(folder/'signature.json');require(signature['keyId']==receipt['keyId'],'Signer changed');key=read(self.trust/(safe_name(receipt['keyId'])+'.json'));verify_signature(checksum_bytes,signature['signature'],key);authorize_manifest(manifest,key)
        require(json.loads(checksum_bytes)==receipt['checksums'],'Package inventory changed')
        vector=tuple((name,(folder/member_name(name)).stat().st_size,(folder/name).stat().st_mtime_ns) for name in sorted(receipt['checksums']))
        if self.verified_cache.get(digest)!=vector:
            for name,expected in receipt['checksums'].items():
                file=folder/member_name(name)
                with file.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
                require(actual==expected,'Stored package changed; re-import the signed release')
            self.verified_cache[digest]=vector
        return folder,manifest
    def retire(self,digest,actor):
        folder,manifest=self.package(digest);atomic(folder/'retired.json',{'actor':actor,'retired':True});return {'status':'retired','digest':digest}
    def begin_commit(self,ident,engine_lock):
        with self.lock:
            require(ident not in self.importing,'This package is already being imported')
            meta=self.upload_status(ident)
            if meta['status']=='verified':return meta
            require(meta['offset']==meta['size'],'Upload is incomplete')
            self.importing.add(ident);meta['status']='verifying';atomic(self.upload_file(ident).with_suffix('.json'),meta)
        def work():
            try:
                with engine_lock:self.commit(ident)
            except Exception as error:
                meta=self.upload_status(ident);meta['status']='failed';meta['message']=str(error);atomic(self.upload_file(ident).with_suffix('.json'),meta)
            finally:
                with self.lock:self.importing.discard(ident)
        threading.Thread(target=work,daemon=True).start();return meta
    def commit(self,ident):
        with self.lock:
            file=self.upload_file(ident);meta=read(file.with_suffix('.json'))
            if meta['status']=='verified':return {'digest':meta['sha256'],'status':'verified'}
            require(file.stat().st_size==meta['size'],'Upload is incomplete')
            with file.open('rb') as stream:require(hashlib.file_digest(stream,'sha256').hexdigest()==meta['sha256'],'Package TAR/ZIP checksum mismatch')
            destination=self.root/meta['sha256']
            if (destination/'receipt.json').exists():meta['status']='verified';atomic(file.with_suffix('.json'),meta);return {'digest':meta['sha256'],'status':'verified'}
            with zipfile.ZipFile(file) as archive:
                infos=archive.infolist();names=[x.filename for x in infos]
                require(not {'receipt.json','retired.json'}.intersection(names),'Reserved package filename');require(len(infos)<=4096 and len(set(names))==len(names),'Duplicate or excessive archive members')
                require(all(not x.is_dir() and ((x.external_attr>>16)&0o170000)!=0o120000 for x in infos),'Directories/symlinks are not accepted')
                for name in names:member_name(name)
                for name in ['manifest.json','checksums.json','signature.json']:require(name in names,'Missing '+name);require(archive.getinfo(name).file_size<=2*1024**2,'Oversized metadata')
                checksum_bytes=archive.read('checksums.json');checksums=json.loads(checksum_bytes);signature=json.loads(archive.read('signature.json'))
                key_id=safe_name(signature['keyId']);key=read(self.trust/(key_id+'.json'));verify_signature(checksum_bytes,signature['signature'],key)
                require(set(checksums)==set(names)-{'checksums.json','signature.json'},'Signature inventory must cover every payload file')
                require(all(DIGEST.fullmatch(v) for v in checksums.values()),'Invalid checksum inventory')
                self.free(sum(x.file_size for x in infos))
                # Extract only fixed validated relative names; never extractall or follow symlinks.
                destination.mkdir(mode=0o700,exist_ok=True)
                for info in infos:
                    out=destination/info.filename;out.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
                    with archive.open(info) as source,open(out,'wb') as target:
                        os.chmod(out,0o600);digest=hashlib.sha256();count=0
                        for block in iter(lambda:source.read(1024**2),b''):
                            count+=len(block);require(count<=info.file_size,'Archive exceeds declared size');target.write(block);digest.update(block)
                    require(count==info.file_size,'Truncated archive member')
                    if info.filename in checksums:require(digest.hexdigest()==checksums[info.filename],'Payload checksum mismatch: '+info.filename)
                    if info.filename.startswith('images/') and info.filename in checksums:
                        blobs=self.root/'blobs';blobs.mkdir(exist_ok=True);blob=blobs/checksums[info.filename]
                        if blob.exists():
                            with blob.open('rb') as existing:require(hashlib.file_digest(existing,'sha256').hexdigest()==checksums[info.filename],'Image staging cache is corrupt')
                            out.unlink();os.link(blob,out)
                        else:os.link(out,blob)
            manifest=read(destination/'manifest.json');validate_manifest(manifest,checksums);authorize_manifest(manifest,key)
            # Signed Docker-save images only. Refuse to replace any existing tag with another image.
            missing=[]
            for image in manifest['images'].values():
                try:current=self.engine('GET','/images/'+image['id']+'/json')
                except ValueError:missing.append(image);continue
                require(current['Id']==image['id'],'Image identity mismatch');require(current.get('Os','linux')+'/'+current['Architecture']==manifest['platform'],'Image architecture differs from signed package')
            self.free(sum(i.get('size',0) for i in missing))
            groups={}
            for image in manifest['images'].values():groups.setdefault(image['archive'],[]).append(image)
            for archive,images in groups.items():
                image_file=destination/archive;needs_load=any(image in missing for image in images)
                with tarfile.open(image_file,'r:') as tar:
                    inner=tar.getmember('manifest.json');require(inner.size<4*1024**2,'Oversized Docker image manifest');image_ids=set()
                    for entry in json.load(tar.extractfile(inner)):
                        config=tar.getmember(entry['Config']);require(config.isfile() and config.size<16*1024**2,'Invalid image config')
                        config_id='sha256:'+hashlib.sha256(tar.extractfile(config).read()).hexdigest();image_ids.add(config_id)
                        for tag in entry.get('RepoTags') or []:
                            require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/:@-]{0,250}',tag),'Invalid image tag')
                            try:existing=self.engine('GET','/images/'+tag+'/json')
                            except ValueError:continue
                            require(not needs_load or existing['Id']==config_id,'Import would overwrite an existing image tag: '+tag)
                    require(all(image['id'] in image_ids for image in images),'Signed image ID is not present in its archive')
                if needs_load:self.raw_load(image_file)
                for image in images:
                    loaded=self.engine('GET','/images/'+image['id']+'/json');require(loaded['Id']==image['id'],'Loaded image ID differs from signed release');require(loaded.get('Os','linux')+'/'+loaded['Architecture']==manifest['platform'],'Loaded image platform differs from package')
            atomic(destination/'receipt.json',{'keyId':key_id,'checksums':checksums})
            meta['status']='verified';atomic(file.with_suffix('.json'),meta)
            # This is the completed temporary upload only, not an image/volume/history cleanup.
            file.unlink();return {'digest':meta['sha256'],'status':'verified','name':manifest['name'],'version':manifest['version']}

def validate_manifest(m,files):
    require(m.get('schemaVersion')==1,'Unsupported package schema')
    require(set(m)<= {'schemaVersion','name','version','description','platform','requirements','parameters','images','volumes','components','upgradeFrom','dataCompatibility','links','runtimeInstaller'},'Unknown package fields')
    safe_name(m.get('name'));require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}',m.get('version','')),'Invalid version')
    require(re.fullmatch(r'linux/(amd64|arm64|arm)',m.get('platform','')),'Unsupported platform')
    require(isinstance(m.get('images'),dict) and 0<len(m['images'])<=100,'Images required')
    for name,image in m['images'].items():
        safe_name(name);require(set(image)<= {'id','archive','size'},'Unknown image fields');require(IMAGE.fullmatch(image.get('id','')),'Pin every image by content ID');require(member_name(image['archive']) in files,'Image archive missing from signed inventory');require(type(image.get('size')) is int and image['size']>=0,'Image size required')
    params=m.get('parameters',{});require(isinstance(params,dict) and len(params)<=100,'Invalid parameter schema')
    if 'runtimeInstaller' in m:
        adapter=m['runtimeInstaller'];require(m['name']=='runtime-os' and isinstance(adapter,dict),'Native Runtime OS adapter requires a Runtime OS release')
        require(set(adapter)<= {'protocol','image','imageMap','services','schemaHashes','sourceCommit'},'Unknown Runtime OS adapter field')
        require(adapter.get('protocol')=='runtime-os-native/v1' and adapter.get('image') in m['images'],'Pinned native Runtime OS installer required')
        require(member_name(adapter.get('imageMap')) in files,'Signed Runtime OS image map required')
        require(isinstance(adapter.get('services'),list) and 0<len(adapter['services'])<=64,'Runtime OS service inventory required')
        for name in adapter['services']:safe_name(name)
        require(isinstance(adapter.get('schemaHashes'),dict) and bool(adapter['schemaHashes']) and all(isinstance(value,str) and DIGEST.fullmatch(value) for value in adapter['schemaHashes'].values()),'Runtime OS schema hashes required')
    for name,p in params.items():
        require(re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}',name),'Invalid parameter name')
        require(set(p)<= {'type','label','description','default','required','secret','generate','enum','pattern','min','max'},'Unknown parameter fields')
        require(p.get('type','string') in ('string','integer','boolean'),'Invalid parameter type')
        require(not (p.get('secret') and 'default' in p),'Secret defaults are not allowed in release packages')
        require('pattern' not in p or len(p['pattern'])<=200,'Parameter pattern too long')
        if 'pattern' in p:re.compile(p['pattern'])
    for name,v in m.get('volumes',{}).items():safe_name(name);require(set(v)<= {'description'},'Only owned named volumes are supported')
    components=m.get('components');require(isinstance(components,list) and 0<len(components)<=60,'Components required')
    ids=set()
    for c in components:
        name=safe_name(c.get('id'));require(name not in ids,'Duplicate component');ids.add(name)
        require(set(c)<= {'id','image','kind','dependsOn','environment','entrypoint','command','mounts','files','ports','health','memoryBytes','cpus','when','retrySafe','runOn','dataChange','user','capabilities','networkFrom','schemaSensitive'},'Unknown or privileged component field')
        require(c.get('kind','service') in ('service','job','external'),'Invalid component kind')
        if c.get('kind')!='external':require(c.get('image') in m['images'],'Component image is not in package')
        require(isinstance(c.get('dependsOn',[]),list),'Dependencies must be a list')
        for field in ['command','entrypoint']:
            if field in c:require(isinstance(c[field],list) and all(isinstance(x,str) and len(x)<=65536 for x in c[field]),'Container argv must be a string array')
        for mount in c.get('mounts',[]):
            require(set(mount)<= {'volume','target','readOnly'},'Host bind mounts are forbidden');require(mount.get('volume') in m.get('volumes',{}),'Unknown named volume');container_path(mount['target'])
        for asset in c.get('files',[]):
            require(set(asset)<= {'source','target','template','mode','uid','gid'},'Unknown file fields');require(member_name(asset['source']) in files,'Unsigned/missing config asset');container_path(asset['target'])
            require(int(asset.get('mode','0644'),8)&~0o777==0,'Invalid asset permissions')
        require(set(c.get('capabilities',[]))<= {'CHOWN','FOWNER','SETUID','SETGID','DAC_OVERRIDE','NET_BIND_SERVICE','IPC_LOCK'},'Unsafe container capability')
        if 'networkFrom' in c:require(c['networkFrom'] in c.get('dependsOn',[]),'Shared network namespace must name an owned dependency')
        if 'user' in c:require(re.fullmatch(r'[0-9]{1,6}(:[0-9]{1,6})?',c['user']),'Numeric container UID required')
        if c.get('kind','service')=='service':require(c.get('health',{}).get('type') in ('http','tcp','docker'),'Every service needs an explicit health gate')
        if c.get('kind')=='job':require(c.get('runOn','install') in ('install','always'),'Invalid job lifecycle')
    for c in components:require(set(c.get('dependsOn',[]))<=ids,'Unknown component dependency')
    pending={c['id']:set(c.get('dependsOn',[])) for c in components};done=[]
    while pending:
        ready=[k for k,v in pending.items() if v<=set(done)];require(ready,'Dependency cycle')
        for name in ready:done.append(name);pending.pop(name)
    for link in m.get('links',[]):
        require(set(link)<= {'name','url'},'Unknown link fields')
        require(not any('${'+name+'}' in link.get('url','') for name,p in params.items() if p.get('secret')),'Application links must not contain secret parameters')
    require(m.get('dataCompatibility','initial-only') in ('initial-only','backward-compatible'),'Invalid data compatibility policy')
    return done

def container_path(value):
    require(isinstance(value,str) and value.startswith('/') and '..' not in PurePosixPath(value).parts and value!='/' and len(value)<=240,'Invalid container path')
    require(not any(value==p or value.startswith(p+'/') for p in ['/proc','/sys','/dev','/var/run','/run/docker.sock']),'Sensitive device/socket path is forbidden')
    return value
