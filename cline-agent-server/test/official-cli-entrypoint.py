"""Authenticate the unmodified CLI into task-local state, then exec it.

This host adapter supplies no analysis program, generated data or Agent loop.
The mounted connection contains a task proxy token, never the provider key.
"""
import json, os, subprocess, sys
from pathlib import Path

binary='/opt/official-cline/bin/cline'
connection=json.loads(Path('/run/connection.json').read_text())
config=Path('/state/.cline')
result=subprocess.run([binary,'auth','--provider','openai','--apikey',connection['apiKey'],
    '--modelid',connection['model'],'--baseurl',connection['baseUrl'],'--config',str(config)],
    capture_output=True,text=True)
if result.returncode:
    sys.stderr.write((result.stdout+result.stderr).replace(connection['apiKey'],'[REDACTED]'))
    raise SystemExit(result.returncode)
settings=config/'data/settings'
protocol=connection.get('protocol','cli')
subprocess.run(['python','/run/configure-official-cli.py',str(settings/'providers.json'),connection['outputTokenPolicy']],check=True,
    stdout=sys.stderr if protocol=='acp' else None)
(settings/'global-settings.json').write_text(json.dumps({'autoUpdateEnabled':False,'telemetryOptOut':True}))
if protocol=='acp':
    # The official ACP runtime stays alive across successful completed turns.
    # Genuine Java review feedback can reach the same Session without process
    # restart, Agent reimplementation or replay of a failed task.
    os.environ.update(CLINE_PROVIDER='openai-compatible',CLINE_API_KEY=connection['apiKey'],CLINE_MODEL=connection['model'],CLINE_SESSION_BACKEND_MODE='local')
    os.execv(binary,[binary,'--config',str(config),'--data-dir',str(config/'data'),
        '--acp','--auto-approve','false','--thinking','low','--cwd',connection['workspace']])
if protocol!='cli':raise ValueError('Unknown official CLI control protocol')
os.execv(binary,[binary,'--config',str(config),'--data-dir',str(config/'data'),
    '--provider','openai','--model',connection['model'],'--thinking','low','--retries','6',
    '--json','--yolo','--auto-approve','true','--timeout','1200','--cwd',connection['workspace'],
    Path('/run/objective.txt').read_text()])
