import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingProgress } from '../src/coding-progress.js';
import { createToolCatalog, DEFERRED_TOOLS_PROFILE } from '../src/tool-catalog.js';
import { createCodingRun } from '../src/runner.js';
import { MonitorStore } from '../dist/monitor.js';

test('advisory flags preparation and stalls without imposing a call limit', () => {
  const tracker = createCodingProgress({ preparationCalls: 4, stalledCalls: 3 });
  for (let n = 1; n <= 12; n++) {
    const result = tracker.model(n, { remainingTokens: 1000 - n, remainingModelCalls: 20 - n });
    if (n === 3) assert.match(result.feedback, /NO_NEW_TOOL_RESULT/);
    if (n === 4) assert.match(result.feedback, /PREPARATION_WITHOUT_CODE/);
    assert.equal(result.progress.modelCall, n);
    assert.equal(result.stop, undefined);
  }
  assert.equal(tracker.snapshot().remainingTokens, 988);
  assert.equal(tracker.model(13).progress.remainingTokens, null);
});

test('new evidence resets stalls; identical failures and unchanged results do not', () => {
  const tracker = createCodingProgress({ preparationCalls: 0, stalledCalls: 2 });
  tracker.model(1);
  const call = { toolName: 'input_read', input: { datasetReference: 'source', offset: 0 } };
  tracker.tool(call, { output: { rows: [1] } });
  assert.equal(tracker.model(2).feedback, '');
  tracker.tool(call, { output: { rows: [1] } });
  assert.match(tracker.model(3).feedback, /NO_NEW_TOOL_RESULT/);
  tracker.tool(call, { output: { error: 'binding' }, isError: true });
  tracker.tool(call, { output: { error: 'binding' }, isError: true });
  assert.equal(tracker.snapshot().repeatedFailures, 1);
  assert.equal(tracker.snapshot().newEvidenceReads, 1);
  const python = { toolName: 'python_execute', input: { source: 'broken' } };
  for (let operation = 1; operation <= 2; operation++) tracker.tool(python, { output: {
    status: 'FAILED', operationId: operation, executionFeedback: { error: { category: 'PROGRAM', diagnosticCode: 'SYNTAX_ERROR' } } } });
  assert.equal(tracker.snapshot().repeatedFailures, 2);
  tracker.tool({ ...call, input: { ...call.input, offset: 1 } }, { output: { rows: [2] } });
  assert.equal(tracker.model(4).feedback, '');
});

test('proposal, Python and result clocks distinguish rejected code and unknown execution', () => {
  let clock = 100;
  const tracker = createCodingProgress({ now: () => clock });
  clock = 150; tracker.observe({ type: 'CODE_PROPOSED' });
  tracker.observe({ type: 'CODE_PROPOSAL_REJECTED' });
  assert.equal(tracker.snapshot().firstValidCodeMs, null);
  clock = 200; tracker.observe({ type: 'CODE_PROPOSED' }); tracker.observe({ type: 'CODE_READY' });
  clock = 250; tracker.observe({ type: 'TOOL_STARTED', tool: 'python_execute' });
  tracker.observe({ type: 'TOOL_FINISHED', tool: 'python_execute', operationId: 'unknown', executorStopped: false });
  assert.equal(tracker.snapshot().pythonExecutions, 0);
  clock = 300;
  const receipt = { type: 'TOOL_FINISHED', tool: 'python_execute', operationId: 'stopped', executorStopped: true, resultAvailable: true };
  tracker.observe(receipt); tracker.observe(receipt);
  const state = tracker.snapshot();
  assert.equal(state.proposals, 2); assert.equal(state.acceptedProposals, 1); assert.equal(state.rejectedProposals, 1);
  assert.equal(state.firstProposalMs, 50); assert.equal(state.firstValidCodeMs, 100);
  assert.equal(state.firstPythonMs, 150); assert.equal(state.firstResultMs, 200); assert.equal(state.pythonExecutions, 1);
});

test('thresholds are configurable and disabled feedback preserves observations', () => {
  assert.throws(() => createCodingProgress({ stalledCalls: -1 }), /threshold/);
  const tracker = createCodingProgress({ preparationCalls: 0, stalledCalls: 0 });
  for (let n = 1; n < 40; n++) assert.equal(tracker.model(n).feedback, '');
  tracker.usage({ inputTokens: 10, outputTokens: 2 }); tracker.usage({});
  assert.equal(tracker.snapshot().knownInputTokens, 10);
  tracker.tool({ toolName: 'skill_read', input: { skillId: 'one' } }, { output: 'method' });
  tracker.tool({ toolName: 'tool_describe', input: { names: ['one'] } }, { output: { loaded: ['one'] } });
  assert.equal(tracker.snapshot().skillReads, 1); assert.equal(tracker.snapshot().discoveryCalls, 1);
});

test('restored code does not trigger a false initial-code prompt; stalls request a minimal repair', () => {
  const tracker = createCodingProgress({ initialCodeAvailable: true, preparationCalls: 1, stalledCalls: 3 });
  assert.equal(tracker.model(9).feedback, '');
  assert.equal(tracker.model(10).feedback, '');
  const feedback = tracker.model(11).feedback;
  assert.match(feedback, /NO_NEW_TOOL_RESULT/); assert.match(feedback, /minimal repair against the latest version/);
  assert.doesNotMatch(feedback, /PREPARATION_WITHOUT_CODE/);
  assert.equal(tracker.snapshot().acceptedProposals, 0);
  assert.equal(tracker.snapshot().firstValidCodeMs, null);
});

