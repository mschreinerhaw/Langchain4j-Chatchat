import test from 'node:test';
import assert from 'node:assert/strict';
import { measureRequestContext } from '../src/request-context.js';
import { sdkPrepareTurn } from '../src/sdk-adapter.js';

test('final payload attribution includes tools, skills, report, code and multibyte text without leaking contents', () => {
  const request = { systemPrompt: 'instruction', tools: [{ name: 'python_execute', inputSchema: {} }], messages: [
    { role: 'user', content: [{ type: 'text', text: '用户请求' }] },
    { role: 'assistant', content: [{ type: 'tool-call', toolName: 'python_execute', input: { source: 'secret_source' } }] },
    { role: 'tool', content: [{ type: 'tool-result', toolName: 'skill_read', output: { text: 'secret_skill' } },
      { type: 'tool-result', toolName: 'artifact_read', output: { report: 'secret_report' } }] },
  ] };
  const measured = measureRequestContext(request, 10);
  assert.equal(Object.values(measured.components).reduce((n, item) => n + item.bytes, 0), measured.requestBytes);
  for (const key of ['tools','skills','report','code','task']) assert(measured.components[key].bytes > 0);
  assert.equal(measured.exceedsSoftBudget, true);
  assert.equal(measured.estimatedInputTokens, Math.ceil(Buffer.byteLength(JSON.stringify(request)) / 4));
  assert.doesNotMatch(JSON.stringify(measured), /secret_|用户请求/);
});

test('review projection preserves exact version/hash while omitting fresh full-source injection; repair restores it', async () => {
  const binding = { source: 'CURRENT_PROGRAM'.repeat(2000), version: { version: 3, codeHash: 'bound-hash' }, lastValidatedHash: 'bound-hash' };
  let reviewOnly = true;
  const prepare = sdkPrepareTurn('review', () => binding, { reviewOnly: () => reviewOnly });
  const context = { messages: [{ role: 'user', content: [{ type: 'text', text: 'authorized objective' }] }],
    tools: [], model: { info: { maxInputTokens: 128000 } }, systemPrompt: '', signal: new AbortController().signal };
  const review = await prepare(context);
  assert.doesNotMatch(JSON.stringify(review.messages), /CURRENT_PROGRAM/);
  assert.match(JSON.stringify(review.messages), /bound-hash/);
  assert.match(JSON.stringify(review.messages), /code_read/);
  reviewOnly = false;
  const repair = await prepare({ ...context, messages: review.messages });
  assert.match(JSON.stringify(repair.messages), /CURRENT_PROGRAM/);
  assert.doesNotMatch(JSON.stringify(repair.messages), /Latest program binding/);
  assert.equal(binding.version.version, 3);
});
