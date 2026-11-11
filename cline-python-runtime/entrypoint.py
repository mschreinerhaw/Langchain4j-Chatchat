"""Configure a mounted connection using official auth, then execute the real CLI.

This process does not dispatch models/tools, interpret data, retry tasks, review
results or decide completion. Cline owns the complete execution loop.
"""
import json
import os
from pathlib import Path
import sys
import subprocess

connection = os.environ.get('CLINE_CONNECTION_FILE')
if connection:
    value = json.loads(Path(connection).read_text(encoding='utf-8'))
    if not all(isinstance(value.get(k), str) and value[k] for k in ['provider', 'apiKey', 'model', 'baseUrl']):
        raise ValueError('Expected provider, apiKey, model and baseUrl in the mounted connection file')
    configured = subprocess.run(['cline', 'auth', '--provider', value['provider'], '--apikey', value['apiKey'],
        '--modelid', value['model'], '--baseurl', value['baseUrl'], '--config', str(Path.home()/'.cline')],
        capture_output=True, text=True)
    if configured.returncode:
        message = (configured.stdout + configured.stderr).replace(value['apiKey'], '[REDACTED]')
        sys.stderr.write(message)
        raise SystemExit(configured.returncode)
    settings = Path.home() / '.cline/data/settings'
    settings.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Pin the installed executable; disable unrelated telemetry and self-update.
    defaults = settings / 'global-settings.json'
    if not defaults.exists():
        defaults.write_text(json.dumps({'autoUpdateEnabled': False, 'telemetryOptOut': True}), encoding='utf-8')

command = sys.argv[1:] or ['cline']
os.execvp(command[0], command)
