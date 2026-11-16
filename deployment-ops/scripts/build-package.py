"""Build a signed release from reviewed JSON and explicit assets, never a production directory snapshot."""
import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile, zipfile, tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'executor'))
from package_store import validate_manifest,member_name,safe_name
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--image-archive',type=Path);p.add_argument('--store-images',action='store_true');p.add_argument('--manifest',required=True,type=Path);p.add_argument('--assets',required=True,type=Path);p.add_argument('--private-key',required=True,type=Path);p.add_argument('--key-id',required=True);p.add_argument('--output',required=True,type=Path);p.add_argument('--signer-image');p.add_argument('--classes',type=Path,default=ROOT/'backend/classes');p.add_argument('--libraries',type=Path,default=ROOT/'backend/lib');args=p.parse_args()
if args.output.exists():raise SystemExit('Output already exists; use a new release filename')
manifest=json.loads(args.manifest.read_text(encoding='utf-8'))
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
 target=args.image_archive.resolve() if args.image_archive else folder/'all-images.tar'
 if not args.image_archive:run(['docker','save','-o',str(target),*sorted(set(references))])
 with tarfile.open(target,'r:') as saved:
  entries=json.load(saved.extractfile('manifest.json'));present={'sha256:'+hashlib.sha256(saved.extractfile(entry['Config']).read()).hexdigest() for entry in entries}
  if not set(references)<=present:raise ValueError('The reused archive does not contain every selected image ID')
 payload['images/all.tar']=target
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
 with zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=1,allowZip64=True) as archive:
  for name,file in sorted(payload.items()):archive.write(file,name,compress_type=zipfile.ZIP_STORED if args.store_images and name.startswith('images/') else zipfile.ZIP_DEFLATED)
with args.output.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
args.output.with_suffix(args.output.suffix+'.sha256').write_text(digest+'  '+args.output.name+'\n',encoding='ascii')
print(json.dumps({'file':str(args.output),'sha256':digest,'package':manifest['name'],'version':manifest['version']}))
