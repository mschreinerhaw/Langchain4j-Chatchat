"""Apply exactly the reviewed no-start cancellation DDL to the isolated MCP database.
The source hash is mandatory. No production database/configuration is modified.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess

ROOT=pathlib.Path('/opt/runtime-os-docker-stage')
EVIDENCE=ROOT/'evidence/shared-input-20261019'
MIGRATION='V20261019_03__mcp_python_unadmitted_cancellation.sql'


def migrate(expected):
    source=EVIDENCE/MIGRATION
    content=source.read_bytes()
    if not re.fullmatch('[0-9a-f]{64}',expected) or hashlib.sha256(content).hexdigest()!=expected:
        raise RuntimeError('Reviewed migration hash mismatch')
    config=json.loads((ROOT/'compose.yaml').read_text())
    env=config['services']['mcp']['environment']
    env=dict(v.split('=',1) for v in env) if isinstance(env,list) else env
    match=re.fullmatch(r'jdbc:postgresql://[^/]+/([^?]+)(?:\?.*)?',env.get('CHATCHAT_MCP_POSTGRESQL_URL',''))
    if not match or match[1]!='runtime_os_acceptance_mcp':
        raise RuntimeError('Refusing a database outside the isolated MCP test environment')
    database=match[1]
    command=['docker','exec','-i','-u','postgres','postgres','psql','-X','-v','ON_ERROR_STOP=1','-d',database]
    def query(sql):return subprocess.check_output(command+['-At','-c',sql],text=True,stderr=subprocess.PIPE).strip()
    owner=query("select tableowner from pg_tables where schemaname='public' and tablename='mcp_python_managed_fence'")
    if not owner or len(owner)>63 or any(ord(c)<32 for c in owner):raise RuntimeError('Existing MCP journal owner is unavailable')
    before=set(query("select tablename from pg_tables where schemaname='public' order by tablename").splitlines())
    statement='SET LOCAL ROLE "'+owner.replace('"','""')+'";\n'+content.decode('utf-8')
    subprocess.run(command+['--single-transaction','-f','-'],input=statement,text=True,check=True,capture_output=True)
    after=set(query("select tablename from pg_tables where schemaname='public' order by tablename").splitlines())
    if 'mcp_python_managed_cancellation' not in after or before-after:raise RuntimeError('MCP schema postcondition failed')
    if query("select tableowner from pg_tables where schemaname='public' and tablename='mcp_python_managed_cancellation'")!=owner:
        raise RuntimeError('Cancellation journal ownership differs from the existing MCP journal')
    report={'status':'PASSED','scope':'Reviewed additive DDL in the isolated MCP database only','database':database,
        'migration':MIGRATION,'sha256':expected,'existingOwnerPreserved':True,'addedTables':sorted(after-before),
        'productionSwitched':False}
    (EVIDENCE/'schema-migration.json').write_text(json.dumps(report,indent=2))
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--sha256',required=True)
    print(json.dumps(migrate(parser.parse_args().sha256)))
