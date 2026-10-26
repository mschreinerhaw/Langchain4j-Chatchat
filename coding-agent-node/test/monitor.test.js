import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { MonitorStore } from '../dist/monitor.js';
import { createCodingRun as realCreateCodingRun } from '../src/runner.js';
// Allocation is a Java callback fixture; Python results below remain branch-specific fixtures.
const createCodingRun=(task,gateway,options)=>{
  let version=0;const assigned=new Map();
  return realCreateCodingRun(task,{async preparePython(_task,input){
    if(!assigned.has(input.operationId)){
      const scriptVersion=++version,fileName=`financial-total_20261022T000000000Z_abcdef012345_v${scriptVersion}.py`;
      assigned.set(input.operationId,{fileName,scriptVersion,scriptPath:'/workspace/scripts/'+fileName,
        workingDirectory:'tasks/'+['a'.repeat(16),'b'.repeat(16),'c'.repeat(16),'d'.repeat(24)].join('/')});
    }
    return assigned.get(input.operationId);
  },...gateway},options);
};
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
test('callback timings remain distinct from provider timings and persist only bounded metadata', () => {
  const store = new MonitorStore();
  const id = store.start(contract, 1, 'NEW_SDK');
  store.event(id, { type: 'MODEL_STARTED', modelCall: 1 });
  store.event(id, { type: 'MODEL_RESOLVED', modelCall: 1, durationMs: 120, firstTokenMs: 40 });
  store.event(id, { type: 'MODEL_TIMING', modelCall: 1, durationMs: 150, callbackFirstEventMs: 130,
    requestBytes: 2048, responseBytes: 512, requestHash: 'a'.repeat(64), prompt: 'PRIVATE_PROMPT' });
  const detail = store.detail(id);
  assert.equal(detail.models[0].durationMs, 120);
  assert.equal(detail.models[0].callbackDurationMs, 150);
  assert.equal(detail.models[0].firstTokenMs, 40);
  assert.equal(detail.models[0].callbackFirstEventMs, 130);
  assert.equal(detail.models[0].requestBytes, 2048);
  assert.doesNotMatch(JSON.stringify(detail), /PRIVATE_PROMPT/);
});
for (const skillStatus of ['APPLIED', 'DELIVERED']) test(`SDK receives ${skillStatus} Skills and configuration; code receipt references exact source`, async () => {
  const source = '\nprint(42)\n'; let turn = 0, received;
  const task = structuredClone(contract);
  task.runtimeContext.skills.status = skillStatus;
  task.runtimeContext.skills.skills[0].instructions = ['Original authorized methodology'];
  const run = createCodingRun(task, {
    reserveModel: async () => {}, settleModel: async () => {}, finish: async () => {},
    executePython: async (_task, value) => { received = value; return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }; },
  }, { attemptId: 'task:fence-3', model: { async *stream(request) {
    assert.doesNotMatch(request.systemPrompt, /selected-by-java/);
    assert.match(request.systemPrompt, /implement the supplied Runtime task plan in Python/);
    assert.match(request.systemPrompt, /already selected business template workflow is immutable/);
    assert.doesNotMatch(request.systemPrompt, /analysis agent owns analytical goals, methods/);
    assert.match(JSON.stringify(request.messages), /selected-by-java/);
    assert.match(JSON.stringify(request.messages), /authorized-skill/);
    assert.match(JSON.stringify(request.messages), /Original authorized methodology/);
    assert.match(request.systemPrompt, /implementation guidance within the Runtime task plan/);
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
  assert.equal(run.events.some(e => e.type === 'SKILLS_APPLIED'), skillStatus === 'APPLIED');
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
  assert.equal(result.status, 'completed'); assert.equal(turns, 3); assert.equal(executions, 2); assert.equal(reviewed, 1);
  assert.equal(actual.source, 'value = 1\nprint(value)\n');
  assert.equal(finished.operationId, actual.operationId);
  assert.ok(run.events.some(event => event.type === 'TOOL_STARTED' && event.tool === 'artifact_read'));
  assert.ok(run.events.some(event => event.type === 'TOOL_FINISHED' && event.tool === 'artifact_read'));
});

test('native completion rejects a stale artifact and stops immediately after the latest accepted review', async () => {
  const next = structuredClone(contract);
  next.metadataRefs = ['runtime-coding:analysis-reviewed-path:v1'];
  next.authorization.allowedTools = ['python_execute', 'artifact_read']; next.budget.maxModelCalls = 4;
  let calls = 0, executions = 0;
  const run = createCodingRun(next, { async reserveModel() {}, async settleModel() {}, async finish() {},
    async executePython() { executions++; return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'runtime-evidence:latest', validationRecordRef: 'validation', evidenceBundleRef: 'evidence' }; },
    async readArtifact(_task, reference) { return { reference, complete: true, rows: [{ reportMarkdown: 'computed' }],
      reportReferenceValidation: { valid: true } }; },
  }, { model: { async *stream(request) {
    calls++; assert.ok(calls <= 3, 'No discretionary rewrite/model turn follows an accepted artifact');
    if (calls === 1) yield { type: 'tool-call-delta', toolCallId: 'program', toolName: 'python_execute', input: { source: 'print(1)' } };
    else {
      if (calls === 3) assert.match(JSON.stringify(request.messages), /"isError":true/);
      yield { type: 'tool-call-delta', toolCallId: 'review-' + calls, toolName: 'artifact_read',
        input: { reference: calls === 2 ? 'runtime-evidence:stale' : 'runtime-evidence:latest' } };
    }
    yield { type: 'finish', reason: 'tool-calls' };
  } } });
  assert.equal((await run.run()).status, 'completed'); assert.equal(calls, 3); assert.equal(executions, 1);
});

test('a complete artifact cannot certify a Python receipt whose result validation failed', async () => {
  const next = structuredClone(contract);
  next.metadataRefs = ['runtime-coding:analysis-reviewed-path:v1'];
  next.authorization.allowedTools = ['python_execute', 'artifact_read']; next.budget.maxModelCalls = 2;
  let calls = 0;
  const run = createCodingRun(next, { async reserveModel() {}, async settleModel() {}, async finish() {},
    async executePython() { return { status: 'SUCCEEDED', executorStopped: true, resultAvailable: false,
      resultDatasetRef: 'runtime-evidence:invalid', validationStatus: 'FAILED', validationRecordRef: 'validation', evidenceBundleRef: 'evidence' }; },
    async readArtifact(_task, reference) { return { reference, complete: true, reportReferenceValidation: { valid: true } }; },
  }, { model: { async *stream() {
    if (++calls === 1) yield { type: 'tool-call-delta', toolCallId: 'program', toolName: 'python_execute', input: { source: 'print(1)' } };
    else yield { type: 'tool-call-delta', toolCallId: 'review', toolName: 'artifact_read', input: { reference: 'runtime-evidence:invalid' } };
    yield { type: 'finish', reason: 'tool-calls' };
  } } });
  assert.equal((await run.run()).status, 'failed');
});

test('report citation failures return to the Cline repair loop before task completion', async () => {
  const next = structuredClone(contract);
  next.metadataRefs = ['runtime-coding:analysis-reviewed-path:v1'];
  next.authorization.allowedTools = ['python_execute', 'artifact_read'];
  next.budget.maxModelCalls = 6;
  let turns = 0, executions = 0, finished;
  const run = createCodingRun(next, {
    reserveModel() {}, settleModel() {}, finish(_task, value) { finished = value; },
    executePython() { executions++; return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'runtime-evidence:result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }; },
    readArtifact() { return { reference: 'runtime-evidence:result', rows: [{ reportMarkdown: 'report' }], complete: true,
      reportReferenceValidation: { valid: executions === 2, repairRequired: executions !== 2,
        authorizedDatasetReferences: ['exact-source#binding-1'] } }; },
  }, { model: { async *stream(request) {
    turns++;
    if (turns === 3) assert.match(JSON.stringify(request.messages), /exact-source#binding-1/);
    if (turns === 1 || turns === 3) yield { type: 'tool-call-delta', toolCallId: `code-${turns}`,
      toolName: 'python_execute', input: { fileName: 'main.py', source: `# program ${turns}` } };
    else if (turns === 2 || turns === 4) yield { type: 'tool-call-delta', toolCallId: `review-${turns}`,
      toolName: 'artifact_read', input: { reference: 'runtime-evidence:result' } };
    else yield { type: 'text-delta', text: 'Repaired exact source references.' };
    yield { type: 'finish', reason: turns < 5 ? 'tool-calls' : 'stop' };
  } } });
  assert.equal((await run.run()).status, 'completed');
  assert.equal(executions, 2); assert.equal(finished.status, 'completed');
  assert.ok(finished.operationId);
});

test('a model cannot finish successfully after a rejected report reference check', async () => {
  const next = structuredClone(contract);
  next.metadataRefs = ['runtime-coding:analysis-reviewed-path:v1'];
  next.authorization.allowedTools = ['python_execute', 'artifact_read'];
  let turns = 0, finished;
  const run = createCodingRun(next, {
    reserveModel() {}, settleModel() {}, finish(_task, value) { finished = value; },
    executePython() { return { status: 'SUCCEEDED', executorStopped: true,
      resultDatasetRef: 'runtime-evidence:result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }; },
    readArtifact() { return { reportReferenceValidation: { valid: false, repairRequired: true } }; },
  }, { model: { async *stream() {
    if (++turns === 1) yield { type: 'tool-call-delta', toolCallId: 'code', toolName: 'python_execute',
      input: { fileName: 'main.py', source: '# invalid report references' } };
    else if (turns === 2) yield { type: 'tool-call-delta', toolCallId: 'review', toolName: 'artifact_read',
      input: { reference: 'runtime-evidence:result' } };
    else yield { type: 'text-delta', text: 'Finish without repairing.' };
    yield { type: 'finish', reason: turns < 3 ? 'tool-calls' : 'stop' };
  } } });
  const result = await run.run().catch(error => ({ status: 'failed', error }));
  assert.notEqual(result.status, 'completed');
  assert.notEqual(finished?.status, 'completed');
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
        assert.match(request.systemPrompt, /Runtime OS owns business interpretation, classification, task plans/);
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
