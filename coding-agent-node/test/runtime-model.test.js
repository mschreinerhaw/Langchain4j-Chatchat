import test from 'node:test';
import assert from 'node:assert/strict';
import { createRuntimeModel } from '../src/runtime-model.js';

test('Runtime model forwards only portable parts and emits tool and usage events', async () => {
  let observed;
  const model = createRuntimeModel({ async model(_contract, request) {
    observed = request;
    return { text: 'check', toolCalls: [{ id: 'call-1', name: 'python_execute', input: { fileName: 'a.py', source: 'print(1)' } }],
      usage: { inputTokens: 12, outputTokens: 4 }, requestId: 'model-1' };
  } }, { taskId: 'task-1' });
  const events = [];
  for await (const event of model.stream({ systemPrompt: 'scope', messages: [{ role: 'user', content: [
    { type: 'text', text: 'analyze' },
  ] }], tools: [{ name: 'python_execute', description: 'Run', inputSchema: { type: 'object' } }] })) events.push(event);
  assert.deepEqual(observed.messages[0].content, [{ type: 'text', text: 'analyze' }]);
  assert.equal(observed.operationId, 'model-1');
  assert.equal(events[1].toolName, 'python_execute');
  assert.deepEqual(events[2].usage, { inputTokens: 12, outputTokens: 4 });
  assert.equal(events[3].reason, 'tool-calls');
});

test('Runtime model refuses file parts rather than silently losing context', async () => {
  const model = createRuntimeModel({ model: async () => ({ text: 'unused' }) }, {});
  await assert.rejects(async () => {
    for await (const _event of model.stream({ messages: [{ role: 'user', content: [
      { type: 'file', path: '/private', content: 'secret' },
    ] }], tools: [] })) { /* consume */ }
  }, /Unsupported Runtime model content/);
});

test('native authorized output repair preserves feedback and avoids SDK input-overflow recovery', async () => {
  const feedback = { schemaVersion:'runtime_os.coding.model_feedback.v1', diagnosticCode:'MODEL_OUTPUT_TRUNCATED',
    repairAllowed:true,maxRepairAttempts:2 };
  const usage = {inputTokens:10,outputTokens:32};
  const model=createRuntimeModel({async model(){return {error:'truncated',finishReason:'LENGTH',toolCalls:[],modelResponseFeedback:feedback,usage};}}, {}, 0, {nativeSdk:true});
  const events=[];for await(const event of model.stream({messages:[],tools:[]}))events.push(event);
  assert.deepEqual(events.find(e=>e.type==='runtime-model-feedback').feedback,feedback);
  assert.deepEqual(events.find(e=>e.type==='usage').usage,usage);
  assert.equal(events.at(-1).reason,'stop');
  assert(!events.some(e=>e.type==='tool-call-delta'));
});

test('unauthorized or unknown feedback is never converted into a tool call or retry', async () => {
  for(const feedback of [null,{schemaVersion:'unknown',repairAllowed:true},
    {schemaVersion:'runtime_os.coding.model_feedback.v1',diagnosticCode:'MODEL_OUTPUT_TRUNCATED',repairAllowed:false,maxRepairAttempts:2},
    {schemaVersion:'runtime_os.coding.model_feedback.v1',diagnosticCode:'MODEL_TOOL_NOT_AUTHORIZED',repairAllowed:true,maxRepairAttempts:2}]) {
    const model=createRuntimeModel({async model(){return {error:'rejected',toolCalls:[],modelResponseFeedback:feedback};}},{});
    await assert.rejects(async()=>{for await(const event of model.stream({messages:[],tools:[]}))assert.notEqual(event.type,'tool-call-delta');},/without an authorized repair decision/);
  }
});
