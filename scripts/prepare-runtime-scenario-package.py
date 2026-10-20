"""Prepare audited module/class overlays from compiled, separately tested reactor outputs."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
import sys

repo = Path(__file__).resolve().parents[1]
output = repo/'target/runtime-os-refactor-20261020/scenario-package'
output.mkdir(parents=True,exist_ok=True)
studio='--studio' in sys.argv
modules = ('chatchat-agents','chatchat-knowledge-base','chatchat-common','chatchat-chat')
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'modules':{}}
for module in modules:
    name = module+'-1.0.0-SNAPSHOT.jar'
    source = repo/module/'target'/name
    shutil.copy2(source,output/name)
    manifest['modules'][module] = digest(source)
families = {
    'cline':('ClineCodingAdapter','ClineModelCallbackController','ClineModelReceiptStore'),
    'analysis':('ClineContextAnswerService','CodingAnalysisService','CodingExecutionProfile'),
}
with zipfile.ZipFile(output/'api-classes.zip','w') as archive:
    for package,names in families.items():
        folder = repo/'chatchat-api/target/classes/com/chatchat/api/runtime/coding'/package
        for name in names:
            classes = [folder/(name+'.class'),*folder.glob(name+'$*.class')]
            for file in classes:
                archive.write(file, 'com/chatchat/api/runtime/coding/'+package+'/'+file.name)
    if studio:
        for package,names in {'datascience':('StudioCodePlanService','StudioTaskHistory'),
                'datascience/sql':('SqlStudioService',),'datascience/python':('PythonCodeAssistantService',),
                'runtime/coding/workspace':('CodingWorkspaceService',)}.items():
            folder=repo/'chatchat-api/target/classes/com/chatchat/api'/package
            for name in names:
                for file in [folder/(name+'.class'),*folder.glob(name+'$*.class')]:
                    archive.write(file,'com/chatchat/api/'+package+'/'+file.name)
manifest['apiClassOverlaySha256'] = digest(output/'api-classes.zip')
if studio:
    web={}
    folder=repo/'chatchat-api/src/main/resources/static'
    with zipfile.ZipFile(output/'web-assets.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for file in folder.rglob('*'):
            if file.is_file():
                name='BOOT-INF/classes/static/'+file.relative_to(folder).as_posix()
                archive.write(file,name);web[name]=digest(file)
    (output/'web-files.json').write_text(json.dumps(web,indent=2))
    manifest['webAssetsSha256']=digest(output/'web-assets.zip')
(output/'module-manifest.json').write_text(json.dumps(manifest,indent=2))
node = {}
for name in ('runner.js','server.js','code-repair.js'):
    source = repo/'coding-agent-node/dist'/name
    shutil.copy2(source,output/name)
    node[name] = digest(source)
(output/'node-manifest.json').write_text(json.dumps(node,indent=2))
print(json.dumps({'output':str(output),**manifest},indent=2))
