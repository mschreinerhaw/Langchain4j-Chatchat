import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Gate normalized observations from actual artifacts and independent calculations.
// This does not execute a model or certify the reviewer's semantic judgments.
export function checkObservation(sample, observation) {
  const failures = [];
  if (observation.caseId !== sample.id) failures.push('case identity');
  if (observation.taskStatus !== 'SUCCEEDED' || observation.codingStatus !== 'COMPLETED') failures.push('execution completion');
  if (observation.analysisStatus !== sample.expectedStatus) failures.push('requirement completion status');
  if (!observation.taskId || !observation.reportSha256?.match(/^[a-f0-9]{64}$/)) failures.push('artifact identity');
  for (const id of sample.deliverables) {
    const check = observation.deliveryChecks?.find(check => check.id === id);
    if (check?.passed !== true || !check.evidenceRef) failures.push('deliverable: ' + id);
  }
  for (const [id, expected] of Object.entries(sample.metrics)) {
    const check = observation.numericalChecks?.find(check => check.id === id);
    // Exact decimal strings avoid floating-point tolerances for money.
    if (check?.actual !== expected || !check.evidenceRef) failures.push('metric: ' + id);
  }
  for (const key of ['durationMs','modelCalls','inputTokens','outputTokens','pythonExecutions']) {
    if (!Number.isSafeInteger(observation.measurements?.[key]) || observation.measurements[key] < 0)
      failures.push('missing measurement: ' + key);
  }
  return { caseId: sample.id, passed: failures.length === 0, failures };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (!process.argv[2]) throw new Error('Usage: node scripts/check-analysis-regression.mjs observations.json');
  const samples = JSON.parse(await readFile(new URL('../test/fixtures/analysis-regressions.json', import.meta.url), 'utf8'));
  const observations = JSON.parse(await readFile(process.argv[2], 'utf8'));
  const results = samples.cases.map(sample => checkObservation(sample,
    observations.find(observation => observation.caseId === sample.id) ?? {}));
  console.log(JSON.stringify({ scope: 'NORMALIZED_OBSERVATION_GATE_NOT_A_LIVE_MODEL_RUN', results }, null, 2));
  process.exitCode = results.every(result => result.passed) ? 0 : 1;
}
