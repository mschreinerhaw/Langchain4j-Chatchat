#!/usr/bin/env python3
"""Rebuild host metadata propagation in a private copy of pinned official source."""
import pathlib,subprocess,shutil,json,hashlib
R=pathlib.Path('/opt/cline-server-p3-stage2-20261108');S=R/'source'
original=pathlib.Path('/opt/cline-agent-server-poc-20261108/build/cline-a459f1d04dea030833b558be645011ab739c463b')
copy=R/'upstream'
def run(*a,**kw):return subprocess.run(a,check=True,text=True,**kw)
if not copy.exists():run('cp','-a','--reflink=auto',str(original),str(copy))
if not (R/'tools').exists():shutil.copytree(original.parents[1]/'tools',R/'tools')
image=json.loads(subprocess.check_output(['docker','inspect','cline-server-poc-builder']))[0]['Image']
def build(command):
 return run('docker','run','--rm','--user','0:0','--network','none','--mount',f'type=bind,src={R},dst=/stage2','-w','/stage2/upstream','--entrypoint','bash',image,'-lc',command)
if not (copy/'stage2-host-patches.json').exists():build('node /stage2/source/cline-agent-server/scripts/stage2-patch-upstream.mjs /stage2/upstream')
log=R/'evidence/native-build.log'
with log.open('w') as f:
 result=subprocess.run(['docker','run','--rm','--user','0:0','--network','none','--mount',f'type=bind,src={R},dst=/stage2','-w','/stage2/upstream/apps/vscode','--entrypoint','bash',image,'-lc','export PATH=/stage2/tools/bun-linux-x64:$PATH; bun run check-types && bun esbuild.mjs --standalone'],stdout=f,stderr=subprocess.STDOUT)
print('NATIVE_BUILD_EXIT',result.returncode)
if result.returncode:print(log.read_text()[-6000:]);raise SystemExit(result.returncode)
old_image='cline-agent-server:p3-stage1'
container=subprocess.check_output(['docker','create',old_image],text=True).strip()
runtime=R/'runtime'
if not runtime.exists():run('docker','cp',container+':/poc/runtime',str(runtime))
run('docker','rm',container)
bundle=copy/'apps/vscode/dist-standalone/cline-core.js'
shutil.copy2(bundle,runtime/'cline-core.js')
manifest=json.loads((runtime/'runtime-manifest.json').read_text())
for f in manifest['files']:
 p=runtime/f['path']
 if f['path']=='cline-core.js':f['bytes']=p.stat().st_size;f['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
(runtime/'runtime-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
builddir=R/'image';builddir.mkdir(exist_ok=True)
shutil.copytree(S/'cline-agent-server',builddir/'service',dirs_exist_ok=True)
shutil.copytree(runtime,builddir/'runtime',dirs_exist_ok=True)
(builddir/'Dockerfile').write_text('FROM cline-agent-server:p3-stage1\nUSER root\nCOPY service/ /poc/service/\nCOPY runtime/ /poc/runtime/\nUSER 1000:1000\nCMD ["node","/poc/service/src/isolated-worker.mjs"]\n')
with (R/'evidence/docker-build.log').open('w') as f:run('docker','build','--network','none','-t','cline-agent-server:p3-stage2',str(builddir),stdout=f,stderr=subprocess.STDOUT)
print('STAGE2_IMAGE',subprocess.check_output(['docker','image','inspect','cline-agent-server:p3-stage2','--format','{{.Id}}'],text=True).strip())
