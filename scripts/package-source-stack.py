"""Package the current Git working branch without app binaries or credentials."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True)
a=p.parse_args();root=Path(subprocess.check_output(['git','rev-parse','--show-toplevel'],text=True).strip()).resolve()
paths=subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=root).decode().split('\0')
blocked={'node_modules','__pycache__','.git','private','secrets'}
blocked_roots={'docs','deployment-artifacts','dist','runtime'}
suffixes={'.jar','.class','.pyc','.zip','.gz','.tar','.log','.key','.pem','.env'}
packaged_examples={
    'chatchat-api/src/main/resources/python-examples/data/sample_application.log',
    'chatchat-api/src/main/resources/python-examples/data/sample_bundle.zip',
}
manifest={'baseCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
    'branch':subprocess.check_output(['git','branch','--show-current'],cwd=root,text=True).strip(),
    'workingTreeIncluded':True,
    'workingTreeDirty':bool(subprocess.check_output(['git','status','--porcelain','--untracked-files=normal'],cwd=root)),
    'files':[]}

def add_member(archive, name, data):
    # Explicit, nonzero source mtime, independent of checkout/release host.
    entry=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,2))
    entry.create_system=3
    entry.external_attr=0o100644 << 16
    archive.writestr(entry,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=6)

a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for name in sorted(set(paths)):
        path=Path(name)
        if not name or name=='source-manifest.json' or path.parts[0] in blocked_roots or set(path.parts)&blocked or name.endswith('.private.json'):continue
        if path.suffix.lower() in suffixes and name not in packaged_examples:continue
        # Java source packages can legitimately be named "target". Only Maven
        # output directories at the repository/module roots are excluded.
        if path.parts[0]=='target' or len(path.parts)>1 and path.parts[1]=='target':continue
        file=root/path
        if not file.is_file() or file.is_symlink():continue
        data=file.read_bytes();add_member(archive,name,data)
        manifest['files'].append({'path':name,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    add_member(archive,'source-manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
print(json.dumps({'files':len(manifest['files']),'baseCommit':manifest['baseCommit'],'workingTreeIncluded':True,
    'workingTreeDirty':manifest['workingTreeDirty'],
    'archiveSha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'bytes':a.output.stat().st_size}))
