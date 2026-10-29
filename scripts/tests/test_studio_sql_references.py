"""Real pinned SQLGlot checks; no database connection and no SQL execution."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'chatchat-api/src/main/resources/sql-toolchain/validate.py'
spec = importlib.util.spec_from_file_location('studio_validator', SCRIPT)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class StudioReferencesTest(unittest.TestCase):
    def parse(self, sql, dialect='postgresql', tables=None):
        return validator.validate({'dialect': dialect, 'files': [{'path': 'query.sql', 'content': sql}],
                                   'schema': {} if tables is None else {'tables': tables}})

    def test_cte_subquery_aliases_and_join_only_report_physical_tables(self):
        result = self.parse('WITH t AS (SELECT id FROM public.actual) SELECT t.id FROM t JOIN (SELECT id FROM public.other) q ON t.id=q.id')
        self.assertTrue(result['valid'])
        self.assertEqual([r['tableName'] for r in result['references']], ['actual', 'other'])

    def test_unused_cte_alias_is_not_a_physical_reference(self):
        result = self.parse('WITH t AS (SELECT id FROM actual) SELECT id FROM t')
        self.assertEqual([r['tableName'] for r in result['references']], ['actual'])

    def test_catalog_schema_table_and_quoted_case(self):
        result = self.parse('SELECT "ID" FROM "catalog"."Mixed"."CamelTable"', 'postgresql')
        self.assertEqual(result['references'], [{'tableName': 'CamelTable', 'database': 'catalog', 'schema': 'Mixed', 'quoted': True}])

    def test_oracle_unquoted_identifiers_are_folded(self):
        result = self.parse('select id from app.tzrt_hyxxls', 'oracle')
        self.assertEqual(result['references'][0]['tableName'], 'TZRT_HYXXLS')
        self.assertEqual(result['references'][0]['schema'], 'APP')

    def test_all_existing_dialects_keep_reference_parsing(self):
        for dialect in ['spark', 'hive', 'postgresql', 'mysql', 'oracle', 'tsql', 'trino', 'ansi']:
            with self.subTest(dialect=dialect):
                self.assertTrue(self.parse('SELECT id FROM actual', dialect)['valid'])

    def test_schema_supplement_does_not_disable_field_validation(self):
        table = {'tableName': 'actual', 'columns': [{'name': 'id'}]}
        self.assertTrue(self.parse('SELECT id FROM actual', tables=[table])['valid'])
        self.assertFalse(self.parse('SELECT hidden FROM actual', tables=[table])['valid'])
        self.assertFalse(self.parse('SELECT id FROM missing', tables=[table])['valid'])

    def test_view_is_resolved_as_a_physical_metadata_object(self):
        self.assertEqual(self.parse('SELECT v.id FROM reporting_view v')['references'][0]['tableName'], 'reporting_view')

    def test_locally_declared_temporary_table_is_not_a_database_reference(self):
        result = self.parse('CREATE TEMP TABLE tmp (id INT); SELECT id FROM tmp')
        self.assertTrue(result['valid'])
        self.assertEqual(result['references'], [])
        self.assertFalse(self.parse('SELECT id FROM #undeclared', 'tsql')['valid'])


if __name__ == '__main__':
    unittest.main()
