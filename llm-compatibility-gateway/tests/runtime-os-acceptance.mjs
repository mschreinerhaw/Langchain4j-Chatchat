import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const [scenarioPath, evidenceDirectory] = process.argv.slice(2);
const api = process.env.TEST_RUNTIME_URL;
const token = process.env.TEST_RUNTIME_TOKEN;
assert.ok(scenarioPath && evidenceDirectory && api && token, 'Set TEST_RUNTIME_URL and TEST_RUNTIME_TOKEN and pass scenario JSON and evidence directory');
const scenario = JSON.parse(await readFile(scenarioPath, 'utf8'));
assert.ok(Array.isArray(scenario.fileIds) && scenario.fileIds.length && scenario.modelName && scenario.instruction);
assert.ok(['COMPLETED', 'FAILED', 'CANCELLED', 'BUDGET_EXCEEDED'].includes(scenario.expectedStatus));
const origin = new URL(api).origin;
const requestId = 'gateway-' + randomUUID();
const evidence = join(evidenceDirectory, requestId);
await mkdir(evidence, { recursive:true, mode:0o700 });
const write = (name, value) => writeFile(join(evidence, name + '.json'), JSON.stringify(value, null, 2), { mode:0o600 });
const headers = { authorization:'Bearer ' + token, 'content-type':'application/json' };
async function request(path, options) {
 const response = await fetch(new URL(path, origin), { headers, signal:AbortSignal.timeout(15_000), redirect:'error', ...options });
 if (!response.ok) throw Error(`${options?.method ?? 'GET'} ${path} returned HTTP ${response.status}`);
 return response;
}
const json = async (path, options) => (await (await request(path, options)).json()).data;
const cases = '/api/v1/coding/tasks';
try {
 const submitted = await json(cases, { method:'POST', body:JSON.stringify({ requestId, fileIds:scenario.fileIds,
  instruction:scenario.instruction, modelName:scenario.modelName, artifactTypes:scenario.artifactTypes ?? ['REPORT','RESULT'] }) });
 const { taskId, runId } = submitted;
 const task = cases + '/runs/' + encodeURIComponent(runId) + '/' + encodeURIComponent(taskId);
 const monitor = '/api/v1/coding-monitor/tasks/' + encodeURIComponent(runId) + '/' + encodeURIComponent(taskId);
 await write('dispatch', { requestId, taskId, runId, expectedStatus:scenario.expectedStatus, startedAt:new Date().toISOString() });
 const timeout = Date.now() + Number(scenario.timeoutMs ?? 900_000);
 let cancelled = false;
 let result;
 const stateChanges = [];
 do {
  result = await json(task);
  if (stateChanges.at(-1)?.status !== result.status) {
   stateChanges.push({ at:new Date().toISOString(), status:result.status });
   await write('task-states', stateChanges);
  }
  if (result.status === 'RUNNING' && scenario.expectedStatus === 'CANCELLED' && !cancelled) {
   const live = await json(monitor);
   if (live.modelReceipts?.some(receipt => receipt.requestId && !receipt.finishedAt)) {
    await write('cancel-request', { at:new Date().toISOString(), requestId, taskId });
    await json(task + '/cancel', { method:'POST' });
    cancelled = true;
   }
  }
  if (['COMPLETED','FAILED','CANCELLED','BUDGET_EXCEEDED','PARTIAL'].includes(result.status)) break;
  await delay(500);
 } while (Date.now() < timeout);
 const delivery = await json(task + '/delivery');
 const trace = await json(monitor);
 await write('outcome', result);
 await write('delivery', delivery);
 await write('monitor', trace);
 assert.equal(result.status,scenario.expectedStatus);
 if (scenario.expectedStatus === 'CANCELLED') {
  assert.ok(cancelled && !result.executionControl.reconciliationRequired);
  assert.ok(trace.modelReceipts.some(receipt => receipt.requestId), 'No broker request ID for Gateway correlation');
 } else {
  assert.ok(trace.modelReceipts.some(receipt => receipt.gatewayRequestId && receipt.requestId),
   'No model receipt paired with a Gateway request ID');
 }
 const hashes = [];
 let verifiedResult = false;
 for (const artifact of delivery.artifacts ?? []) {
  const downloaded = await request('/api/v1/data-science/user-assets/' + encodeURIComponent(artifact.id) + '/content');
  const bytes = Buffer.from(await downloaded.arrayBuffer());
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  assert.equal(bytes.length,artifact.size);
  assert.equal(sha256,artifact.sha256);
  if (scenario.expectedResult && artifact.fileName === scenario.expectedResult.fileName) {
   assert.deepEqual(JSON.parse(bytes.toString('utf8')), scenario.expectedResult.data);
   verifiedResult = true;
  }
  hashes.push({ id:artifact.id, fileName:artifact.fileName, byteLength:bytes.length, sha256 });
 }
 await write('verified-artifacts', hashes);
 if (result.status === 'COMPLETED') assert.ok(hashes.length, 'Completed analysis must deliver a verified artifact');
 if (scenario.expectedResult) assert.ok(verifiedResult, 'Expected result artifact was not returned');
 console.log(JSON.stringify({ requestId, runId, taskId, status:result.status, evidence }));
} catch (error) {
 await write('failure', { at:new Date().toISOString(), message:error.message });
 throw error;
}
