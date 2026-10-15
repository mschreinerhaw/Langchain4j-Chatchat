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
