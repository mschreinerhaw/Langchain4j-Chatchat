"""Assemble official Linux standalone distribution and required HostBridge proto assets."""
import pathlib,sys,zipfile,shutil,hashlib,json
source=pathlib.Path(sys.argv[1]).resolve();dest=pathlib.Path(sys.argv[2]).resolve()
if dest==source or source in dest.parents:raise SystemExit('Use a separate runtime destination outside source checkout')
archive=source/'apps/vscode/dist-standalone/standalone.zip'
required=['cline-core.js','package.json','proto/descriptor_set.pb','proto/host/workspace.proto','proto/host/env.proto','proto/host/window.proto','proto/host/diff.proto','proto/cline/task.proto','proto/cline/state.proto']
if not archive.is_file():raise SystemExit('Missing official Standalone archive: '+str(archive))
if not (source/'apps/vscode/proto/descriptor_set.pb').is_file():raise SystemExit('Missing generated ProtoBus resource: apps/vscode/proto/descriptor_set.pb; run official build protos')
if dest.exists() and any(dest.iterdir()):raise SystemExit('Runtime destination must be empty; refusing stale resource overlay')
dest.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(archive) as z:
 for name in z.namelist():
  p=(dest/name).resolve()
  if not p.is_relative_to(dest):raise SystemExit('Archive path outside destination')
 z.extractall(dest)
# The official VSIX-style exclude list omits proto/, but ProtoBus loads its descriptor at runtime.
shutil.copytree(source/'apps/vscode/proto',dest/'proto',dirs_exist_ok=True)
for name in required:
 if not (dest/name).is_file() or (dest/name).stat().st_size==0:raise SystemExit('Required runtime resource missing or empty: '+name)
files=[]
for p in sorted(dest.rglob('*')):
 if p.is_file():files.append({'path':str(p.relative_to(dest)),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
manifest={'upstream':'v4.1.26','sourceCommit':'a459f1d04dea030833b558be645011ab739c463b','required':required,'descriptorSource':'apps/vscode/proto/descriptor_set.pb','hostAssetAdaptation':'copy generated proto directory for ProtoBus and Server HostBridge','files':files}
(dest/'runtime-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({'runtimeFiles':len(files),'manifest':str(dest/'runtime-manifest.json')}))
