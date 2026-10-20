import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createCodingRun } from '../src/runner.js';
import { createCodingServer } from '../src/server.js';

const contract = {
  schemaVersion: 'runtime_os.coding.v3', taskId: 'task', scope: { tenantId: 'tenant', userId: 'owner', runId: 'run' },
  objective: { instruction: 'Calculate an authorized aggregate' }, inputSnapshotRef: 'input',
  authorization: { networkPolicy: 'NONE', allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: [`c${'a'.repeat(63)}`, `c${'b'.repeat(63)}`] },
  outputContract: { resultFormat: 'JSON_LINES', requiredFiles: ['rows.jsonl'] }, budget: { maxModelCalls: 3 },
  runtimeContext: { model: { selectedModel: 'selected-by-java' }, skills: { status: 'APPLIED', fingerprint: 'approved-guidance',
    skills: [{ skillId: 'authorized-skill' }], stages: { ANALYSIS: ['Use measured values only'] } } },
};
test('SDK receives task configuration and chunk bindings; final code receipt references exact returned source', async () => {
  const source = '\nprint(42)\n'; let turn = 0, received;
  const run = createCodingRun(contract, {
    reserveModel: async () => {}, settleModel: async () => {}, finish: async () => {},
    executePython: async (_task, value) => { received = value; return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }; },
  }, { attemptId: 'task:fence-3', model: { async *stream(request) {
    assert.doesNotMatch(request.systemPrompt, /selected-by-java/);
    assert.match(JSON.stringify(request.messages), /selected-by-java/);
    assert.match(JSON.stringify(request.messages), /authorized-skill/);
    if (++turn === 1) {
      yield { type: 'tool-call-delta', toolCallId: 'generate-1', toolName: 'python_execute', input: { fileName: 'main.py', source } };
      yield { type: 'finish', reason: 'tool-calls' };
    } else { yield { type: 'text-delta', text: 'Runtime archived result' }; yield { type: 'finish', reason: 'stop' }; }
  } } });
  const result = await run.run();
  assert.equal(received.source, source);
  assert.equal(received.codeSha256, createHash('sha256').update(source).digest('hex'));
  assert.equal(result.taskId, 'task'); assert.equal(result.attemptId, 'task:fence-3');
  assert.equal(result.codeFiles[0].sha256, received.codeSha256);
  assert.ok(result.codeFiles[0].artifactRef.endsWith('/code'));
  assert.ok(run.events.some(e => e.type === 'SKILLS_LOADED'));
  assert.ok(run.events.some(e => e.type === 'SKILLS_APPLIED'));
  assert.ok(run.events.some(e => e.type === 'CODE_READY' && e.codeSha256 === received.codeSha256));
  assert.ok(run.events.every(e => !Object.hasOwn(e, 'source') || e.source === 'NODE_CLINE'));
  assert.equal(JSON.stringify(run.events).includes('print(42)'), false);
});
test('fast analysis finishes after a validated tool result and archives the final tool interaction', async () => {
  const next = structuredClone(contract);
  next.metadataRefs = ['runtime-coding:analysis-fast-path:v1'];
  let calls = 0, finished, checkpoint;
  const run = createCodingRun(next, { reserveModel: async () => {}, settleModel: async () => {},
    checkpoint: async (_task, value) => { checkpoint = value; }, finish: async (_task, value) => { finished = value; },
    executePython: async () => ({ status: 'SUCCEEDED', executorStopped: true, validationStatus: 'INCONCLUSIVE',
      businessValidationPassed: false, resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }),
  }, { model: { async *stream() {
    calls++; assert.equal(calls, 1, 'No confirmation/model turn is needed after verified collection');
    yield { type: 'tool-call-delta', toolCallId: 'complete-program', toolName: 'python_execute',
      input: { fileName: 'main.py', source: 'print(1)' } };
    yield { type: 'finish', reason: 'tool-calls' };
  } } });
  const result = await run.run();
  assert.equal(result.status, 'completed'); assert.equal(finished.status, 'completed'); assert.equal(calls, 1);
  assert.ok(checkpoint.messages.some(message => message.role === 'tool'));
  assert.ok(checkpoint.lastCollectedOperation);
});

