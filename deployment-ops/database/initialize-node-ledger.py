"""Optional offline node-ledger initialization. The agent also initializes on first boot."""
import argparse
from pathlib import Path
import os
import sqlite3

p=argparse.ArgumentParser();p.add_argument('--state',type=Path,required=True);p.add_argument('--agent-stopped',action='store_true');args=p.parse_args()
if not args.agent_stopped:raise SystemExit('Stop only the node agent first and pass --agent-stopped; workloads must keep running')
root=args.state.resolve()/'manager';root.mkdir(parents=True,exist_ok=True)
path=root/'ledger.sqlite'
with sqlite3.connect(path) as db:
    if db.execute('PRAGMA user_version').fetchone()[0]>1:raise SystemExit('Newer ledger schema detected; downgrade is forbidden')
    db.executescript(Path(__file__).with_name('001-node-ledger.sql').read_text())
if os.name!='nt':root.chmod(0o700);path.chmod(0o600)
print('Node ledger v1 initialized; no Docker or business database was contacted')
