"""Read-only deployment inventory. Do not export credentials or source business rows."""
import json
from pathlib import Path
import subprocess

root = Path('/opt/runtime-os-docker-stage')
def run(*args):
    return subprocess.check_output(args, text=True).strip()

print(json.dumps({
    'containers': json.loads(run('docker', 'container', 'ls', '--format', 'json').replace('\n', ',').join(['[', ']'])),
    'evidenceFiles': [str(path.relative_to(root)) for path in (root/'evidence').rglob('*')
                      if path.is_file() and (path.name in {'summary.json', 'request.json', 'deployment.json'}
                                            or 'benchmark' in path.name)],
}, ensure_ascii=False, indent=2))
