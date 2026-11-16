"""Assemble a self-contained native candidate/release; never assigns a Git release tag."""
import argparse,hashlib,json,os,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--version',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Output exists; choose an immutable new package filename')
if not (ROOT/'web-ui/dist/index.html').is_file():raise SystemExit('Build the Vue frontend before packaging')
files={}
for name in ['src/main','backend/src','backend/lib','backend/schema.sql','executor','frontend','web-ui/dist','web-ui/src','web-ui/public','web-ui/package.json','web-ui/package-lock.json','web-ui/vue.config.js','deploy/native','recipes/runtime-os','docs/APPLICATION-PLATFORM.md']:
    source=ROOT/name
    for path in source.rglob('*') if source.is_dir() else [source]:
        if path.is_symlink():raise SystemExit('Release source contains symlink: '+str(path))
        if path.is_file() and '__pycache__' not in path.parts:
            if path.suffix in ('.der','.mv.db','.key') or path.name.startswith('.env'):raise SystemExit('Private/generated file in release source')
            content=path.read_bytes()
            if path.suffix in ('.sh','.py','.java','.vue','.js','.css','.md','.json','.yml','.service'):content=content.replace(b'\r\n',b'\n')
            files[path.relative_to(ROOT).as_posix()]=content
for name in ['build-native-offline.py','package-native.py','build-package.py','prepare-runtime-os.py','export-image-schema.py','ImageSchemaExporter.java']:
    files['scripts/'+name]=(ROOT/'scripts'/name).read_bytes().replace(b'\r\n',b'\n')
if (ROOT/'docs/NATIVE-OPERATIONS.md').exists():files['README.md']=(ROOT/'docs/NATIVE-OPERATIONS.md').read_bytes()
files['lib/deployment-ops.jar']=a.jar.read_bytes()
# Dependency-only Boot archive enables javac source builds on an offline target host.
import io
seed=io.BytesIO()
with zipfile.ZipFile(a.jar) as jar,zipfile.ZipFile(seed,'w') as runtime:
    for entry in jar.infolist():
        if not entry.filename.startswith('BOOT-INF/classes/'):runtime.writestr(entry,jar.read(entry))
files['build/boot-runtime.zip']=seed.getvalue()
try:commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
except Exception:commit='unknown'
manifest={'format':'deployment-ops-native/v1','version':a.version,'schemaVersion':2,'sourceCommit':commit,'candidate':'-' in a.version,'files':{name:hashlib.sha256(value).hexdigest() for name,value in sorted(files.items())}}
files['release.json']=json.dumps(manifest,indent=2).encode()
a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for name,value in sorted(files.items()):archive.writestr(name,value)
digest=hashlib.sha256()
with a.output.open('rb') as source:
    for chunk in iter(lambda:source.read(1024**2),b''):digest.update(chunk)
a.output.with_name(a.output.name+'.sha256').write_text(digest.hexdigest()+'  '+a.output.name+'\n')
print(json.dumps({'file':str(a.output),'sha256':digest.hexdigest(),'files':len(files),'version':a.version,'sourceCommit':commit,'formalRelease':not manifest['candidate']}))
