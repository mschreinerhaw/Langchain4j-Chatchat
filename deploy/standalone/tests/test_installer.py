import importlib.util
import json
from pathlib import Path, PurePosixPath
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('installer',Path(__file__).resolve().parents[1]/'installer.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class InstallerTests(unittest.TestCase):
    def render(self,directory):
        app=m.Installation.__new__(m.Installation)
        app.host=PurePosixPath('/data/runtime-os-test');app.project='test-runtime';app.image='sha256:'+'a'*64
        app.secret={k:'generated-'+k for k in ['postgresPassword','apiDatabasePassword','mcpDatabasePassword','newsDatabasePassword','temporalPassword','redisPassword','searchPassword','codingSecret','frontendToken']}
        app.state={'httpPort':19400,'mcpPort':19401};app.compose=Path(directory)/'compose.json'
        app.images={k:{'reference':'test-'+k+':1','imageId':'sha256:'+'b'*64} for k in ['postgres','redis','search','silo','temporal','api','mcp','news','node','studio','gateway','frontend','daemon']}
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

if __name__=='__main__':unittest.main()
