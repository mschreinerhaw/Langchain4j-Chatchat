"""Validate real Compose expansion and Nginx syntax without starting applications."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
GATEWAY_IMAGE = os.environ.get('GATEWAY_TEST_IMAGE', 'nginx:1.27.5-alpine')

class ApplicationDeploymentTest(unittest.TestCase):
    def test_mysql_infrastructure_accepts_external_secret_file_references(self):
        env = dict(os.environ)
        env.update({name + '_FILE': '/private/' + name.lower() for name in
                    ('MYSQL_ROOT_PASSWORD', 'MYSQL_API_PASSWORD', 'MYSQL_MCP_PASSWORD',
                     'MYSQL_NEWS_PASSWORD', 'REDIS_PASSWORD', 'OPENSEARCH_ADMIN_PASSWORD')})
        rendered = json.loads(subprocess.check_output(['docker', 'compose', '-f', str(ROOT.parent / 'docker-compose.yaml'),
                                                      'config', '--format', 'json'], env=env, text=True))
        self.assertIn('mysql', rendered['services'])
        self.assertNotIn('postgresql', rendered['services'])
        self.assertEqual(rendered['secrets']['mysql_news_password']['file'], '/private/mysql_news_password')

    def test_temporal_renders_file_secret_without_inline_database_password(self):
        env = dict(os.environ, TEMPORAL_DATABASE_PASSWORD_FILE='/private/temporal-database')
        rendered = json.loads(subprocess.check_output(['docker', 'compose', '-f', str(ROOT.parents[1] / 'temporal/docker-compose.yml'),
                                                      'config', '--format', 'json'], env=env, text=True))
        self.assertNotIn('POSTGRES_PASSWORD', rendered['services']['temporal-postgresql']['environment'])
        self.assertNotIn('POSTGRES_PWD', rendered['services']['temporal']['environment'])
        self.assertEqual(rendered['services']['temporal']['pull_policy'], 'never')

    def config(self, explicit=False, missing_api_data=False):
        with tempfile.TemporaryDirectory(prefix='runtime-compose-') as directory:
            stage = Path(directory)
            (stage / 'config').mkdir()
            for name in ['api.env', 'mcp.env', 'news.env']:
                (stage / 'config' / name).write_text('')
            values = {'STAGE_ROOT': stage.as_posix(), 'API_IMAGE': 'test-api', 'MCP_IMAGE': 'test-mcp',
                      'NEWS_IMAGE': 'test-news', 'NODE_IMAGE': 'test-node', 'NODE_PROXY_IMAGE': 'nginx:1.27.5-alpine',
                      'MODEL_CONFIG_FILE': '/config/model.yml', 'MCP_HOST_MAC_FILE': '/sys/class/net/ens192/address',
                      'API_INTERNAL_KEY_FILE': '/keys/api', 'MCP_INTERNAL_KEY_FILE': '/keys/mcp',
                      'CODING_AGENT_SHARED_SECRET_FILE': '/keys/shared', 'CODING_AGENT_NODE_SECRET_FILE': '/keys/node',
                      'CODING_MONITOR_TOKEN_FILE': '/keys/monitor',
                      'NEWS_DATA_DIR': '/existing/news/data', 'NEWS_CONFIG_DIR': '/existing/news/config'}
            for key in ['POSTGRESQL_ADMIN_PASSWORD_FILE', 'POSTGRESQL_API_PASSWORD_FILE',
                        'POSTGRESQL_MCP_PASSWORD_FILE', 'REDIS_PASSWORD_FILE',
                        'OPENSEARCH_ADMIN_PASSWORD_FILE', 'BOOTSTRAP_ADMIN_PASSWORD_FILE', 'NEWS_DATABASE_PASSWORD_FILE', 'NEWS_INTERNAL_KEY_FILE', 'INTERNAL_CREDENTIAL_ENCRYPTED_FILE', 'WSA_SECRET_ID_FILE', 'WSA_SECRET_KEY_FILE']:
                values[key]='/keys/'+key.lower()
            values.update(OPENSEARCH_CERTS_DIR='/keys/opensearch-certs',
                          OPENSEARCH_TRUSTSTORE_FILE='/keys/opensearch-truststore.p12')
            values.update(API_DATA_DIR=stage.as_posix()+'/api/data', MCP_DATA_DIR=stage.as_posix()+'/mcp/data')
            if explicit:
                values.update(API_DATA_DIR='/existing/api/data', MCP_DATA_DIR='/existing/mcp/data',
                              MCP_PYTHON_WORKSPACE_ROOT='/legacy/python-assets', MCP_PYTHON_DATA_ROOT='/legacy/user-data')
            if missing_api_data:values.pop('API_DATA_DIR')
            envfile=stage/'deployment.env'
            envfile.write_text('\n'.join(k+'='+v for k,v in values.items()))
            env=os.environ.copy()
            env.pop('API_DATA_DIR',None)
            for key in values:env.pop(key,None)
            result=subprocess.run(['docker','compose','--env-file',str(envfile),'-f',str(ROOT/'compose.yaml'),
                                   'config','--format','json'],env=env,capture_output=True,text=True,check=True)
            return json.loads(result.stdout),stage.as_posix()

    def test_existing_paths_and_private_news(self):
        doc,_=self.config(True);services=doc['services']
        for service,expected in [('api','/existing/api/data'),('mcp','/existing/mcp/data'),('news','/existing/news/data')]:
            mount=next(v for v in services[service]['volumes'] if v['target']=='/srv/app/data')
            self.assertEqual(mount['source'],expected)
            self.assertFalse(mount['bind'].get('create_host_path',False))
        news=services['news'];self.assertFalse(news.get('ports'))
        self.assertEqual(services['mcp']['environment']['CHATCHAT_MCP_NEWS_RUNTIME_BASE_URL'],'http://news:8091')
        self.assertEqual(services['mcp']['environment']['CHATCHAT_MCP_PYTHON_WORKSPACE_ROOT'],'/legacy/python-assets')
        self.assertTrue(any(v['source']==v['target']=='/legacy/python-assets' for v in services['mcp']['volumes']))
        self.assertTrue(next(v for v in news['volumes'] if v['target']=='/srv/app/config')['read_only'])

    def test_missing_explicit_api_data_directory_fails_instead_of_selecting_an_empty_store(self):
        with self.assertRaises(subprocess.CalledProcessError):self.config(missing_api_data=True)

    def test_stage_defaults_remain_persistent(self):
        doc,stage=self.config()
        for service in ['api','mcp']:
            mount=next(v for v in doc['services'][service]['volumes'] if v['target']=='/srv/app/data')
            self.assertEqual(mount['source'],stage+'/'+service+'/data')
        api=doc['services']['api']
        self.assertEqual(api['environment']['CHATCHAT_CHAT_DETAIL_STORE_PATH'],'/srv/app/data/chat-rocksdb')
        self.assertEqual(api['environment']['CHATCHAT_CHAT_DETAIL_STORE_EXTERNAL_TEXT_ENABLED'],'true')
        self.assertEqual(api['environment']['CHATCHAT_CHAT_DETAIL_STORE_EXTERNAL_TEXT_MIGRATE_LEGACY_ON_READ'],'false')
        self.assertEqual(api['stop_grace_period'],'1m0s')

    def test_coding_callback_authentication_uses_configured_dns_and_dynamic_addresses(self):
        doc,_=self.config()
        api=doc['services']['api']; worker=doc['services']['coding-agent']
        self.assertEqual(api['environment']['CHATCHAT_CODING_CLINE_ALLOWED_PEER_HOSTNAMES'],'coding-agent')
        self.assertEqual(api['environment']['CHATCHAT_CODING_CLINE_ALLOWED_PEER_ADDRESSES'],'')
        self.assertEqual(worker['environment']['RUNTIME_CODING_CALLBACK_URL'],'http://api:8080')
        self.assertEqual(worker['environment']['RUNTIME_CODING_ALLOWED_CALLBACK_HOSTS'],'api')
        for service in (api,worker):
            self.assertFalse((service['networks']['coding'] or {}).get('ipv4_address'))

    def test_nginx_accepts_gateway_config(self):
        subprocess.run(['docker','run','--rm','-i','--add-host','api:127.0.0.1','--add-host','mcp:127.0.0.1',
                        '--entrypoint','sh',GATEWAY_IMAGE,'-c',
                        'cat > /tmp/test.conf && exec nginx -t -c /tmp/test.conf'],
                       input=(ROOT/'public-gateway.conf').read_text(),text=True,capture_output=True,check=True)

    def test_dependencies_are_persistent_authenticated_and_isolated_from_coding(self):
        doc,_=self.config();services=doc['services']
        for name in ['postgresql','redis','opensearch']:
            service=services[name]
            self.assertIn('healthcheck',service)
            self.assertEqual(set(service['networks']),{'control'})
            self.assertEqual(service['ports'][0]['host_ip'],'127.0.0.1')
            self.assertTrue(any(v['type']=='volume' for v in service['volumes']))
        for name,db in [('api','live_runtime_api'),('mcp','live_runtime_mcp')]:
            service=services[name]
            self.assertEqual(service['depends_on']['postgresql']['condition'],'service_healthy')
            self.assertEqual(service['depends_on']['opensearch-security-init']['condition'],
                             'service_completed_successfully')
            self.assertIn('classpath:datasource-postgresql.yml',service['environment']['SPRING_CONFIG_IMPORT'])
            self.assertIn('configtree:/run/secrets/',service['environment']['SPRING_CONFIG_IMPORT'])
            url=service['environment']['CHATCHAT_'+name.upper()+'_POSTGRESQL_URL']
            self.assertEqual(url,'jdbc:postgresql://postgresql:5432/'+db)
            self.assertTrue(any(s['target']=='CHATCHAT_'+name.upper()+'_POSTGRESQL_PASSWORD' for s in service['secrets']))
        self.assertNotIn('DISABLE_SECURITY_PLUGIN',services['opensearch']['environment'])
        self.assertIn('.runtime-schema-initialized',services['postgresql']['healthcheck']['test'][-1])
        self.assertEqual(set(services['coding-agent']['networks']),{'coding'})
        health_command=services['coding-agent']['healthcheck']['test'][-1]
        self.assertIn('RUNTIME_CODING_SHARED_SECRET_FILE',health_command)
        self.assertIn("Authorization:'Bearer '+secret",health_command)

    def test_startup_graph_has_no_cycle_and_health_conditions_have_probes(self):
        doc,_=self.config();services=doc['services'];visited=set();active=set()
        def visit(name):
            self.assertNotIn(name,active,'Circular dependency at '+name)
            if name in visited:return
            active.add(name)
            for dependency,condition in services[name].get('depends_on',{}).items():
                self.assertIn(dependency,services)
                if condition['condition']=='service_healthy':
                    self.assertIn('healthcheck',services[dependency])
                visit(dependency)
            active.remove(name);visited.add(name)
        for name in services:visit(name)
        self.assertEqual(services['gateway']['depends_on']['api']['condition'],'service_healthy')
        self.assertEqual(services['node-proxy']['depends_on']['coding-agent']['condition'],'service_healthy')

    def test_node_proxy_follows_worker_dns_after_ip_changes_without_reload(self):
        prefix='coding-dns-test-'+uuid.uuid4().hex[:12]
        names=[prefix+suffix for suffix in ['-one','-two','-proxy','-reserved']]
        one,two,proxy,reserved=names
        image=os.environ.get('PYTHON_TEST_IMAGE','python:3.11-alpine')
        server="import http.server,sys\nclass Handler(http.server.BaseHTTPRequestHandler):\n def do_GET(self):\n  self.send_response(200);self.end_headers();self.wfile.write(sys.argv[1].encode())\nhttp.server.HTTPServer(('0.0.0.0',3100),Handler).serve_forever()\n"
        def run(*args):return subprocess.check_output(['docker',*args],text=True,stderr=subprocess.PIPE).strip()
        def response(expected):
            until=time.monotonic()+20
            while time.monotonic()<until:
                try:
                    text=run('run','--rm','--network','container:'+proxy,'--entrypoint','python',image,'-c',
                        "import urllib.request;print(urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:8791/',timeout=3).read().decode())")
                    if text==expected:return
                except subprocess.CalledProcessError:pass
                time.sleep(0.2)
            self.fail('Proxy did not resolve the current worker identity')
        run('network','create','--internal',prefix)
        try:
            # Let Docker choose an unused subnet, then make it explicit for the address reservation below.
            subnet=run('network','inspect','-f','{{(index .IPAM.Config 0).Subnet}}',prefix)
            run('network','rm',prefix);run('network','create','--internal','--subnet',subnet,prefix)
            run('run','-d','--name',one,'--network',prefix,'--network-alias','coding-agent','--entrypoint','python',image,'-u','-c',server,'one')
            old=run('inspect','-f','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',one)
            run('create','--name',proxy,'--network',prefix,'--entrypoint','nginx',GATEWAY_IMAGE,'-g','daemon off;')
            run('cp',str(ROOT/'node-proxy.conf'),proxy+':/etc/nginx/nginx.conf');run('start',proxy)
            response('one')
            run('rm','-f',one)
            # Reserve the released address so the recreated worker is guaranteed to receive another IP.
            run('run','-d','--name',reserved,'--network',prefix,'--ip',old,'--entrypoint','python',image,'-c','import time;time.sleep(60)')
            run('run','-d','--name',two,'--network',prefix,'--network-alias','coding-agent','--entrypoint','python',image,'-u','-c',server,'two')
            self.assertNotEqual(old,run('inspect','-f','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',two))
            response('two')
        finally:
            for name in names:subprocess.run(['docker','rm','-f',name],capture_output=True)
            subprocess.run(['docker','network','rm',prefix],capture_output=True,check=True)

    def test_redirects_keep_the_external_port(self):
        container=subprocess.check_output(['docker','create','--publish','127.0.0.1::8080',
            '--add-host','api:127.0.0.1','--add-host','mcp:127.0.0.1','--entrypoint','nginx',
            GATEWAY_IMAGE,'-g','daemon off;'],text=True).strip()
        self.assertTrue(len(container)==64 and all(c in '0123456789abcdef' for c in container))
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs):return None
        try:
            subprocess.run(['docker','cp',str(ROOT/'public-gateway.conf'),container+':/etc/nginx/nginx.conf'],check=True,capture_output=True)
            subprocess.run(['docker','start',container],check=True,capture_output=True)
            address=subprocess.check_output(['docker','port',container,'8080'],text=True).strip()
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
            for path,expected in [('/admin/index.html','/'),('/admin','/'),('/admin/','/'),('/favicon.ico','/lingdong-insight-mark.svg')]:
                for attempt in range(20):
                    try:
                        opener.open('http://'+address+path,timeout=2)
                        self.fail('Expected redirect')
                    except urllib.error.HTTPError as response:
                        self.assertEqual(response.code,302)
                        self.assertEqual(response.headers['Location'],expected)
                        break
                    except urllib.error.URLError:
                        if attempt==19:raise
                        time.sleep(.25)
        finally:
            subprocess.run(['docker','rm','-f',container],check=True,capture_output=True)

if __name__=='__main__':unittest.main()
