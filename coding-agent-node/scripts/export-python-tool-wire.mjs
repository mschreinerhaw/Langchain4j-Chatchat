// Export the actual SDK -> Runtime callback tool schema for Java compatibility checks.
import { writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { createCodingRun } from '../src/runner.js';
import { createRuntimeModel } from '../src/runtime-model.js';
const output = process.argv[2];
assert(output, 'An output path is required');
const task = { schemaVersion: 'runtime_os.coding.v3', taskId: 'wire-check',
  scope: { tenantId: 'fixture', userId: 'fixture', runId: 'wire-check' },
  objective: { instruction: 'Check tool protocol without business data or execution' },
  inputSnapshotRef: 'fixture', outputContract: { requiredFiles: ['rows.jsonl'], resultFormat: 'JSON_LINES' },
  authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: ['c' + 'a'.repeat(63)], networkPolicy: 'NONE' },
  runtimeContext: { codingLifecycle: { schemaVersion: 'runtime_os.code_lifecycle.v1', patchOnlyAfterFirst: true } },
  budget: { maxModelCalls: 1, maxModelTokens: 4096, deadlineAt: new Date(Date.now() + 10000).toISOString() } };
let tools;
const gateway = { async reserveModel() {}, async settleModel() {}, async finish() {},
  async model(_task, request) { tools = request.tools; return { text: 'Protocol inspected.', toolCalls: [] }; },
  async executePython() { throw new Error('Protocol inspection cannot execute Python'); } };
await createCodingRun(task, gateway, { nativeSdk: true, model: createRuntimeModel(gateway, task, 0, { nativeSdk: true }) }).run();
assert(tools?.some(tool => tool.name === 'python_execute'));
await writeFile(output, JSON.stringify(tools, null, 2));
console.log(JSON.stringify({ toolCount: tools.length, output }));
