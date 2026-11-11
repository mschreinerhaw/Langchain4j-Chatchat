"""Fail the image build on dependency drift or broken Python ABI dependencies."""
import importlib
import importlib.metadata
import json
import pathlib
import re
import sqlite3
import subprocess
import sys

assert sys.version_info[:2] == (3, 11), sys.version
lock = pathlib.Path('/opt/chatchat/requirements.lock')
for raw in lock.read_text().splitlines():
    if not raw.strip() or raw.startswith('#'):
        continue
    match = re.fullmatch(r'([A-Za-z0-9_.-]+)==([^\s]+)', raw.strip())
    if not match:
        raise RuntimeError('Unpinned dependency: ' + raw)
    name, version = match.groups()
    actual = importlib.metadata.version(name)
    if actual != version:
        raise RuntimeError(f'Dependency drift: {name}: {actual} != {version}')
modules = ['pandas', 'numpy', 'pyarrow', 'duckdb', 'matplotlib', 'openpyxl',
           'pytest', 'psycopg', 'pyodbc', 'oracledb', 'scipy', 'sklearn']
for name in modules:
    importlib.import_module(name)
subprocess.run([sys.executable, '-m', 'pip', 'check'], check=True)
print(json.dumps({'python': sys.version, 'sqlite': sqlite3.sqlite_version,
                  'imports': modules, 'lockVerified': True}))
