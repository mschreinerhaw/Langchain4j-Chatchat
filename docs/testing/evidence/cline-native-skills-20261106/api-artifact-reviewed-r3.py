import pathlib,subprocess,json,hashlib,tarfile,zipfile,io
r=pathlib.Path('/opt/chatchat-releases/cline-native-skills-20261106-r3')
def run(*a):return subprocess.check_output(a,text=True,stderr=subprocess.STDOUT).strip()
def sha(raw):return hashlib.sha256(raw).hexdigest()
b=r/'api-candidate';b.mkdir(mode=0o700,exist_ok=True)
with tarfile.open(r/'classes.tar.gz') as a:
 for m in a.getmembers():
  assert not pathlib.Path(m.name).is_absolute() and '..' not in pathlib.Path(m.name).parts and not m.issym() and not m.islnk()
 a.extractall(b,filter='data')
manifest=json.loads((b/'manifest.json').read_text());direct={};nested={}
for f in manifest['files']:
 raw=(b/f['file']).read_bytes();assert sha(raw)==f['sha256']
 if f['container'].endswith('/'):direct[f['container']+f['entry']]=raw
 else:nested.setdefault(f['container'],{})[f['entry']]=raw
live=json.loads(run('docker','inspect','runtime-os-app-stage-api-1'))[0]
run('docker','cp','runtime-os-app-stage-api-1:/srv/app/app.jar',str(r/'api-baseline.jar'))
assert sha((r/'api-baseline.jar').read_bytes())=='b7cf6bc86d106525c90c72c275b8c71b7d61c4f848eb25ae0a18bd0dbcb1c8aa'
proofs={}
def overlay(raw,patches):
 out=io.BytesIO();changed=[]
 with zipfile.ZipFile(io.BytesIO(raw)) as old,zipfile.ZipFile(out,'w') as new:
  assert len(old.namelist())==len(set(old.namelist())) and set(patches)<=set(old.namelist());new.comment=old.comment
  for info in old.infolist():
   before=old.read(info.filename);after=patches.get(info.filename,before)
   if before!=after:changed.append(info.filename)
   new.writestr(info,after)
 with zipfile.ZipFile(io.BytesIO(raw)) as old,zipfile.ZipFile(io.BytesIO(out.getvalue())) as new:
  assert old.namelist()==new.namelist()
  assert all(old.read(n)==new.read(n) for n in old.namelist() if n not in changed)
 assert changed and set(changed)<=set(patches)
 return out.getvalue(),{'changedEntries':changed,'allOtherEntriesUnchanged':True,'entryCount':len(new.namelist())}
raw=(r/'api-baseline.jar').read_bytes()
with zipfile.ZipFile(io.BytesIO(raw)) as z:
 for name,patches in nested.items():
  assert z.getinfo(name).compress_type==zipfile.ZIP_STORED
  direct[name],proofs[name]=overlay(z.read(name),patches)
candidate,outer=overlay(raw,direct);(b/'app.jar').write_bytes(candidate)
digest=sha(candidate);base='runtime-os-api:native-skills-baseline-'+live['Image'].split(':')[-1][:12]
run('docker','tag',live['Image'],base);image='runtime-os-api:cline-native-skills-'+digest[:12]
(b/'Dockerfile').write_text('FROM '+base+'\nCOPY app.jar /srv/app/app.jar\n');(b/'.dockerignore').write_text('*\n!Dockerfile\n!app.jar\n')
with (r/'api-image-build.log').open('w') as log:subprocess.run(['docker','build','--network','none','-t',image,str(b)],check=True,stdout=log,stderr=subprocess.STDOUT)
assert run('docker','run','--rm','--network','none','--entrypoint','sha256sum',image,'/srv/app/app.jar').split()[0]==digest
p={'sourceCommit':manifest['sourceCommit'],'baselineImageId':live['Image'],'baselineJarSha256':sha(raw),'candidateJarSha256':digest,'image':image,'imageId':run('docker','image','inspect','--format','{{.Id}}',image),'compiler':manifest['compiler'],'compiledClassFiles':3,'updatedSchemaResources':3,'nestedLibraries':proofs,**outer,'inputFiles':manifest['files']}
(r/'api-artifact-proof.json').write_text(json.dumps(p,indent=2));print(json.dumps(p))
