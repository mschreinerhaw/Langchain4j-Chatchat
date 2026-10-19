"""Merge archived conversation history, without replacing current sessions or configuration.

Run on the Docker host. Dry run is the default. The source must be an inactive archive.
File bodies (API data directory and external OpenSearch index) must be retained separately.
No database credentials or message bodies are printed. Backups contain private data.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

TABLES = ('chat_session', 'chat_message_index', 'conversation_summary', 'agent_task_latest',
          'agent_execution_event', 'agent_runtime_checkpoint', 'ui_artifact', 'workspace_resource_origin')
TERMINAL = "('SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED')"


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, **kwargs)
    if result.returncode:
        # PostgreSQL errors can contain row contents. Keep details in the private backup only.
        raise RuntimeError('Database operation failed; inspect private migration logs')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--postgres-container', default='postgres')
    parser.add_argument('--backup-dir', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    for value in (args.source, args.target, args.postgres_container):
        if not re.fullmatch(r'[a-zA-Z0-9_-]+', value):
            raise ValueError('Expected a database/container identifier')
    if args.source == args.target:
        raise ValueError('Source and destination must differ')
    os.umask(0o077)
    base = ['docker', 'exec', '-u', 'postgres', args.postgres_container]

    def sql(db, query):
        return run(base + ['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-d', db, '-c', query], text=True).strip()

    if sql(args.source, f"SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN {TERMINAL}") != '0':
        raise RuntimeError('The history source contains active tasks; archive it before migration')
    if sql(args.target, f"SELECT count(*) FROM agent_task_latest WHERE canonical_state IS NULL OR canonical_state NOT IN {TERMINAL}") != '0':
        raise RuntimeError('Wait for current tasks before merging history')
    columns = "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='%s' ORDER BY column_name"
    counts = {}
    for table in TABLES:
        if sql(args.source, columns % table) != sql(args.target, columns % table):
            raise RuntimeError('Schema mismatch for ' + table + '; apply compatible migrations first')
        counts[table] = {name: int(sql(db, 'SELECT count(*) FROM ' + table))
                         for name, db in (('source', args.source), ('before', args.target))}
    # A duplicate identifier may be retained only when it denotes the same owner/object.
    for table, fields in {
        'chat_session': ('session_id','tenant_id','user_id'),
        'chat_message_index': ('message_id','tenant_id','user_id','session_id','rocks_key'),
        'agent_task_latest': ('task_id','tenant_id','user_id','session_id'),
        'ui_artifact': ('artifact_id','tenant_id','task_id'),
        'workspace_resource_origin': ('id','tenant_id','resource_type','resource_id','workspace_id'),
    }.items():
        query = "SELECT coalesce(json_agg(json_build_array(" + ','.join(fields) + ")), '[]') FROM " + table
        source = {row[0]: row[1:] for row in json.loads(sql(args.source, query))}
        target = {row[0]: row[1:] for row in json.loads(sql(args.target, query))}
        if any(source[key] != target[key] for key in source.keys() & target.keys()):
            raise RuntimeError('Conflicting object ownership/reference in ' + table + '; merge refused')
    print(json.dumps({'action': 'APPLY' if args.apply else 'DRY_RUN', 'tables': counts}))
    if not args.apply:
        return
    directory = args.backup_dir.resolve() / ('history-' + time.strftime('%Y%m%d-%H%M%S'))
    directory.mkdir(parents=True, mode=0o700, exist_ok=False)
    selections = [item for table in TABLES for item in ('--table', 'public.' + table)]
    with (directory / 'target-before.dump').open('wb') as backup:
        result = subprocess.run(base + ['pg_dump', '-Fc', '-d', args.target] + selections, stdout=backup, stderr=subprocess.PIPE)
        (directory / 'backup.stderr').write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError('Backup failed; destination was not modified')
    with (directory / 'source.sql').open('wb') as archive:
        result = subprocess.run(base + ['pg_dump', '-d', args.source, '--data-only', '--column-inserts',
                                       '--on-conflict-do-nothing', '--no-owner', '--no-privileges'] + selections,
                                stdout=archive, stderr=subprocess.PIPE)
        (directory / 'export.stderr').write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError('History export failed; destination was not modified')
    # One transaction: failure rolls back the entire import. Existing primary keys win.
    with (directory / 'source.sql').open('rb') as archive, (directory / 'import.log').open('wb') as log:
        result = subprocess.run(['docker', 'exec', '-i', '-u', 'postgres', args.postgres_container,
                                 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-d', args.target],
                                stdin=archive, stdout=log, stderr=log)
        if result.returncode:
            raise RuntimeError('Merge rolled back; inspect the private import.log')
    for table in TABLES:
        counts[table]['after'] = int(sql(args.target, 'SELECT count(*) FROM ' + table))
        counts[table]['inserted'] = counts[table]['after'] - counts[table]['before']
    report = {'action': 'MERGED', 'sourceUnchanged': True, 'existingRowsOverwritten': False,
              'backupDirectory': str(directory), 'tables': counts,
              'bodyStorageVerificationRequired': True}
    (directory / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
