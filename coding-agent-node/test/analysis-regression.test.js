import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { checkObservation } from '../scripts/check-analysis-regression.mjs';
import { prepareProgram } from '../src/code-repair.js';
import { createHash } from 'node:crypto';

const { cases } = JSON.parse(await readFile(new URL('./fixtures/analysis-regressions.json', import.meta.url), 'utf8'));
for (const sample of cases) test(`fixed regression gate: ${sample.id} rejects missing evidence, wrong completion and unknown usage`, () => {
  const observation = { caseId: sample.id, taskId: 'fixture-only-not-live', reportSha256: 'a'.repeat(64),
    taskStatus: sample.expectedTaskStatus ?? 'SUCCEEDED', codingStatus: 'COMPLETED', analysisStatus: sample.expectedStatus,
    deliveryChecks: sample.deliverables.map(id => ({ id, passed: true, evidenceRef: 'fixture:check' })),
    numericalChecks: Object.entries(sample.metrics).map(([id, actual]) => ({ id, actual, evidenceRef: 'fixture:calculation' })),
    measurements: { durationMs: 100, modelCalls: 2, inputTokens: 1000, outputTokens: 100, pythonExecutions: 1 } };
  assert.equal(checkObservation(sample, observation).passed, true);
  assert.equal(checkObservation(sample, { ...observation, taskStatus: observation.taskStatus === 'SUCCEEDED' ? 'PARTIAL' : 'SUCCEEDED' }).passed, false);
  assert.equal(checkObservation(sample, { ...observation, analysisStatus: sample.expectedStatus === 'COMPLETE' ? 'PARTIAL' : 'COMPLETE' }).passed, false);
  assert.equal(checkObservation(sample, { ...observation, deliveryChecks: [] }).passed, false);
  assert.equal(checkObservation(sample, { ...observation, numericalChecks: [] }).passed, false);
  assert.equal(checkObservation(sample, { ...observation, measurements: { ...observation.measurements, inputTokens: null } }).passed, false);
});

test('repair fixture modifies only the supplied exact anchor and preserves validated money text', () => {
  const sample = cases.find(sample => sample.initialBrokenSource);
  const source = prepareProgram({ repair: { baseCodeSha256: createHash('sha256').update(sample.initialBrokenSource).digest('hex'),
    edits: [sample.repair] } }, sample.initialBrokenSource);
  assert.equal(source, "total = '30.50'\ntitle = '区域金额'\n");
});

test('full repair source keeps computation and immutable file verification unchanged', async () => {
  const sample = cases.find(sample => sample.sourceFixture);
  const source = await readFile(new URL('./fixtures/' + sample.sourceFixture, import.meta.url), 'utf8');
  const repaired = prepareProgram({ repair: { baseCodeSha256: createHash('sha256').update(source).digest('hex'), edits: [sample.repair] } }, source);
  assert.equal(repaired, source.replace(sample.repair.oldText, sample.repair.newText));
  assert.match(repaired, /sum\(\(Decimal\(row\['amount'\]\)/);
  assert.match(repaired, /hashlib.sha256\(source_bytes\)/);
  assert.doesNotMatch(repaired, /30\.50/);
  assert.equal(repaired.includes(String.fromCharCode(92, 92, 110)), false, 'Report and JSONL must write newlines rather than literal backslash-n text');
});
