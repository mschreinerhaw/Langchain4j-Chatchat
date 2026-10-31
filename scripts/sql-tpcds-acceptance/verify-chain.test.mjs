import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { verifyChain } from './verify-chain.mjs';
const read = p => JSON.parse(fs.readFileSync(new URL('../../' + p, import.meta.url)));
function fixture() {
  return [read('docs/sql-chain-revalidation/sql-run/source-chain.json'),
    read('docs/sql-chain-revalidation/sql-run/agent-result.json'),
    read('docs/sql-chain-revalidation/sql-run/observations-att-1-516a6b2c-96fc-4485-bcc8-6fcd408c7737.json'),
    read('docs/sql-chain-revalidation/fresh-jdbc.json')];
}
test('recorded real SQL invocations reconcile to independent JDBC', () => {
  const proof = verifyChain(...fixture());
  assert.equal(proof.status, 'SQL_CHAIN_EXACT_MATCH');
  assert.deepEqual(proof.results.map(r => [r.rows, r.comparedFields]), [[6, 42], [11, 77]]);
});
const cases = [
  ['PARTIAL task with good rows', ([, t]) => { t.canonicalState = 'PARTIAL'; }, /finish successfully/],
  ['discovery without execution', a => { a[2] = []; }, /actual invocation/],
  ['unexecuted draft', ([s]) => { s.templates.yearly.definition.testEvidence.success = false; }, /database trial/],
  ['source changed after generation', ([s]) => { s.templates.yearly.file.content += '\nSELECT 1;'; }, /differs from model output/],
  ['trial from another source version', ([s]) => { s.templates.monthly.definition.testEvidence.source.fileVersion++; }, /different saved source/],
  ['MCP schema drift', ([s]) => { s.templates.monthly.descriptor.inputSchema.required = []; }, /Schema differs/],
  ['revoked Agent binding', ([s]) => { s.agent.boundMcpToolNames = []; }, /not bound/],
  ['old attempt observations', ([, t]) => { t.attemptId = 'another-attempt'; }, /another attempt/],
  ['different date scope despite matching returned rows', ([,, o]) => { o.find(v => v.metadata?.resolvedInput?.start_date).metadata.resolvedInput.start_date = '2000-01-01'; }, /parameters differ/],
  ['changed database value', ([,,, j]) => { j.baselines.yearly.rows[0].total_net_paid = '0'; }, /total_net_paid/],
  ['non-independent baseline', ([,,, j]) => { j.baselines.monthly.source = 'TEMPLATE_PREVIEW'; }, /DIRECT_MYSQL_JDBC/],
];
for (const [name, mutate, message] of cases) test('rejects ' + name, () => {
  const data = fixture(); mutate(data);
  assert.throws(() => verifyChain(...data), message);
});
