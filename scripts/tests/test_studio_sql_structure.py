"""Real SQLGlot structural checks, using explicit simulated catalog evidence only."""
import test_studio_sql_references as references
import unittest


class StudioStructureTest(unittest.TestCase):
    parse = references.StudioReferencesTest.parse
    def tables(self):
        return [dict(tableName='accounts', bindingId='b', columns=[{'name': 'id'}, {'name': 'amount'}],
                     primaryKeys=['id'], primaryKeyStatus='COLLECTED_VISIBLE'),
                dict(tableName='trades', bindingId='b', columns=[{'name': 'id'}, {'name': 'account_id'}, {'name': 'amount'}, {'name': 'day'}],
                     primaryKeys=['id'], primaryKeyStatus='COLLECTED_VISIBLE',
                     partitionKeys=['day'], partitionKeyStatus='COLLECTED_VISIBLE',
                     foreignKeys=[dict(constraintRef='fk', column='account_id', referencedTable='accounts',
                                       referencedSchema=None, referencedDatabase=None, referencedColumn='id', source='JDBC_IMPORTED_KEYS')])]

    def review(self, sql, tables=None):
        result = self.parse(sql, tables=self.tables() if tables is None else tables)
        self.assertTrue(result['valid'], result)
        self.assertFalse(result['sqlExecuted'])
        self.assertEqual(result['businessValidation'], 'NOT_EVALUATED')
        return result, {d['code'] for d in result['structuralDiagnostics']}

    def test_declared_fk_does_not_make_parent_aggregation_safe(self):
        result, codes = self.review('SELECT SUM(a.amount) FROM accounts a JOIN trades t ON a.id=t.account_id')
        self.assertEqual(result['relationships'][0]['status'], 'DECLARED_FOREIGN_KEY')
        self.assertIn('JOIN_FANOUT_AGGREGATION_RISK', codes)
        self.assertIn('MISSING_PARTITION_FILTER', codes)

    def test_child_aggregate_to_complete_parent_key_and_partition_filter(self):
        result, codes = self.review("SELECT SUM(t.amount) FROM trades t JOIN accounts a ON a.id=t.account_id WHERE t.day >= '2026-01-01'")
        self.assertEqual(codes, set())
        self.assertFalse(result['structureReviewRequired'])
        self.assertEqual(result['grains'][0]['businessGrain'], 'UNCONFIRMED')

    def test_wrong_join_is_inference_not_foreign_key_evidence(self):
        result, codes = self.review('SELECT a.id FROM accounts a JOIN trades t ON a.id=t.id')
        self.assertEqual(result['relationships'][0]['status'], 'INFERRED')
        self.assertIn('JOIN_RELATION_UNCONFIRMED', codes)

    def test_count_rows_can_fan_out_but_distinct_count_is_not_a_duplicate_sum(self):
        _, codes = self.review('SELECT COUNT(*) FROM accounts a JOIN trades t ON a.id=t.account_id')
        self.assertIn('JOIN_FANOUT_AGGREGATION_RISK', codes)
        _, codes = self.review('SELECT COUNT(DISTINCT a.id), MAX(a.amount) FROM accounts a JOIN trades t ON a.id=t.account_id')
        self.assertNotIn('JOIN_FANOUT_AGGREGATION_RISK', codes)

    def test_or_clause_cannot_certify_fk_or_partition_filter(self):
        result, codes = self.review("SELECT a.id FROM accounts a JOIN trades t ON a.id=t.account_id OR a.amount=t.amount WHERE t.day='2026-01-01' OR t.id=1")
        self.assertIn('JOIN_RELATION_UNCONFIRMED', codes)
        self.assertIn('MISSING_PARTITION_FILTER', codes)
        self.assertNotIn('DECLARED_FOREIGN_KEY', str(result['relationships']))

    def test_composite_primary_key_requires_all_columns(self):
        tables = self.tables(); tables[0]['primaryKeys'] = ['id', 'amount']
        _, codes = self.review('SELECT SUM(t.amount) FROM trades t JOIN accounts a ON a.id=t.account_id', tables)
        self.assertIn('JOIN_FANOUT_AGGREGATION_RISK', codes)

    def test_incomplete_composite_foreign_key_is_not_verified(self):
        tables = self.tables()
        tables[1]['foreignKeys'].append(dict(tables[1]['foreignKeys'][0], column='amount', referencedColumn='amount'))
        result, _ = self.review('SELECT t.id FROM trades t JOIN accounts a ON a.id=t.account_id', tables)
        self.assertEqual(result['relationships'][0]['status'], 'INFERRED')

    def test_redacted_or_uncollected_keys_cannot_prove_uniqueness(self):
        tables = self.tables(); tables[0]['primaryKeyStatus'] = 'REDACTED'
        _, codes = self.review('SELECT SUM(t.amount) FROM trades t JOIN accounts a ON a.id=t.account_id', tables)
        self.assertIn('JOIN_FANOUT_AGGREGATION_RISK', codes)

    def test_same_table_names_in_other_bindings_cannot_certify_relationship(self):
        tables = self.tables(); tables[0]['bindingId'] = 'another'
        result, _ = self.review('SELECT t.id FROM trades t JOIN accounts a ON a.id=t.account_id', tables)
        self.assertEqual(result['relationships'][0]['status'], 'INFERRED')

    def test_no_key_name_heuristics_and_no_fake_database_execution(self):
        tables = self.tables()
        for table in tables:
            table.pop('primaryKeys'); table.pop('primaryKeyStatus')
        _, codes = self.review('SELECT SUM(t.amount) FROM trades t JOIN accounts a ON a.id=t.account_id', tables)
        self.assertIn('JOIN_FANOUT_AGGREGATION_RISK', codes)


if __name__ == '__main__':
    unittest.main()
