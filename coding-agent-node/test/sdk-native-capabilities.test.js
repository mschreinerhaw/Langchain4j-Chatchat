import test from 'node:test';
import assert from 'node:assert/strict';
import { Agent } from '@cline/agents';
import * as sdk from '@cline/sdk';

// Installed public APIs only. Models/tools are fixtures, never provider benchmarks.
test('installed SDK exposes session, compaction and patch extension points', () => {
  for (const name of ['ClineCore', 'createTool', 'createContextCompactionPrepareTurn',
    'createCompactionStateAwarePrepareTurn', 'createBuiltinTools', 'createApplyPatchExecutor'])
    assert.equal(typeof sdk[name], 'function', name);
  // readMessages/send/abort are instance-bound fields, not prototype methods.
  for (const name of ['start', 'restore', 'compareCheckpoint', 'subscribe'])
    assert.equal(typeof sdk.ClineCore.prototype[name], 'function', name);
});

test('failed Python tool feeds the next native turn in the same Agent run', async () => {
  const runIds = new Set(); let calls = 0; let tools = 0;
  const agent = new Agent({ maxIterations: 3,
    model: { async *stream(request) {
      if (++calls === 1) yield { type: 'tool-call-delta', toolCallId: 'python-1', toolName: 'python_execute', input: {} };
      else {
        const result = request.messages.flatMap(m => m.content).find(p => p.type === 'tool-result');
        assert.equal(result.isError, true); assert.match(JSON.stringify(result.output), /fixture failure/);
        yield { type: 'text-delta', text: 'diagnostic received' };
      }
      yield { type: 'finish', reason: calls === 1 ? 'tool-calls' : 'stop' };
    } },
    tools: [{ name: 'python_execute', description: 'fixture', inputSchema: { type: 'object' },
      execute() { tools++; throw new Error('fixture failure'); } }],
    hooks: { onEvent(event) { if (event.snapshot.runId) runIds.add(event.snapshot.runId); } },
  });
  assert.equal((await agent.run('test')).status, 'completed');
  assert.equal(calls, 2); assert.equal(tools, 1); assert.equal(runIds.size, 1);
});

test('public prepareTurn projects requests without overwriting canonical messages', async () => {
  const agent = new Agent({
    prepareTurn: () => ({ messages: [{ role: 'user', content: [{ type: 'text', text: 'projection' }] }] }),
    model: { async *stream(request) {
      assert.equal(request.messages[0].content[0].text, 'projection');
      yield { type: 'text-delta', text: 'done' }; yield { type: 'finish', reason: 'stop' };
    } },
  });
  const result = await agent.run('canonical');
  assert.equal(result.messages[0].content[0].text, 'canonical');
});

test('restore preserves transcript but resets native usage', async () => {
  const agent = new Agent({ model: { async *stream() {
    yield { type: 'usage', usage: { inputTokens: 7, outputTokens: 2 } };
    yield { type: 'text-delta', text: 'done' }; yield { type: 'finish', reason: 'stop' };
  } } });
  const result = await agent.run('test');
  assert.equal(result.usage.inputTokens, 7);
  agent.restore(result.messages);
  assert.equal(agent.snapshot().usage.inputTokens, 0);
  assert.equal(agent.snapshot().messages.length, result.messages.length);
});

test('errorRetryable false prevents hidden provider attempts', async () => {
  let streams = 0; let admissions = 0;
  const agent = new Agent({ hooks: { beforeModel() { admissions++; } },
    model: { async *stream() {
      streams++; yield { type: 'finish', reason: 'error', error: 'HTTP 503 fixture', errorRetryable: false };
    } },
  });
  assert.equal((await agent.run('test')).status, 'failed');
  assert.equal(streams, 1); assert.equal(admissions, 1);
});

test('ordinary native provider retries invoke beforeModel again', async () => {
  let streams = 0; let admissions = 0;
  const agent = new Agent({ hooks: { beforeModel() { admissions++; } },
    model: { async *stream() {
      if (++streams === 1) {
        yield { type: 'finish', reason: 'error', error: 'HTTP 503 fixture', errorRetryable: true };
      } else {
        yield { type: 'text-delta', text: 'recovered' }; yield { type: 'finish', reason: 'stop' };
      }
    } },
  });
  assert.equal((await agent.run('test')).status, 'completed');
  assert.equal(streams, 2); assert.equal(admissions, 2);
});

test('retry inside max-token compaction recovery bypasses an additional beforeModel', async () => {
  let streams = 0; let admissions = 0;
  const agent = new Agent({ hooks: { beforeModel() { admissions++; } },
    prepareTurn(context) { if (context.overflowRecovery) return {
      messages: [{ role: 'user', content: [{ type: 'text', text: 'short' }] }],
    }; },
    model: { async *stream() {
      streams++;
      if (streams === 1) {
        yield { type: 'text-delta', text: 'partial' }; yield { type: 'finish', reason: 'max-tokens' };
      } else if (streams === 2) {
        yield { type: 'finish', reason: 'error', error: 'HTTP 503 fixture', errorRetryable: true };
      } else {
        yield { type: 'text-delta', text: 'recovered' }; yield { type: 'finish', reason: 'stop' };
      }
    } },
  });
  assert.equal((await agent.run('long original request '.repeat(100))).status, 'completed');
  assert.equal(streams, 3); assert.equal(admissions, 2);
});

test('plain AgentTool timeout metadata does not enforce a timeout in this version', async () => {
  let finished = false; let calls = 0;
  const agent = new Agent({ maxIterations: 2,
    model: { async *stream() {
      if (++calls === 1) yield { type: 'tool-call-delta', toolCallId: 'slow-1', toolName: 'slow', input: {} };
      else yield { type: 'text-delta', text: 'done' };
      yield { type: 'finish', reason: calls === 1 ? 'tool-calls' : 'stop' };
    } },
    tools: [{ name: 'slow', description: 'fixture', inputSchema: { type: 'object' }, timeoutMs: 1,
      async execute() { await new Promise(resolve => setTimeout(resolve, 25)); finished = true; return 'done'; } }],
  });
  const result = await agent.run('test');
  assert.equal(finished, true); assert.equal(result.status, 'completed');
  assert.equal(result.messages.flatMap(m => m.content).find(p => p.type === 'tool-result').isError, undefined);
});

test('native abort propagates its signal to the active custom tool', async () => {
  let started; const ready = new Promise(resolve => { started = resolve; });
  const agent = new Agent({ model: { async *stream() {
    yield { type: 'tool-call-delta', toolCallId: 'wait-1', toolName: 'wait', input: {} };
    yield { type: 'finish', reason: 'tool-calls' };
  } }, tools: [{ name: 'wait', description: 'fixture', inputSchema: { type: 'object' },
    execute(input, context) { return new Promise((resolve, reject) => {
      context.signal.addEventListener('abort', () => reject(context.signal.reason), { once: true }); started();
    }); } }],
  });
  const pending = agent.run('test'); await ready; agent.abort('fixture cancellation');
  assert.equal((await pending).status, 'aborted');
});
