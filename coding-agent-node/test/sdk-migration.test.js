import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { editProgram, sdkPrepareTurn } from '../src/sdk-adapter.js';
import { createCodingRun } from '../src/runner.js';
import { createRuntimeModel } from '../src/runtime-model.js';

const hash = source => createHash('sha256').update(source).digest('hex');
const task = () => ({ schemaVersion: 'runtime_os.coding.v1', taskId: 'native-task',
  scope: { tenantId: 't', userId: 'u', runId: 'r' }, inputSnapshotRef: 'immutable:input',
  objective: { instruction: 'Analyze the authorized immutable data' },
  authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: ['c' + 'a'.repeat(63)], networkPolicy: 'NONE' },
  outputContract: { requiredFiles: ['rows.jsonl'] },
  budget: { maxModelCalls: 5, deadlineAt: new Date(Date.now() + 10000).toISOString() } });

test('SDK compaction preserves portable tool pairs and error roles', async () => {
  const messages = [{ role: 'user', content: [{ type: 'text', text: 'authorized objective' }] }];
  for (let i = 0; i < 12; i++) messages.push(
    { role: 'assistant', content: [{ type: 'tool-call', toolCallId: 'op-' + i,
      toolName: 'python_execute', input: { source: 'x'.repeat(16000) } }] },
    { role: 'tool', content: [{ type: 'tool-result', toolCallId: 'op-' + i,
      toolName: 'python_execute', output: { diagnosis: 'failed ' + i }, isError: true }] });
  const result = await sdkPrepareTurn('task')({ agentId: 'task', iteration: 12, messages, tools: [],
    model: { info: { maxInputTokens: 16384, contextWindow: 20000 } }, systemPrompt: 'authorized',
    signal: new AbortController().signal });
  assert.ok(result); const pending = new Set();
  for (const message of result.messages) for (const part of message.content) {
    if (part.type === 'tool-call') pending.add(part.toolCallId);
    if (part.type === 'tool-result') {
      assert.equal(message.role, 'tool'); assert.equal(part.isError, true);
      assert.ok(pending.delete(part.toolCallId));
    }
  }
  assert.equal(pending.size, 0);
  assert.ok(result.messages.flatMap(m => m.content).some(p => p.type === 'tool-result'));
});

test('SDK editor rejects ambiguous, stale and partially invalid proposals atomically', async () => {
  const source = 'x = 1\ny = 1\n';
  const repair = edits => ({ repair: { baseCodeSha256: hash(source), edits } });
  assert.equal(await editProgram(repair([{ oldText: 'x = 1', newText: 'x = 2' }]), source), 'x = 2\ny = 1\n');
  await assert.rejects(editProgram(repair([{ oldText: '= 1', newText: '= 2' }]), source));
  await assert.rejects(editProgram(repair([{ oldText: 'x = 1', newText: 'x = 2' },
    { oldText: 'not present', newText: 'z = 1' }]), source));
  await assert.rejects(editProgram(repair([{ oldText: 'x = 1', newText: 'x = 2' }]), 'other'), /hash/);
  await assert.rejects(editProgram({ source, ...repair([{ oldText: 'x = 1', newText: 'x = 2' }]) }, source));
  assert.equal(source, 'x = 1\ny = 1\n');
});

test('a short uncompacted conversation still receives the actual latest executed source after a rejected proposal',async()=>{
  const source='value = 1\nprint(value)\n';
  const binding={source,version:{version:1,codeHash:hash(source)}};
  const prepare=sdkPrepareTurn('small-task',()=>binding);
  const input={agentId:'small-task',iteration:2,messages:[{role:'user',content:[{type:'text',text:'authorized objective'}]}],tools:[],
    model:{info:{contextWindow:128000,maxInputTokens:100000}},systemPrompt:'authorized',signal:new AbortController().signal};
  const first=await prepare(input);
  assert.match(first.messages.at(-1).content[0].text,/latest executed program/);
  assert(first.messages.at(-1).content[0].text.includes(JSON.stringify(binding)));
  const rejected={role:'assistant',content:[{type:'text',text:'REJECTED_SOURCE_NEVER_EXECUTED'}]};
  const again=await prepare({...input,iteration:3,messages:[...first.messages,rejected]});
  assert(again.messages.at(-1).content[0].text.includes(JSON.stringify(binding)));
  assert.equal(again.messages.filter(m=>m.content?.[0]?.text?.includes('Untrusted latest executed program')).length,1);
  assert.equal(input.messages.length,1);
});

