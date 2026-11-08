#!/usr/bin/env python3
"""Prepare ONLY a new isolated stage-2 directory; never replace a running service."""
import pathlib, subprocess, zipfile, hashlib, json, secrets, shutil, re
R=pathlib.Path('/opt/cline-server-p3-stage2-20261108')
R.mkdir(exist_ok=True)
def run(*a,**kw): return subprocess.run(a,check=True,text=True,**kw)
for n in ('input','lib','classes','api-classes','mcp-classes','private','evidence','python/workspace','python/data'):(R/n).mkdir(parents=True,exist_ok=True)
for kind in ('api','mcp'):
 p=R/'input'/f'{kind}.jar'
 if not p.exists():run('docker','cp',f'runtime-native-acceptance-{kind}:/srv/app/app.jar',str(p))
 with zipfile.ZipFile(p) as z:
  for name in z.namelist():
   if name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
    target=R/f'{kind}-classes'/name[len('BOOT-INF/classes/'):];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
   if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
    target=R/'lib'/pathlib.Path(name).name
    if not target.exists():target.write_bytes(z.read(name))
src=R/'source'
files=list((src/'cline-agent-server/stage2-java/src').rglob('*.java'))+[src/'chatchat-agents/src/main/java/com/chatchat/agents/runtime/coding/DurableCodingTaskService.java']
cp=':'.join(str(R/n) for n in ('classes','api-classes','mcp-classes','lib/*'))
result=subprocess.run(['/opt/jdk-17.0.5/bin/javac','-encoding','UTF-8','-cp',cp,'-d',str(R/'classes'),*map(str,files)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(R/'evidence/javac.log').write_text(result.stdout);print(result.stdout);print('JAVAC_EXIT',result.returncode)
if result.returncode:raise SystemExit(result.returncode)
print('COMPILED',len(files),'source files against existing application jars')
