"""No Docker or database is contacted; verify release migration ordering inputs fail closed."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('deployer', Path(__file__).with_name('deploy-studio-remediation.py'))
deployer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployer)


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.content = b'ALTER TABLE ds_domain_skill ADD COLUMN IF NOT EXISTS compilation_error VARCHAR(2000);\n'
        (self.root / 'schema.sql').write_bytes(self.content)
        self.manifest = {'databaseMigration': {'file': 'schema.sql', 'sha256': hashlib.sha256(self.content).hexdigest()}}
        self.execute = Mock()

    def test_existing_release_without_migration_does_not_execute_sql(self):
        self.assertIsNone(deployer.apply_database_migration(self.root, {}, self.execute))
        self.execute.assert_not_called()

    def test_exact_pinned_migration_runs_transactionally_and_stops_on_error(self):
        self.assertEqual(deployer.apply_database_migration(self.root, self.manifest, self.execute), self.manifest['databaseMigration']['sha256'])
        args, kwargs = self.execute.call_args
        self.assertIn('--single-transaction', args[0])
        self.assertIn('ON_ERROR_STOP=1', args[0])
        self.assertEqual(kwargs['input'], self.content)
        self.assertTrue(kwargs['check'])

    def test_changed_sql_cannot_execute(self):
        (self.root / 'schema.sql').write_bytes(b'changed')
        with self.assertRaises(AssertionError): deployer.apply_database_migration(self.root, self.manifest, self.execute)
        self.execute.assert_not_called()

    def test_missing_sql_cannot_execute(self):
        (self.root / 'schema.sql').unlink()
        with self.assertRaises(AssertionError): deployer.apply_database_migration(self.root, self.manifest, self.execute)
        self.execute.assert_not_called()

    def test_traversal_cannot_select_different_file(self):
        self.manifest['databaseMigration']['file'] = '../schema.sql'
        with self.assertRaises(AssertionError): deployer.apply_database_migration(self.root, self.manifest, self.execute)
        self.execute.assert_not_called()

    def test_database_error_propagates_before_any_container_replacement(self):
        self.execute.side_effect = RuntimeError('database rejected migration')
        with self.assertRaisesRegex(RuntimeError, 'rejected'): deployer.apply_database_migration(self.root, self.manifest, self.execute)


if __name__ == '__main__': unittest.main()
