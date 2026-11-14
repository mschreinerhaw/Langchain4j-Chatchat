"""Package committed source for a server build; never ship local application binaries."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[3]
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
archive = subprocess.check_output(['git', 'archive', '--format=zip', commit], cwd=repo)
rows = []
with zipfile.ZipFile(io.BytesIO(archive)) as source, zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as output:
    for item in source.infolist():
        name = item.filename
        # Match Maven output at the project/module root, not Java packages such as routing/target.
        if (item.is_dir() or name.startswith(('docs/', '.github/'))
                or re.match(r'^(?:[^/]+/)?(?:target|dist)/', name)
                or '/node_modules/' in '/' + name or '/src/main/resources/static/' in '/' + name
                or name.endswith(('.jar', '.class', '.zip', '.tar.gz'))):
            continue
        data = source.read(item)
        output.writestr(name, data)
        rows.append({'path': name, 'sha256': hashlib.sha256(data).hexdigest()})
    included = {row['path'] for row in rows}
    required = {name for name in source.namelist() if '/src/main/java/' in name and name.endswith('.java')}
    if not required <= included:
        raise RuntimeError('Java source excluded: ' + str(sorted(required - included)))
    output.writestr('source-manifest.json', json.dumps({'sourceCommit': commit,
        'prebuiltApplicationIncluded': False, 'files': rows}, indent=2))
print(json.dumps({'sourceCommit': commit, 'files': len(rows), 'javaSourcesVerified': len(required),
    'archiveBytes': args.output.stat().st_size}))
