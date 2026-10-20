"""Build a parser-only layer for the isolated API; no generated code is executed."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

root=Path('/opt/runtime-os-docker-stage/evidence/runtime-os-studios-20261021')
root.mkdir(mode=0o700,exist_ok=True)
wheel=root/'sqlglot-30.23.0-py3-none-any.whl'
expected='b5a645722cb4c6b649e9131b94830d9df9a557e87be63713179d848320f2baa1'
if hashlib.sha256(wheel.read_bytes()).hexdigest()!=expected: raise RuntimeError('Pinned parser hash mismatch')
context=root/'parser-image';context.mkdir(exist_ok=True)
with zipfile.ZipFile(wheel) as archive:
    for member in archive.infolist():
        if not member.filename.startswith('sqlglot/') or member.is_dir():continue
        file=context/member.filename
        if not file.resolve().is_relative_to(context.resolve()): raise RuntimeError('Unsafe wheel')
        file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(archive.read(member))
base=json.loads(subprocess.check_output(['docker','inspect','runtime-os-app-stage-api-1']))[0]['Config']['Image']
dockerfile=f'''FROM {base}
USER root
RUN apt-get -o Acquire::Retries=0 -o Acquire::http::Timeout=30 update && apt-get -o Acquire::Retries=0 -o Acquire::http::Timeout=30 install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY sqlglot /usr/local/lib/python3.10/dist-packages/sqlglot
RUN /usr/bin/python3 -I -c "import sqlglot; assert sqlglot.__version__ == '30.23.0'"
'''
(context/'Dockerfile').write_text(dockerfile)
tag='runtime-os-api:studio-parser-sqlglot-30.23.0'
subprocess.run(['docker','build','--network=host','-t',tag,str(context)],check=True)
report={'status':'BUILT','baseImage':base,'image':tag,'wheelSha256':expected,'sqlExecuted':False,'modelCodeExecuted':False}
(root/'parser-image.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report),flush=True)
