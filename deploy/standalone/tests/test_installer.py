import importlib.util
import json
from pathlib import Path, PurePosixPath
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('installer',Path(__file__).resolve().parents[1]/'installer.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class InstallerTests(unittest.TestCase):
    def render(self,directory,portable=False,status='PREPARING'):
        app=m.Installation.__new__(m.Installation)
        app.host=PurePosixPath('/data/runtime-os-test');app.project='test-runtime';app.image='sha256:'+'a'*64
        app.secret={k:'generated-'+k for k in ['postgresPassword','apiDatabasePassword','mcpDatabasePassword','newsDatabasePassword','temporalPassword','redisPassword','searchPassword','codingSecret','frontendToken']}
        app.state={'httpPort':19400,'mcpPort':19401,'status':status};app.compose=Path(directory)/'compose.json'
        app.images={k:{'reference':'test-'+k+':1','imageId':'sha256:'+'b'*64} for k in ['postgres','redis','search','silo','temporal','api','mcp','news','node','studio','gateway','frontend','daemon']}
        if portable:
            for role in ['executor','recovery','task']:
                app.images[role]={'reference':'test-'+role+':1','imageId':'sha256:'+'c'*64}
        (Path(directory)/'data').mkdir()
        with patch.object(m,'ROOT',Path(directory)):app.render()
        return json.loads(app.compose.read_text())

    def test_all_host_files_are_inside_the_selected_installation(self):
        with tempfile.TemporaryDirectory() as tmp:config=self.render(tmp)
        for service in config['services'].values():
            for mount in service.get('volumes',[]):
                self.assertTrue(mount['source'].startswith('/data/runtime-os-test') or mount['source']=='/var/run/docker.sock',mount)
        serialized=json.dumps(config)
        self.assertNotIn('/opt/runtime-os/releases/',serialized)
        self.assertNotIn('192.168.',serialized)

    def test_all_services_use_offline_images_and_only_gateway_publishes_ports(self):
        with tempfile.TemporaryDirectory() as tmp:config=self.render(tmp)
        for name,service in config['services'].items():
            self.assertEqual(service['pull_policy'],'never')
            if name!='gateway':self.assertNotIn('ports',service)
        self.assertEqual(config['services']['gateway']['ports'],['19400:8080','19401:8090'])

    def test_database_data_and_workflow_data_are_persisted_independently(self):
        with tempfile.TemporaryDirectory() as tmp:config=self.render(tmp)
        first=config['services']['postgresql']['volumes'][0]
        second=config['services']['temporal-db']['volumes'][0]
        self.assertNotEqual(first['source'],second['source'])
        self.assertEqual(first['target'],'/var/lib/postgresql')
        for name in ['api','mcp','news']:
            self.assertEqual(config['services'][name]['environment']['SPRING_JPA_HIBERNATE_DDL_AUTO'],'validate')

    def test_temporal_accepts_local_health_checks_and_service_network_clients(self):
        with tempfile.TemporaryDirectory() as tmp:config=self.render(tmp)
        temporal=config['services']['temporal']
        self.assertEqual(temporal['environment']['BIND_ON_IP'],'0.0.0.0')
        self.assertIn('127.0.0.1',temporal['healthcheck']['test'][-1])
        news=json.loads(config['services']['news']['environment']['SPRING_APPLICATION_JSON'])
        self.assertEqual(news['chatchat.runtime.news.temporal.server-mode'],'external')

    def test_executor_daemon_is_separate_and_untrusted_workers_do_not_get_main_socket(self):
        with tempfile.TemporaryDirectory() as tmp:config=self.render(tmp)
        daemon=config['services']['executor-daemon']
        self.assertEqual(daemon['network_mode'],'none')
        for name in ['node1','node2','executor1','executor2','worker1','worker2']:
            for mount in config['services'][name]['volumes']:self.assertNotEqual(mount['source'],'/var/run/docker.sock')
        self.assertEqual(config['services']['executor1']['pid'],'host')
        self.assertTrue(config['networks']['control']['internal'])

    def test_private_installation_files_have_private_modes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'state.json';m.write_json(path,{'secret':'generated-at-install-time'})
            self.assertEqual(json.loads(path.read_text())['secret'],'generated-at-install-time')
            # Mode checks are meaningful on the Linux build/acceptance host.
            if m.os.name!='nt':self.assertEqual(path.stat().st_mode & 0o777,0o600)

    def test_portable_compose_pins_each_role_and_uses_published_process_bindings(self):
        with tempfile.TemporaryDirectory() as tmp:config=self.render(tmp,portable=True)
        services=config['services']
        self.assertEqual(services['node1']['image'],'test-node:1')
        self.assertEqual(services['recovery1']['image'],'test-recovery:1')
        self.assertEqual(services['executor-daemon']['network_mode'],'none')
        self.assertEqual(services['executor-daemon']['environment']['RUNTIME_OS_NATIVE_DAEMON_ROOT'],
                         '/data/runtime-os-test/d/strict')
        for role in ['api','worker1','worker2']:
            self.assertEqual(services[role]['environment']['CHATCHAT_CODING_ANALYSIS_ENABLED'],'true')
            self.assertEqual(services[role]['environment']['CHATCHAT_CODING_CLINE_BACKEND'],'native')
        for role in ['executor1','executor2','studio-executor']:
            self.assertEqual(services[role]['image'],'test-executor:1')
            self.assertEqual(services[role]['environment']['CLINE_EXECUTOR_PUBLISHED_DAEMON_BINDINGS'],'1')
            self.assertEqual(services[role]['depends_on']['executor-daemon']['condition'],'service_healthy')
        self.assertIn('seccomp=bootstrap/frontend-seccomp.json',services['frontend-worker']['security_opt'])

    def test_portable_application_requires_bootstrap_and_all_services_have_readiness_probes(self):
        with tempfile.TemporaryDirectory() as tmp:
            pending=self.render(tmp,portable=True)['services']
        self.assertEqual(pending['gateway']['profiles'],['application'])
        self.assertEqual(pending['executor1']['profiles'],['application'])
        self.assertNotIn('profiles',pending['postgresql'])
        with tempfile.TemporaryDirectory() as tmp:
            staged=self.render(tmp,portable=True,status='BOOTSTRAPPED')['services']
        self.assertTrue(all('profiles' not in service and 'healthcheck' in service for service in staged.values()))
        self.assertEqual(staged['gateway']['depends_on']['api']['condition'],'service_healthy')

    def test_portable_search_reaps_children_and_checks_https_before_security_bootstrap(self):
        with tempfile.TemporaryDirectory() as tmp:
            services = self.render(tmp, portable=True)['services']
        self.assertNotIn('opensearch-ready', services)
        search = services['opensearch']
        self.assertIs(search['init'], True)
        self.assertNotIn('profiles', search)
        self.assertEqual(search['healthcheck']['test'],
                         ['CMD', 'curl', '--silent', '--insecure', '--max-time', '4',
                          '--output', '/dev/null', 'https://127.0.0.1:9200'])
        self.assertEqual(search['healthcheck']['interval'], '5s')

if __name__=='__main__':unittest.main()
