import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

// Independent of the generated Python. These fixed fixtures contain no quoted
// CSV cells; this intentionally is not a general-purpose CSV import service.
const read = async path => JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
const samples = (await read('../../coding-agent-node/test/fixtures/analysis-regressions.json')).cases;
const csv = text => {
  assert(!text.includes('"'));
  const [header, ...rows] = text.trim().split(/\r?\n/);
  const columns = header.split(',');
  return rows.map(line => {
    const cells = line.split(','); assert.equal(cells.length, columns.length);
    return Object.fromEntries(columns.map((column, i) => [column, cells[i]]));
  });
};
const cents = text => {
  assert.match(text, /^\d+(?:\.\d{1,2})?$/);
  const [whole, fraction = ''] = text.split('.');
  return BigInt(whole) * 100n + BigInt((fraction + '00').slice(0, 2));
};
const money = n => `${n / 100n}.${(n % 100n).toString().padStart(2, '0')}`;
const sum = rows => rows.reduce((total, row) => total + cents(row.amount), 0n);
const computed = {};
for (const sample of samples) {
  if (sample.id === 'simple-sales-csv') {
    const rows = csv(sample.files['sales.csv']);
    computed[sample.id] = { rows: String(rows.length), missingRegion: String(rows.filter(row => !row.region).length),
      totalUnits: String(rows.reduce((total, row) => total + Number(row.units), 0)), totalAmount: money(sum(rows)),
      eastAmount: money(sum(rows.filter(row => row.region === '华东'))) };
  } else if (sample.id === 'multi-file-explicit-join') {
    const customers = csv(sample.files['customers.csv']), orders = csv(sample.files['orders.csv']);
    const regions = new Map(customers.map(row => [row.customer_id, row.region]));
    assert.equal(regions.size, customers.length);
    const unmatched = orders.filter(row => !regions.has(row.customer_id));
    computed[sample.id] = { orders: String(orders.length), unmatchedOrders: String(unmatched.length), totalAmount: money(sum(orders)),
      eastAmount: money(sum(orders.filter(row => regions.get(row.customer_id) === '华东'))), unmatchedAmount: money(sum(unmatched)) };
  } else if (sample.id === 'exact-repair-preserves-results') computed[sample.id] = { totalAmount: money(sum(csv(sample.files['sales.csv']))) };
  else computed[sample.id] = { rows: String(csv(sample.files['account.csv']).length) }; // Unknown financial meanings: no amount aggregate.
  assert.deepEqual(computed[sample.id], sample.metrics);
}
assert.deepEqual(computed, (await read('./numeric-oracles.json')).oracles);
for (const observation of await read('./observations.json'))
  assert.deepEqual(Object.fromEntries(observation.numericalChecks.map(check => [check.id, check.actual])), computed[observation.caseId]);
console.log(JSON.stringify({ status: 'PASSED', cases: Object.keys(computed).length, arithmetic: 'INTEGER_CENTS', scope: 'INDEPENDENT_FIXED_FIXTURE_NUMERIC_CHECKS' }));