test('fast analysis keeps output-missing failures repairable and materializes audited patched code', async () => {
  const next = structuredClone(contract); next.metadataRefs = ['runtime-coding:analysis-fast-path:v1'];
  const initial = 'value = 1\nprint(value)\n';
  const digest = createHash('sha256').update(initial).digest('hex');
  let calls = 0, executions = 0, actual;
  const run = createCodingRun(next, { reserveModel: async () => {}, settleModel: async () => {}, finish: async () => {},
    executePython: async (_task, value) => {
      actual = value;
      if (++executions === 1) return { status: 'SUCCEEDED', executorStopped: true, codeHash: digest,
        outputStatus: 'REQUIRED_OUTPUT_MISSING', resultAvailable: false };
      return { status: 'SUCCEEDED', executorStopped: true, resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' };
    },
  }, { model: { async *stream(request) {
    calls++;
    if (calls === 2) assert.match(JSON.stringify(request.messages), /"isError":true/);
    assert.ok(calls <= 2);
    yield { type: 'tool-call-delta', toolCallId: 'program-' + calls, toolName: 'python_execute',
      input: calls === 1 ? { fileName: 'main.py', source: initial } : { fileName: 'main.py', repair: {
        baseCodeSha256: digest, edits: [{ oldText: 'value = 1', newText: 'value = 2' }],
      } } };
    yield { type: 'finish', reason: 'tool-calls' };
  } } });
  const result = await run.run();
  assert.equal(result.status, 'completed'); assert.equal(calls, 2);
  assert.equal(actual.source, 'value = 2\nprint(value)\n');
  assert.equal(actual.codeSha256, createHash('sha256').update(actual.source).digest('hex'));
});

test('reviewed analysis repairs locally and reads its scoped result before completion', async () => {
  const next = structuredClone(contract);
  next.metadataRefs = ['runtime-coding:analysis-reviewed-path:v1'];
  next.authorization.allowedTools = ['python_execute', 'artifact_read'];
  next.budget.maxModelCalls = 4;
  const initial = 'value = 1\nraise ValueError(value)\n';
  const digest = createHash('sha256').update(initial).digest('hex');
  let turns = 0, executions = 0, reviewed = 0, actual, finished;
  const run = createCodingRun(next, {
    reserveModel: async () => {}, settleModel: async () => {},
    finish: async (_task, value) => { finished = value; },
    executePython: async (_task, value) => {
      actual = value;
      if (++executions === 1) return { status: 'FAILED', executorStopped: true,
        technicalError: 'ValueError at program line 2', codeHash: digest };
      return { status: 'SUCCEEDED', executorStopped: true, resultDatasetRef: 'runtime-evidence:result',
        validationRecordRef: 'runtime-evidence:validation', evidenceBundleRef: 'runtime-evidence:bundle' };
    },
    readArtifact: async (_task, reference) => {
      reviewed++; assert.equal(reference, 'runtime-evidence:result');
      return { reference, complete: true, rows: [{ reportMarkdown: 'supported' }] };
    },
  }, { model: { async *stream(request) {
    turns++;
    if (turns === 1) yield { type: 'tool-call-delta', toolCallId: 'initial', toolName: 'python_execute',
      input: { fileName: 'main.py', source: initial } };
    else if (turns === 2) yield { type: 'tool-call-delta', toolCallId: 'repair', toolName: 'python_execute',
      input: { fileName: 'main.py', repair: { baseCodeSha256: digest,
        edits: [{ oldText: 'raise ValueError(value)', newText: 'print(value)' }] } } };
    else if (turns === 3) yield { type: 'tool-call-delta', toolCallId: 'review', toolName: 'artifact_read',
      input: { reference: 'runtime-evidence:result' } };
    else yield { type: 'text-delta', text: 'Reviewed result is supported.' };
    yield { type: 'finish', reason: turns < 4 ? 'tool-calls' : 'stop' };
  } } });
  const result = await run.run();
  assert.equal(result.status, 'completed'); assert.equal(turns, 4); assert.equal(executions, 2); assert.equal(reviewed, 1);
  assert.equal(actual.source, 'value = 1\nprint(value)\n');
  assert.equal(finished.operationId, actual.operationId);
  assert.ok(run.events.some(event => event.type === 'TOOL_STARTED' && event.tool === 'artifact_read'));
  assert.ok(run.events.some(event => event.type === 'TOOL_FINISHED' && event.tool === 'artifact_read'));
});

test('versioned file inputs negotiate format before SDK/model admission and keep output JSONL', async () => {
  for (const format of ['PARQUET', 'CSV']) {
    let prompt, metadataReads = 0;
    const next = structuredClone(contract);
    next.runtimeContext.inputFiles = { schemaVersion: 'runtime_os.coding.input.v1', format, delivery: 'SHARED_DIRECTORY',
      manifestFileId: next.authorization.allowedDataRefs[0], manifestSha256: 'a'.repeat(64) };
    const run = createCodingRun(next, { reserveModel: async () => {}, settleModel: async () => {},
      readInputContext: async () => { metadataReads++; return { columns: ['metadata-column-from-java'] }; },
      executePython: async () => {}, finish: async () => {} }, { model: { async *stream(request) {
        prompt = request.systemPrompt; assert.match(JSON.stringify(request.messages), /metadata-column-from-java/);
        yield { type: 'text-delta', text: 'No execution requested' }; yield { type: 'finish', reason: 'stop' };
      } } });
    await run.run(); assert.equal(metadataReads, 1); assert.match(prompt, /ordered Parquet\/CSV parts|ordered parts|Parquet presence columns/);
    assert.match(prompt, /map storage columns to logical names/i);
    assert.match(prompt, /file ID directories are siblings/);
    assert.match(prompt, /exact files\[\]\.virtualPath/);
    next.runtimeContext.inputFiles.schemaVersion = 'unnegotiated';
    assert.throws(() => createCodingRun(next, {}), /VERSION_UNSUPPORTED/);
  }
});
test('only explicitly declared bounded input observations reach the coding model', async () => {
  for (const declared of [false, true]) {
    const next = structuredClone(contract);
    next.metadataRefs = declared ? ['runtime-coding:bounded-input-observation:v1'] : [];
    next.runtimeContext.inputFiles = { schemaVersion: 'runtime_os.coding.input.v1', format: 'PARQUET',
      delivery: 'SHARED_DIRECTORY', manifestFileId: next.authorization.allowedDataRefs[0], manifestSha256: 'a'.repeat(64) };
    let calls = 0;
    const run = createCodingRun(next, { reserveModel: async () => {}, settleModel: async () => {},
      executePython: async () => {}, finish: async () => {}, readInputContext: async () => ({
        analysisPreview: { complete: false, samples: [{ rowJsonPrefix: 'authorized-format-prefix' }] },
      }) }, { model: { async *stream(request) {
        calls++; assert.match(JSON.stringify(request.messages), /authorized-format-prefix/);
        assert.match(request.systemPrompt, /does not judge business conclusions/);
        yield { type: 'text-delta', text: 'Analysis agent owns the result' }; yield { type: 'finish', reason: 'stop' };
      } } });
    if (declared) { await run.run(); assert.equal(calls, 1); }
    else { await assert.rejects(run.run(), /not declared/); assert.equal(calls, 0); }
  }
});

test('cancellation during input preparation cannot start a late model loop', async () => {
  const next = structuredClone(contract);
  next.runtimeContext.inputFiles = { schemaVersion: 'runtime_os.coding.input.v1', format: 'PARQUET',
    delivery: 'SHARED_DIRECTORY', manifestFileId: next.authorization.allowedDataRefs[0], manifestSha256: 'a'.repeat(64) };
  let release, signal, modelCalls = 0, finish;
  const run = createCodingRun(next, {
    reserveModel: async () => {}, executePython: async () => {},
    finish: async (_contract, outcome) => { finish = outcome; },
    readInputContext: async (_contract, value) => {
      signal = value;
      return new Promise(resolve => { release = resolve; });
    },
  }, { model: { async *stream() { modelCalls++; yield { type: 'finish', reason: 'stop' }; } } });
  const pending = run.run();
  run.abort('Runtime cancellation');
  assert.equal(signal.aborted, true);
  release({ columns: [] });
  await assert.rejects(pending, /Runtime cancellation/);
  assert.equal(modelCalls, 0);
  assert.equal(finish.status, 'aborted');
  assert.equal(run.events.some(event => event.type === 'CONTEXT_READY'), false);
});

test('failed input preparation records a failed Runtime finish without admitting a model', async () => {
  const next = structuredClone(contract);
  next.runtimeContext.inputFiles = { schemaVersion: 'runtime_os.coding.input.v1', format: 'CSV', delivery: 'SHARED_DIRECTORY',
    manifestFileId: next.authorization.allowedDataRefs[0], manifestSha256: 'a'.repeat(64) };
  let finish, modelCalls = 0;
  const run = createCodingRun(next, { reserveModel: async () => {}, executePython: async () => {},
    readInputContext: async () => { throw new Error('Metadata unavailable'); },
    finish: async (_contract, outcome) => { finish = outcome; },
  }, { model: { async *stream() { modelCalls++; yield { type: 'finish', reason: 'stop' }; } } });
  await assert.rejects(run.run(), /Metadata unavailable/);
  assert.equal(finish.status, 'failed'); assert.equal(finish.iterations, 0); assert.equal(modelCalls, 0);
});

test('cancellation while persisting context events cannot be reset by Agent.run', async () => {
  let release, entered, finish, modelCalls = 0;
  const started = new Promise(resolve => { entered = resolve; });
  const run = createCodingRun(contract, { reserveModel: async () => {}, executePython: async () => {},
    event: async event => {
      if (event.type === 'CONTEXT_READY') { entered(); await new Promise(resolve => { release = resolve; }); }
    },
    finish: async (_contract, outcome) => { finish = outcome; },
  }, { model: { async *stream() { modelCalls++; yield { type: 'finish', reason: 'stop' }; } } });
  const pending = run.run(); await started;
  run.abort('Runtime cancellation'); release();
  await assert.rejects(pending, /Runtime cancellation/);
  assert.equal(finish.status, 'aborted'); assert.equal(modelCalls, 0);
});

test('worker graceful shutdown cancels active SDK execution and records finish before exiting', async () => {
  const secret = '0123456789abcdef0123456789abcdef';
  let entered, stopped = false;
  const started = new Promise(resolve => { entered = resolve; });
  const server = createCodingServer({ secret, callbackUrl: 'http://localhost:8080',
    modelFactory: () => ({ async *stream(request) {
      entered();
      await new Promise(resolve => { if (request.signal.aborted) resolve(); else request.signal.addEventListener('abort', resolve, { once: true }); });
      yield { type: 'finish', reason: 'stop' };
    } }),
    gatewayFactory: () => ({ reserveModel: async () => {}, settleModel: async () => {},
      executePython: async () => {}, finish: async () => { stopped = true; } }),
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const response = await fetch(`${url}/v1/runs`, { method: 'POST', headers: { authorization: `Bearer ${secret}` },
    body: JSON.stringify({ contract, contractHash: 'a'.repeat(64), fencingToken: 1 }) });
  assert.equal(response.status, 202); await response.json(); await started;
  await server.shutdown(2000);
  assert.equal(server.listening, false); assert.equal(stopped, true);
});