test('authorized malformed tool JSON is repaired by the native loop without executing it', async () => {
  let calls = 0, executions = 0; const admitted = [], settled = [], runs = new Set();
  const contract = task();
  const gateway = { async reserveModel(_, value) { admitted.push(value.operationId); },
    async settleModel(_, value) { settled.push(value.operationId); }, async finish() {},
    async preparePython() { return { scriptVersion: 1, fileName: 'native_v1.py', scriptPath: '/workspace/scripts/native_v1.py',
      workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') }; },
    async executePython() { executions++; return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'evidence' }; },
    async model(_, request) {
      if (++calls === 1) return { error: 'invalid arguments', usage: { inputTokens: 1, outputTokens: 1 },
        toolCalls: [], nativeToolCalls: [{ id: 'bad', name: 'python_execute', arguments: '{"source":' }],
        modelResponseFeedback: { schemaVersion: 'runtime_os.coding.model_feedback.v1', diagnosticCode: 'MODEL_TOOL_ARGUMENTS_INVALID',
          repairAllowed: true, maxRepairAttempts: 2 } };
      if (calls === 2) {
        assert.match(JSON.stringify(request.messages), /invalid JSON/i);
        return { toolCalls: [{ id: 'valid', name: 'python_execute', input: { source: 'print(1)' } }],
          finishReason: 'TOOL_EXECUTION', usage: { inputTokens: 2, outputTokens: 2 } };
      }
      return { text: 'verified', toolCalls: [], finishReason: 'STOP', usage: { inputTokens: 3, outputTokens: 1 } };
    } };
  const run = createCodingRun(contract, gateway, { nativeSdk: true,
    model: createRuntimeModel(gateway, contract, 0, { nativeSdk: true }) });
  const result = await run.run(); runs.add(run.snapshot().runId);
  assert.equal(result.status, 'completed', result.error); assert.equal(executions, 1);
  assert.deepEqual(admitted, ['model-1', 'model-2', 'model-3']); assert.deepEqual(settled, admitted);
  assert.equal(runs.size, 1); assert.ok(run.snapshot().messages.some(m => m.role === 'tool'));
  assert.equal(run.events.some(e => e.type === 'MODEL_RESPONSE_REJECTED'), false);
});

test('native finish reasons reach the SDK without collapsing filter, cutoff or unknown', async () => {
  for (const [finishReason, expected] of [['LENGTH', 'max-tokens'], ['CONTENT_FILTER', 'content-filter'], ['OTHER', 'unknown']]) {
    const events = [];
    const model = createRuntimeModel({ async model() { return { finishReason, text: 'partial', toolCalls: [] }; } }, {}, 0, { nativeSdk: true });
    for await (const event of model.stream({ messages: [], tools: [] })) events.push(event);
    assert.equal(events.at(-1).reason, expected);
  }
});

test('managed Java model attempts remove separate admission and settlement RPCs', async () => {
  const contract = task(); let requests = 0;
  const gateway = { atomicModelAttempts: true,
    async reserveModel() { throw new Error('redundant admission RPC'); },
    async settleModel() { throw new Error('redundant settlement RPC'); },
    async finish() {}, async executePython() {},
    async model(_, request) {
      requests++; assert.equal(request.managedAttempt, true); assert.equal(request.operationId, 'model-1');
      return { text: 'No execution claimed', finishReason: 'STOP', usage: { inputTokens: 1, outputTokens: 1 } };
    },
  };
  const result = await createCodingRun(contract, gateway, { nativeSdk: true,
    model: createRuntimeModel(gateway, contract, 0, { nativeSdk: true }) }).run();
  assert.equal(result.status, 'completed', result.error); assert.equal(requests, 1);
  // Java's real finish endpoint still independently rejects a run without Python evidence.
});

test('SDK cutoff recovery receives a fresh admission and cannot exceed the task model budget', async () => {
  const contract = task(); contract.budget.maxModelCalls = 2;
  let calls = 0; const admitted = [];
  const result = await createCodingRun(contract, {
    async reserveModel(_, value) { admitted.push(value.operationId); }, async settleModel() {}, async finish() {}, async executePython() {},
  }, { nativeSdk: true, model: { async *stream() {
    calls++; yield { type: 'text-delta', text: 'partial' }; yield { type: 'usage', usage: { inputTokens: 1, outputTokens: 1 } };
    yield { type: 'finish', reason: 'max-tokens' };
  } } }).run();
  assert.equal(result.status, 'failed'); assert.equal(calls, 2); assert.equal(admitted.length, 2);
});
