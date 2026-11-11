"""Package the current Git working branch without app binaries or credentials."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True)
a=p.parse_args();root=Path(subprocess.check_output(['git','rev-parse','--show-toplevel'],text=True).strip()).resolve()
paths=subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=root).decode().split('\0')
blocked={'node_modules','__pycache__','.git','private','secrets'}
blocked_roots={'docs','deployment-artifacts','dist','runtime'}
suffixes={'.jar','.class','.pyc','.zip','.gz','.tar','.log','.key','.pem','.env'}
manifest={'baseCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
    'branch':subprocess.check_output(['git','branch','--show-current'],cwd=root,text=True).strip(),
    'workingTreeIncluded':True,'files':[]}
a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for name in sorted(set(paths)):
        path=Path(name)
        if not name or path.parts[0] in blocked_roots or set(path.parts)&blocked or path.suffix.lower() in suffixes or name.endswith('.private.json'):continue
        # Java source packages can legitimately be named "target". Only Maven
        # output directories at the repository/module roots are excluded.
        if path.parts[0]=='target' or len(path.parts)>1 and path.parts[1]=='target':continue
        file=root/path
        if not file.is_file() or file.is_symlink():continue
        data=file.read_bytes();archive.writestr(name,data)
        manifest['files'].append({'path':name,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    archive.writestr('source-manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
print(json.dumps({'files':len(manifest['files']),'baseCommit':manifest['baseCommit'],'workingTreeIncluded':True,
    'archiveSha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'bytes':a.output.stat().st_size}))
