"""Assemble a self-contained native candidate/release; never assigns a Git release tag."""
import argparse,hashlib,json,os,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--version',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--acceptance-report',type=Path);a=p.parse_args()
try:commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,stderr=subprocess.DEVNULL,text=True).strip()
except Exception:commit='unknown'
acceptance_files={}
if '-' not in a.version:
    if not a.acceptance_report:raise SystemExit('Formal release requires a complete real acceptance report; use an explicit candidate version until then')
    report=json.loads(a.acceptance_report.read_text(encoding='utf-8'))
    if subprocess.run(['git','diff','--quiet','HEAD','--','.'],cwd=ROOT).returncode:raise SystemExit('Commit tracked source changes before formal packaging')
    for name in subprocess.check_output(['git','ls-files','--others','--exclude-standard','--','.'],cwd=ROOT,text=True).splitlines():
        extra=(ROOT/name).resolve()
        if extra!=a.acceptance_report.resolve() and not extra.is_relative_to(ROOT/'docs/evidence'):raise SystemExit('Uncommitted source file prevents formal packaging: '+name)
    required={'offline_install','h2_persistence','independent_startup','admin_login_api','offline_image_import','runtime_deployment_ui','runtime_live_status','platform_restart_isolation','platform_upgrade_isolation','application_upgrade_rollback','backup_restore','missing_mount_refusal'}
    if report.get('format')!='deployment-ops-acceptance/v1' or commit=='unknown' or report.get('sourceCommit')!=commit:raise SystemExit('Acceptance report must identify this exact source commit')
    if report.get('jarSha256')!=hashlib.sha256(a.jar.read_bytes()).hexdigest():raise SystemExit('Acceptance report must identify the packaged server-built JAR')
    scenarios=report.get('scenarios',{})
    if set(scenarios)!=required or any(item.get('status')!='passed' or not item.get('evidence') for item in scenarios.values()):raise SystemExit('Every mandatory real acceptance scenario must have passed evidence')
    for item in scenarios.values():
        for evidence in item['evidence']:
            source=(ROOT/evidence['path']).resolve();source.relative_to(ROOT)
            if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest()!=evidence['sha256']:raise SystemExit('Acceptance evidence checksum mismatch')
            acceptance_files[source.relative_to(ROOT).as_posix()]=source.read_bytes()
    acceptance_files['docs/acceptance/report.json']=a.acceptance_report.read_bytes()
if a.output.exists():raise SystemExit('Output exists; choose an immutable new package filename')
if not (ROOT/'web-ui/dist/index.html').is_file():raise SystemExit('Build the Vue frontend before packaging')
files=dict(acceptance_files)
for name in ['src/main','backend/src','backend/lib','backend/schema.sql','executor','frontend','web-ui/dist','web-ui/src','web-ui/public','web-ui/package.json','web-ui/package-lock.json','web-ui/.prettierrc.json','web-ui/vue.config.js','deploy/native','deploy/jdk','recipes/runtime-os-native','recipes/protocol-example','docs/INSTALLATION-PROTOCOL.md','pom.xml','docs/dependencies.json','release/trust','tests','docs/NATIVE-OPERATIONS.md','docs/JDK-OPERATIONS.md','docs/APPLICATION-PLATFORM.md','docs/CLEAN-SERVER-93-20261118.md','docs/evidence/clean-server-93-20261118']:
    source=ROOT/name
    for path in source.rglob('*') if source.is_dir() else [source]:
        if path.is_symlink():raise SystemExit('Release source contains symlink: '+str(path))
        if path.is_file() and '__pycache__' not in path.parts:
            if path.suffix in ('.der','.mv.db','.key') or path.name.startswith('.env'):raise SystemExit('Private/generated file in release source')
            content=path.read_bytes()
            if path.suffix in ('.sh','.py','.java','.vue','.js','.css','.md','.json','.yml','.service'):content=content.replace(b'\r\n',b'\n')
            files[path.relative_to(ROOT).as_posix()]=content
for name in ['build-native-offline.py','BuildNativeOffline.java','package-native.py','build-package.py','import-offline-package.py','prepare-native-runtime.py','export-image-schema.py','ImageSchemaExporter.java']:
    files['scripts/'+name]=(ROOT/'scripts'/name).read_bytes().replace(b'\r\n',b'\n')
if (ROOT/'docs/JDK-OPERATIONS.md').exists():files['README.md']=(ROOT/'docs/JDK-OPERATIONS.md').read_bytes()
files['lib/deployment-ops.jar']=a.jar.read_bytes()
# Dependency-only Boot archive enables javac source builds on an offline target host.
import io
seed=io.BytesIO()
with zipfile.ZipFile(a.jar) as jar,zipfile.ZipFile(seed,'w') as runtime:
    for entry in jar.infolist():
        if not entry.filename.startswith('BOOT-INF/classes/'):runtime.writestr(entry,jar.read(entry))
files['build/boot-runtime.zip']=seed.getvalue()
manifest={'format':'deployment-ops-native/v1','version':a.version,'schemaVersion':2,'sourceCommit':commit,'candidate':'-' in a.version,'files':{name:hashlib.sha256(value).hexdigest() for name,value in sorted(files.items())}}
files['release.json']=json.dumps(manifest,indent=2).encode()
a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for name,value in sorted(files.items()):archive.writestr(name,value)
digest=hashlib.sha256()
with a.output.open('rb') as source:
    for chunk in iter(lambda:source.read(1024**2),b''):digest.update(chunk)
a.output.with_name(a.output.name+'.sha256').write_bytes((digest.hexdigest()+'  '+a.output.name+'\n').encode('ascii'))
print(json.dumps({'file':str(a.output),'sha256':digest.hexdigest(),'files':len(files),'version':a.version,'sourceCommit':commit,'formalRelease':not manifest['candidate']}))
