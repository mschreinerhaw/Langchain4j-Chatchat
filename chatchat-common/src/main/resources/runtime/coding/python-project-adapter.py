# runtime_os.python.code-project.v1
__project_b64__ = "__RUNTIME_PROJECT_PAYLOAD__"
# Source packaging only. This program runs inside the existing managed Sandbox.
import base64
import json
import os
from pathlib import Path
import subprocess
import sys

__project = json.loads(base64.b64decode(__project_b64__).decode('utf-8'))
__root = Path('/tmp/runtime-python-project')
__root.mkdir(parents=True, exist_ok=True)
for __file in __project['files']:
    __path = (__root / __file['path']).resolve()
    if not __path.is_relative_to(__root.resolve()) or not __path.name.endswith('.py'):
        raise PermissionError('Project path rejected')
    compile(__file['content'].encode('utf-8'), str(__path), 'exec')
    __path.parent.mkdir(parents=True, exist_ok=True)
    __path.write_bytes(__file['content'].encode('utf-8'))
__entry = __root / __project['entryPath']
__parts = Path(__project['entryPath']).parts
__package = len(__parts) > 1 and all((__root.joinpath(*__parts[:i]) / '__init__.py').is_file() for i in range(1, len(__parts)))
__command = [sys.executable, '-m', '.'.join(__parts)[:-3]] if __package else [sys.executable, str(__entry)]
__env = dict(os.environ)
__env['PYTHONPATH'] = str(__root) + os.pathsep + str(__entry.parent)
raise SystemExit(subprocess.run(__command, cwd=str(__root), env=__env).returncode)
