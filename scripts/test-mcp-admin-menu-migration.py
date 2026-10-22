"""Verify PostgreSQL menu DDL under a least-privilege owner in a rolled-back isolated schema."""
import argparse
import json
from pathlib import Path
import subprocess
import uuid
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--migration',type=Path,required=True)
parser.add_argument('--database',required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
name='mcp_menu_test_'+uuid.uuid4().hex[:12]
sql=f'''BEGIN;
CREATE ROLE {name} NOLOGIN;
CREATE SCHEMA {name} AUTHORIZATION {name};
SET search_path TO {name};
SET ROLE {name};
CREATE TABLE mcp_admin_user(username varchar(128) PRIMARY KEY);
INSERT INTO mcp_admin_user VALUES ('fixture');
'''+args.migration.read_text()+args.migration.read_text()+f'''
INSERT INTO mcp_admin_menu_grant(id,username,menu_key,granted_by,created_at)
VALUES ('fixture','fixture','adminLoginAudits','fixture',CURRENT_TIMESTAMP);
DO $$ BEGIN
 IF (SELECT count(*) FROM mcp_admin_menu_grant)<>1 THEN RAISE EXCEPTION 'missing row'; END IF;
 IF (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='mcp_admin_menu_grant'::regclass)<>'{name}' THEN RAISE EXCEPTION 'owner mismatch'; END IF;
END $$;
UPDATE mcp_admin_menu_grant SET menu_key='adminUsers' WHERE id='fixture';
DELETE FROM mcp_admin_menu_grant WHERE id='fixture';
RESET ROLE;
ROLLBACK;
'''
subprocess.run(['docker','exec','-i','-u','postgres','postgres','psql','-v','ON_ERROR_STOP=1','-d',args.database],input=sql,text=True,check=True,capture_output=True)
report={'repeatableAdditiveMigration':True,'tableOwnedByApplicationRole':True,'applicationSelectInsertUpdateDelete':True,'isolatedSchemaAndRoleRolledBack':True}
args.output.write_text(json.dumps(report,indent=2));print(json.dumps(report))