test('eager disclosure cannot introduce unauthorized tools and survives catalog restore', () => {
  const tools = [{ name: 'python_execute', inputSchema: { type: 'object' } }, { name: 'skill_read' }];
  const catalog = createToolCatalog(tools, undefined, ['python_execute', 'shell']);
  assert.deepEqual(catalog.expose(tools).map(t => t.name), ['python_execute']);
  assert.equal(catalog.has('shell'), false); assert.equal(catalog.has('skill_read'), false);
  assert.equal(createToolCatalog(tools, catalog.snapshot()).has('python_execute'), true);
  assert.equal(createToolCatalog([], catalog.snapshot(), ['python_execute']).has('python_execute'), false);
});

const task = { schemaVersion: 'runtime_os.coding.v3', taskId: 'progress', scope: { tenantId: 't', userId: 'u', runId: 'r' },
  metadataRefs: [DEFERRED_TOOLS_PROFILE], objective: { instruction: 'Calculate 2 + 2 using Python' },
  authorization: { allowedTools: ['python_execute', 'tool_describe'], allowedSideEffects: ['CODE_EXECUTION'],
    allowedDataRefs: ['c' + 'a'.repeat(63)], networkPolicy: 'NONE' },
  outputContract: { resultFormat: 'JSON_LINES', requiredFiles: ['rows.jsonl'] }, budget: { maxModelCalls: 8 } };

test('real SDK can submit initial code without schema discovery, Skill reads or a plan turn', async () => {
  let calls = 0;
  const run = createCodingRun(task, {
    reserveModel() {}, settleModel() {}, finish() {},
    preparePython() { return { scriptVersion: 1, fileName: 'sum_v1.py', scriptPath: '/workspace/scripts/sum_v1.py',
      workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') }; },
    executePython() { return { executorStopped: true, status: 'SUCCEEDED', resultAvailable: true,
      resultDatasetRef: 'result', validationRecordRef: 'validation', evidenceBundleRef: 'bundle' }; },
  }, { nativeSdk: true, model: { async *stream(request) {
    calls++;
    assert.ok(request.tools.some(t => t.name === 'python_execute' && t.inputSchema.properties.source));
    if (calls === 1) {
      yield { type: 'tool-call-delta', toolName: 'python_execute', toolCallId: 'code', input: { source: 'print(2 + 2)' } };
      yield { type: 'finish', reason: 'tool-calls' };
    } else { yield { type: 'text-delta', text: 'Done' }; yield { type: 'finish', reason: 'stop' }; }
  } } });
  assert.equal((await run.run()).status, 'completed');
  const events = run.events;
  const proposal = events.find(e => e.type === 'CODE_READY');
  assert.equal(proposal.modelCall, 1);
  assert.equal(events.at(-1).progress.pythonExecutions, 1);
  assert.equal(events.find(e => e.type === 'MODEL_USAGE').actualTools[0], 'python_execute');
});

test('same SDK session receives advisory and continues legitimate exploration beyond threshold', async () => {
  let calls = 0;
  const noPython = { ...task, authorization: { ...task.authorization, allowedTools: ['skill_read'], allowedSideEffects: [] }, metadataRefs: [] };
  const run = createCodingRun(noPython, { reserveModel() {}, settleModel() {}, finish() {}, executePython() {} },
    { nativeSdk: true, progressPreparationCalls: 2, progressStalledCalls: 2, model: { async *stream(request) {
      calls++;
      if (calls === 2 || calls === 4) assert.match(request.systemPrompt, /Coding progress check/);
      if (calls < 4) {
        yield { type: 'tool-call-delta', toolName: 'skill_read', toolCallId: 'skill-' + calls, input: { skillId: 'builtin:python-data-analysis' } };
        yield { type: 'finish', reason: 'tool-calls' };
      } else { yield { type: 'text-delta', text: 'Explained' }; yield { type: 'finish', reason: 'stop' }; }
    } } });
  assert.equal((await run.run()).status, 'completed'); assert.equal(calls, 4);
});

test('monitor stores bounded progress, tool names, finish reason and diagnostics without tool payloads', () => {
  const store = new MonitorStore({ secrets: ['secret'] }); const id = store.start(task, 1, 'fresh');
  assert.equal(store.detail(id).progress, undefined);
  store.event(id, { type: 'MODEL_STARTED', modelCall: 1, purpose: 'PREPARING', progress: {
    modelCall: 1, phase: 'PREPARING', waitingFor: 'MODEL', lastTool: 'secret', proposals: 0,
    firstProposalMs: null, remainingTokens: -1, source: 'PRIVATE_SOURCE' } });
  store.event(id, { type: 'MODEL_FAILED', modelCall: 1, finishReason: 'error', diagnosticCode: 'MODEL_REQUEST_TIMEOUT',
    actualTools: ['input_read'], input: 'PRIVATE_INPUT' });
  const detail = store.detail(id);
  assert.equal(detail.progress.firstProposalMs, null); assert.equal(detail.progress.remainingTokens, null);
  assert.equal(detail.progress.lastTool, '[REDACTED]'); assert.equal(detail.models[0].purpose, 'PREPARING');
  assert.equal(detail.models[0].diagnosticCode, 'MODEL_REQUEST_TIMEOUT'); assert.deepEqual(detail.models[0].actualTools, ['input_read']);
  assert.doesNotMatch(JSON.stringify(detail), /PRIVATE_SOURCE|PRIVATE_INPUT/);
});
