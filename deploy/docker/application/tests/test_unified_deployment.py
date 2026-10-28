import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
module = importlib.util.spec_from_file_location('runtime_deploy', ROOT / 'runtime-deploy.py')
deploy = importlib.util.module_from_spec(module);module.loader.exec_module(deploy)


class UnifiedDeploymentTest(unittest.TestCase):
    def spec(self):
        value = json.loads((ROOT / 'runtime-deployment.example.json').read_text())
        value['compose_env_file'] = 'unused'
        return value

    def test_existing_postgresql_and_mysql_are_distinct_supported_engines(self):
        deploy.validate_spec(self.spec())

    def test_mismatched_url_and_automatic_empty_database_are_rejected(self):
        for url in ('jdbc:mysql://db/existing', 'jdbc:postgresql://db/existing?createDatabaseIfNotExist=true'):
            spec = self.spec();spec['databases']['api']['url'] = url
            with self.assertRaises(ValueError):deploy.validate_spec(spec)

    def test_existing_h2_requires_an_explicit_file(self):
        spec = self.spec();spec['databases']['news'] = {'engine': 'h2'}
        with self.assertRaises(ValueError):deploy.validate_spec(spec)
        spec['databases']['news']['existing_file'] = '/existing/news.mv.db'
        deploy.validate_spec(spec)

    def test_full_install_needs_explicit_new_database_consent_and_matching_engines(self):
        spec = self.spec();spec['mode'] = 'full'
        with self.assertRaises(ValueError):deploy.validate_spec(spec)
        spec['new_installation'] = True
        spec['bootstrap_admin_password_file'] = '/private/database-admin'
        with self.assertRaises(ValueError):deploy.validate_spec(spec)
        spec['databases']['news'] = copy.deepcopy(spec['databases']['mcp'])
        for name, db in spec['databases'].items():
            db['url'] = 'jdbc:postgresql://postgresql:5432/' + {'api':'live_runtime_api','mcp':'live_runtime_mcp','news':'chatchat_news'}[name]
            db['username'] = 'chatchat_' + name
        deploy.validate_spec(spec)

    def test_embedded_temporal_is_not_mistaken_for_durable_persistence(self):
        spec = self.spec();spec['temporal']['mode'] = 'embedded'
        with self.assertRaises(ValueError):deploy.validate_spec(spec)
        spec['allow_ephemeral_temporal'] = True;deploy.validate_spec(spec)

    def test_render_preserves_data_mounts_and_networks_without_starting_infrastructure(self):
        spec = self.spec();spec['require_image_ids'] = False
        original = {'services': {name: {'image': 'local-' + name, 'environment': {},
            'volumes': [{'type':'bind','source':'/business/'+name,'target':'/srv/app/data','bind':{'create_host_path':False}}],
            'depends_on': {'postgresql': {'condition': 'service_healthy'}}, 'secrets': []} for name in deploy.APPLICATIONS},
            'networks': {'control': {}, 'coding': {'internal': True}}, 'secrets': {}}
        original['services']['postgresql'] = {'image': 'local-postgres'}
        original['services']['news']['extra_hosts'] = ['host.docker.internal:host-gateway']
        original['services']['news']['volumes'].append({'type': 'bind', 'source': '/old-ca', 'target': '/run/runtime-truststore.p12'})
        spec['extra_hosts'] = {'news': {'search.internal': '192.0.2.1'}}
        with tempfile.TemporaryDirectory() as folder, patch.object(deploy, 'read_env', return_value={}), \
                patch.object(deploy, 'command', return_value=json.dumps(original)) as command:
            rendered = deploy.normalize(spec, Path(folder))
            self.assertNotIn('postgresql', rendered['services'])
            for name in deploy.APPLICATIONS:
                self.assertEqual(rendered['services'][name]['volumes'][0], original['services'][name]['volumes'][0])
                self.assertEqual(rendered['services'][name]['pull_policy'], 'never')
                self.assertNotIn('postgresql', rendered['services'][name]['depends_on'])
            self.assertEqual(rendered['networks']['control']['name'], spec['control_network'])
            self.assertEqual(rendered['services']['news']['environment']['SPRING_DATASOURCE_DRIVER_CLASS_NAME'], 'com.mysql.cj.jdbc.Driver')
            self.assertEqual(rendered['services']['api']['environment']['SPRING_DATASOURCE_DRIVER_CLASS_NAME'], 'org.postgresql.Driver')
            self.assertEqual(rendered['services']['news']['environment']['CHATCHAT_RUNTIME_NEWS_OPENSEARCH_INSECURESSL'], 'false')
            self.assertEqual(command.call_count, 1)
            effective_environment = command.call_args.args[2]
            self.assertEqual(effective_environment['POSTGRESQL_API_PASSWORD_FILE'], spec['databases']['api']['password_file'])
            self.assertEqual(effective_environment['NEWS_DATABASE_PASSWORD_FILE'], spec['databases']['news']['password_file'])
            self.assertEqual(effective_environment['OPENSEARCH_ADMIN_PASSWORD_FILE'], spec['opensearch']['password_file'])
            self.assertIn('--profile', command.call_args.args[0])
            self.assertEqual(rendered['services']['news']['extra_hosts']['host.docker.internal'], 'host-gateway')
            self.assertEqual(rendered['services']['news']['extra_hosts']['search.internal'], '192.0.2.1')
            truststores = [m for m in rendered['services']['news']['volumes'] if m['target'] == '/run/runtime-truststore.p12']
            self.assertEqual(len(truststores), 1)
            self.assertEqual(truststores[0]['source'], spec['opensearch']['truststore_file'])
            self.assertFalse((Path(folder) / 'infrastructure.compose.json').exists())

    def test_database_probe_resolves_the_same_host_gateway_as_applications(self):
        db = self.spec()['databases']['api']
        with tempfile.TemporaryDirectory() as folder, patch.object(deploy, 'command', side_effect=['[]', '\n'.join(db['expected_tables'])]) as command:
            result = deploy.database_probe(db, 'existing-network', Path(folder))
            self.assertEqual(result['status'], 'SCHEMA_CONTRACT_VERIFIED')
            self.assertIn('host.docker.internal:host-gateway', command.call_args.args[0])
            self.assertNotIn('CREATE', command.call_args.args[0][-1])

    def test_business_probe_requires_real_result_fields(self):
        probe = {'argv': ['installation-owned-live-probe'], 'expect': {'hits': {'minimum': 1}}}
        names = ('runtime_start', 'mcp_registration', 'mcp_news', 'news_collection', 'news_search',
                 'financial_search', 'news_opensearch', 'external_web_search', 'local_fallback',
                 'runtime_web_search', 'cline_model', 'data_preservation')
        with tempfile.TemporaryDirectory() as folder, patch.object(deploy, 'command', return_value='{"hits":0}'):
            with self.assertRaises(ValueError):
                deploy.business_probes({'acceptance': dict.fromkeys(names, probe)}, Path(folder))
            self.assertTrue(all(p['status'] == 'FAILED' for p in json.loads((Path(folder) / 'business-acceptance.json').read_text()).values()))

    def test_migration_version_is_read_from_real_ledger_and_mismatch_blocks(self):
        db = self.spec()['databases']['api']
        db.update(migration_ledger='flyway', migration_version='20261028')
        with tempfile.TemporaryDirectory() as folder, patch.object(deploy, 'command', side_effect=['[]', '\n'.join(db['expected_tables']), '20261001']) as command:
            with self.assertRaisesRegex(ValueError, 'migration version differs'):
                deploy.database_probe(db, 'existing-network', Path(folder))
            self.assertIn('flyway_schema_history', command.call_args.args[0][-1])

    def test_missing_business_probe_blocks_success(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):deploy.business_probes({}, Path(folder))
            result = json.loads((Path(folder) / 'business-acceptance.json').read_text())
            self.assertEqual(len(result), 12)
            self.assertTrue(all(row['status'] == 'BLOCKED' for row in result.values()))

    def test_no_database_or_index_destructive_commands_in_entry(self):
        source = (ROOT / 'runtime-deploy.py').read_text()
        self.assertNotIn("'down'", source)
        self.assertNotIn("'DELETE'", source)
        self.assertNotIn('rmtree', source)


if __name__ == '__main__':unittest.main()
