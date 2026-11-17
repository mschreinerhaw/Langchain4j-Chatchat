"""Build a signed release from reviewed JSON and explicit assets, never a production directory snapshot."""
import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile, zipfile, tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'executor'))
from package_store import validate_manifest,member_name,safe_name
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--image-archive',type=Path);p.add_argument('--image-archives',type=Path,help='Reviewed JSON mapping image roles to existing Docker-save TAR paths');p.add_argument('--store-images',action='store_true');p.add_argument('--manifest',required=True,type=Path);p.add_argument('--assets',required=True,type=Path);p.add_argument('--private-key',required=True,type=Path);p.add_argument('--key-id',required=True);p.add_argument('--output',required=True,type=Path);p.add_argument('--signer-image');p.add_argument('--classes',type=Path,default=ROOT/'backend/classes');p.add_argument('--libraries',type=Path,default=ROOT/'backend/lib');p.add_argument("--reflink-from",type=Path,help="Trusted complete base ZIP on a reflink filesystem");p.add_argument("--reflink-sha256",help="Required publisher SHA256 for the base ZIP");args=p.parse_args()
if args.output.exists():raise SystemExit('Output already exists; use a new release filename')
if args.reflink_from:
 if not args.reflink_sha256 or len(args.reflink_sha256)!=64:raise SystemExit('Publisher SHA-256 is required for reflink base')
 if args.reflink_from.is_symlink() or not args.reflink_from.is_file():raise SystemExit('Reflink source must be a regular complete archive')
 with args.reflink_from.open('rb') as stream:
  if hashlib.file_digest(stream,'sha256').hexdigest()!=args.reflink_sha256:raise SystemExit('Reflink base SHA-256 mismatch')
manifest=json.loads(args.manifest.read_text(encoding='utf-8'))
reuse=json.loads(args.image_archives.read_text()) if args.image_archives else None
if reuse is not None and (args.image_archive or set(reuse)!=set(manifest['images'])):raise SystemExit('Archive map must cover every image role and cannot combine with --image-archive')
def run(argv):return subprocess.check_output(argv,stderr=subprocess.STDOUT)
with tempfile.TemporaryDirectory(prefix='ops-package-',dir=args.output.resolve().parent) as temp:
 folder=Path(temp);payload={}
 references=[]
 for key,image in manifest['images'].items():
  safe_name(key)
  reference=image.pop('source',None) or image.get('id');info=json.loads(run(['docker','image','inspect',reference]))[0]
  if info.get('Os','linux')+'/'+info['Architecture']!=manifest['platform']:raise ValueError('Image platform differs from release: '+key)
  image.update({'id':info['Id'],'size':info['Size'],'archive':'images/all.tar'});references.append(info['Id'])
 print('Exporting pinned offline images...',flush=True)
 if reuse is None:
  target=args.image_archive.resolve() if args.image_archive else folder/'all-images.tar'
  if not args.image_archive:run(['docker','save','-o',str(target),*sorted(set(references))])
  archives={target:set(references)};names={target:'images/all.tar'}
 else:
  archives={};names={}
  for role,image in manifest['images'].items():
   target=Path(reuse[role]).resolve()
   if target not in names:names[target]='images/archive-'+str(len(names)+1)+'.tar'
   image['archive']=names[target];archives.setdefault(target,set()).add(image['id'])
 for target,identities in archives.items():
  with tarfile.open(target,'r:') as saved:
   entries=json.load(saved.extractfile('manifest.json'));present={'sha256:'+hashlib.sha256(saved.extractfile(entry['Config']).read()).hexdigest() for entry in entries}
   if not identities<=present:raise ValueError('The reused archive does not contain every selected image ID')
  payload[names[target]]=target
 for c in manifest['components']:
  for asset in c.get('files',[]):
   name=member_name(asset['source']);source=(args.assets/name).resolve();source.relative_to(args.assets.resolve())
   if not source.is_file() or source.is_symlink():raise ValueError('Missing or symlinked asset: '+name)
   payload[name]=source
 if manifest.get('runtimeInstaller'):
  for name in [manifest['runtimeInstaller']['imageMap'],'assets/runtime-source.json']:
   member_name(name);source=(args.assets/name).resolve();source.relative_to(args.assets.resolve())
   if not source.is_file() or source.is_symlink():raise ValueError('Missing native Runtime OS publication asset: '+name)
   payload[name]=source
 manifest_file=folder/'manifest.json';manifest_file.write_text(json.dumps(manifest,indent=2),encoding='utf-8');payload['manifest.json']=manifest_file
 print('Hashing release payloads...',flush=True)
 checksums={name:hashlib.file_digest(file.open('rb'),'sha256').hexdigest() for name,file in payload.items()}
 validate_manifest(manifest,checksums)
 inventory=folder/'checksums.json';inventory.write_text(json.dumps(checksums,sort_keys=True,separators=(',',':')),encoding='ascii')
 classpath=str(args.classes)+os.pathsep+str(args.libraries/'*')
 if args.signer_image:
  run(['docker','run','--rm','--network','none','--user','0:0','--mount','type=bind,source='+str(args.private_key.resolve().parent)+',target=/keys,readonly','--mount','type=bind,source='+str(folder.resolve())+',target=/signing',args.signer_image,'java','-cp','classes:lib/*','PackageSigner','sign','/keys/'+args.private_key.name,'/signing/checksums.json','/signing/signature.json',args.key_id])
 else:
  run(['java','-cp',classpath,'PackageSigner','sign',str(args.private_key),str(inventory),str(folder/'signature.json'),args.key_id])

 payload.update({'checksums.json':inventory,'signature.json':folder/'signature.json'})
 print('Writing signed offline archive...',flush=True)
 if args.reflink_from:
  # Preserve image compressed bytes using filesystem CoW. Old metadata is removed
  # from the central directory; no original file or private key is modified.
  with zipfile.ZipFile(args.reflink_from) as base:
   old_checks=json.loads(base.read('checksums.json'));retained={}
   for name,file in payload.items():
    if name.startswith('images/'):
     entry=base.getinfo(name)
     if old_checks.get(name)!=checksums[name] or entry.file_size!=file.stat().st_size:raise ValueError('Reflink base image differs from selected pinned archive')
     retained[name]=entry
  try:
   run(['/bin/cp','--reflink=always','--',str(args.reflink_from),str(args.output)])
   with zipfile.ZipFile(args.output,'a',compression=zipfile.ZIP_DEFLATED,compresslevel=1,allowZip64=True) as archive:
    archive.filelist=list(retained.values());archive.NameToInfo=dict(retained)
    for name,file in sorted(payload.items()):
     if name not in retained:archive.write(file,name)
   with zipfile.ZipFile(args.output) as archive:
    if set(archive.namelist())!=set(payload) or len(archive.namelist())!=len(payload):raise ValueError('Reflink ZIP member inventory mismatch')
  except Exception:
   # Only the uniquely named output created by this invocation is disposable.
   if args.output.exists():args.output.unlink()
   raise
 else:
  with zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=1,allowZip64=True) as archive:
   for name,file in sorted(payload.items()):archive.write(file,name,compress_type=zipfile.ZIP_STORED if args.store_images and name.startswith('images/') else zipfile.ZIP_DEFLATED)
with args.output.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
args.output.with_suffix(args.output.suffix+'.sha256').write_bytes((digest+'  '+args.output.name+'\n').encode('ascii'))
print(json.dumps({'file':str(args.output),'sha256':digest,'package':manifest['name'],'version':manifest['version']}))
