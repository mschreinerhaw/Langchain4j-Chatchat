"""Assemble official Linux standalone distribution and required HostBridge proto assets."""
import pathlib,sys,zipfile,shutil,hashlib,json
source=pathlib.Path(sys.argv[1]).resolve();dest=pathlib.Path(sys.argv[2]).resolve()
if dest==source or source in dest.parents:raise SystemExit('Use a separate runtime destination outside source checkout')
archive=source/'apps/vscode/dist-standalone/standalone.zip'
dest.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(archive) as z:
 for name in z.namelist():
  p=(dest/name).resolve()
  if not p.is_relative_to(dest):raise SystemExit('Archive path outside destination')
 z.extractall(dest)
# The official VSIX-style exclude list omits proto/, but ProtoBus loads its descriptor at runtime.
shutil.copytree(source/'apps/vscode/proto',dest/'proto',dirs_exist_ok=True)
files=[]
for p in sorted(dest.rglob('*')):
 if p.is_file():files.append({'path':str(p.relative_to(dest)),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
manifest={'upstream':'v4.1.26','sourceCommit':'a459f1d04dea030833b558be645011ab739c463b','hostAssetAdaptation':'copy generated proto directory for ProtoBus and Server HostBridge','files':files}
(dest/'runtime-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({'runtimeFiles':len(files),'manifest':str(dest/'runtime-manifest.json')}))
