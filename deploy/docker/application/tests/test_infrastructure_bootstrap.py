"""Opt-in Linux/root Docker acceptance using only a disposable Compose project.

RUNTIME_INFRA_DOCKER_TESTS=1 python3 -m unittest discover -s deploy/docker/application/tests -p test_infrastructure_bootstrap.py
Requires the three pinned infrastructure images already installed; never starts application services.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.environ.get('RUNTIME_INFRA_DOCKER_TESTS') == '1', 'opt-in disposable Docker acceptance')
class InfrastructureBootstrapTest(unittest.TestCase):
    def test_fresh_schema_credentials_persistence_and_security_init(self):
        self.assertEqual(os.getuid(), 0, 'Fixture needs Linux root to set UID 1000 file permissions')
        project = 'runtime-infra-test-' + uuid.uuid4().hex[:12]
        with tempfile.TemporaryDirectory(prefix=project) as folder:
            stage = Path(folder)
            config = stage/'config'; config.mkdir()
            certificates = stage/'certs'; certificates.mkdir()
            for name in ['api','mcp','news']:(config/(name+'.env')).write_text('')
            password_files = {}
            for name in ['postgresql-admin','postgresql-api','postgresql-mcp','postgresql-news','redis','opensearch-admin','bootstrap-admin','internal','coding']:
                p = stage/name;p.write_text(secrets.token_hex(32));p.chmod(0o640);os.chown(p,0,1000)
                password_files[name] = p
            postgres_image=os.environ.get('RUNTIME_TEST_POSTGRESQL_IMAGE','postgres:16.9')
            postgres_gid=int(subprocess.check_output(['docker','run','--rm','--network','none','--entrypoint','id',postgres_image,'-g','postgres'],text=True).strip())
            for name in ['postgresql-admin','postgresql-api','postgresql-mcp','postgresql-news']:
                os.chown(password_files[name],0,postgres_gid)
            def run(*args, **options):
                return subprocess.run(args, text=True, capture_output=True, check=True, timeout=60, **options)
            # Private fixture CA; no host trust store or production certificates are changed.
            run('openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=fixture-ca',
                '-keyout',str(stage/'ca.key'),'-out',str(certificates/'root-ca.pem'))
            for name,subject in [('node','chatchat-opensearch'),('admin','chatchat-opensearch-admin')]:
                run('openssl','req','-new','-newkey','rsa:2048','-nodes','-subj','/CN='+subject,
                    '-keyout',str(certificates/(name+'-key.pem')),'-out',str(stage/(name+'.csr')))
                extension = stage/(name+'.ext')
                extension.write_text('basicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:opensearch,DNS:localhost,IP:127.0.0.1\n')
                run('openssl','x509','-req','-in',str(stage/(name+'.csr')),'-CA',str(certificates/'root-ca.pem'),
                    '-CAkey',str(stage/'ca.key'),'-CAcreateserial','-days','1','-extfile',str(extension),'-out',str(certificates/(name+'.pem')))
            certificates.chmod(0o750);os.chown(certificates,0,1000)
            for p in certificates.iterdir():p.chmod(0o640);os.chown(p,0,1000)
            values = dict(STAGE_ROOT=str(stage),API_IMAGE='unused-api',MCP_IMAGE='unused-mcp',NEWS_IMAGE='unused-news',
                NODE_IMAGE='unused-node',NODE_PROXY_IMAGE='nginx:1.27.5-alpine',API_DATA_DIR=str(stage/'api'),MCP_DATA_DIR=str(stage/'mcp'),
                NEWS_DATA_DIR=str(stage/'news'),NEWS_CONFIG_DIR=str(config),MODEL_CONFIG_FILE=str(stage/'unused.yml'),
                MCP_HOST_MAC_FILE='/etc/hostname',OPENSEARCH_CERTS_DIR=str(certificates),OPENSEARCH_TRUSTSTORE_FILE=str(stage/'unused.p12'),
                API_INTERNAL_KEY_FILE=str(password_files['internal']),MCP_INTERNAL_KEY_FILE=str(password_files['internal']),
                CODING_AGENT_SHARED_SECRET_FILE=str(password_files['coding']),CODING_AGENT_NODE_SECRET_FILE=str(password_files['coding']),
                CODING_MONITOR_TOKEN_FILE=str(password_files['internal']),
                POSTGRESQL_ADMIN_PASSWORD_FILE=str(password_files['postgresql-admin']),POSTGRESQL_API_PASSWORD_FILE=str(password_files['postgresql-api']),
                POSTGRESQL_MCP_PASSWORD_FILE=str(password_files['postgresql-mcp']),REDIS_PASSWORD_FILE=str(password_files['redis']),
                OPENSEARCH_ADMIN_PASSWORD_FILE=str(password_files['opensearch-admin']),BOOTSTRAP_ADMIN_PASSWORD_FILE=str(password_files['bootstrap-admin']),
                NEWS_DATABASE_PASSWORD_FILE=str(password_files['postgresql-news']),NEWS_INTERNAL_KEY_FILE=str(password_files['internal']),INTERNAL_CREDENTIAL_ENCRYPTED_FILE=str(password_files['internal']),WSA_SECRET_ID_FILE=str(password_files['internal']),WSA_SECRET_KEY_FILE=str(password_files['internal']),POSTGRESQL_PORT='0',REDIS_PORT='0',OPENSEARCH_PORT='0')
            for service in ['POSTGRESQL','REDIS','OPENSEARCH']:
                if os.environ.get('RUNTIME_TEST_'+service+'_IMAGE'):
                    values[service+'_IMAGE']=os.environ['RUNTIME_TEST_'+service+'_IMAGE']
            env = config/'deployment.env';env.write_text('\n'.join(k+'='+v for k,v in values.items()))
            # Test-only: avoid changing the shared host's vm.max_map_count. Production requires 262144.
            override = stage/'test-only.json'
            override.write_text(json.dumps({'services':{
                'opensearch':{'command':['opensearch','-Enode.store.allow_mmap=false']},
                'postgresql':{'environment':{'PGDATA':'/var/lib/postgresql/data'}}}}))
            command = ['docker','compose','--project-name',project,'--env-file',str(env),'-f',str(ROOT/'compose.yaml'),'-f',str(override)]
            def dc(*args, check=True):
                return subprocess.run(command+list(args),capture_output=True,text=True,timeout=420,check=check)
            try:
                dc('up','-d','--wait','--wait-timeout','300','postgresql','redis','opensearch')
                dc('up','-d','opensearch-security-init')
                self.assertEqual(dc('wait','opensearch-security-init',check=False).returncode,0)
                # Correct secrets authenticate; another application's database is inaccessible.
                for user,db in [('chatchat_api','live_runtime_api'),('chatchat_mcp','live_runtime_mcp')]:
                    secret = '/run/secrets/postgresql_'+('api' if user.endswith('api') else 'mcp')+'_password'
                    result=dc('exec','-T','postgresql','bash','-ec',
                        'export PGPASSWORD="$(cat '+secret+')"; exec psql -h 127.0.0.1 -U '+user+' -d '+db+' -Atc "select count(*)>0 from information_schema.tables where table_schema=\'public\'"')
                    self.assertEqual(result.stdout.strip(),'t')
                denied=dc('exec','-T','postgresql','bash','-ec',
                    'export PGPASSWORD="$(cat /run/secrets/postgresql_api_password)"; exec psql -h 127.0.0.1 -U chatchat_api -d live_runtime_mcp -Atc "select 1"',check=False)
                self.assertNotEqual(denied.returncode,0)
                dc('exec','-T','postgresql','psql','-U','chatchat_api','-d','live_runtime_api','-c',
                    'create table deploy_fixture_probe(id int primary key); insert into deploy_fixture_probe values (7);')
                dc('restart','postgresql')
                dc('up','-d','--wait','postgresql')
                self.assertEqual(dc('exec','-T','postgresql','psql','-U','chatchat_api','-d','live_runtime_api','-Atc','select id from deploy_fixture_probe').stdout.strip(),'7')
                # Existing initialization is idempotent and a wrong secret cannot reset security.
                dc('run','--rm','--no-deps','opensearch-security-init')
                original = password_files['opensearch-admin'].read_text()
                password_files['opensearch-admin'].write_text(secrets.token_hex(32))
                try:
                    self.assertNotEqual(dc('run','--rm','--no-deps','opensearch-security-init',check=False).returncode,0)
                finally:password_files['opensearch-admin'].write_text(original)
                dc('run','--rm','--no-deps','opensearch-security-init')
            except Exception as error:
                # Logs stay on the private test host, never include generated secret values.
                text=(getattr(error,'stderr','') or '')+'\n'+dc('logs','--no-color','--tail','80',check=False).stdout
                for p in password_files.values():text=text.replace(p.read_text(),'[redacted]')
                print(text)
                raise
            finally:
                # Names/volumes are created solely by this random test project.
                dc('down','--volumes','--remove-orphans',check=True)


if __name__ == '__main__':
    unittest.main()
