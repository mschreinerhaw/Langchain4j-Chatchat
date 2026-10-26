/** Controlled SDK comparison: identical proposals, no live provider or business data. */
import { performance } from 'node:perf_hooks';
import { createHash } from 'node:crypto';
import { createCodingRun } from '../src/runner.js';
import { CODE_LIFECYCLE_VERSION } from '../src/code-repair.js';

const sha = text => createHash('sha256').update(text).digest('hex');
async function sample(enabled, index, fullSource = false) {
  let source = '', version = 0, calls = 0, executions = 0, inputBytes = 0, valid = null;
  const initial = 'amount = 1\nanswer = amount[0]\nlabel = 0\n' + '# transport fixture padding\n'.repeat(1000);
  const contract = { schemaVersion: 'runtime_os.coding.v1', taskId: `benchmark-${enabled}-${index}`,
    scope: { tenantId: 'fixture', userId: 'fixture', runId: 'fixture-' + index },
    objective: { instruction: 'Preserve the validated technical correction.' },
    inputSnapshotRef: 'fixture:immutable', outputContract: { requiredFiles: ['rows.jsonl'] },
    authorization: { allowedTools: ['python_execute'], allowedSideEffects: ['CODE_EXECUTION'],
      allowedDataRefs: ['c' + 'a'.repeat(63)], networkPolicy: 'NONE' },
    runtimeContext: enabled ? { codingLifecycle: { schemaVersion: CODE_LIFECYCLE_VERSION, patchOnlyAfterFirst: true } } : {},
    budget: { maxModelCalls: 6, deadlineAt: new Date(Date.now() + 10000).toISOString() } };
  const model = { async *stream(request) {
    calls++; inputBytes += Buffer.byteLength(JSON.stringify(request.messages));
    let input;
    if (calls === 1) input = { source: initial };
    if (calls === 2) input = { repair: { baseCodeSha256: sha(source), parentCodeVersion: version,
      edits: [{ oldText: 'answer = amount[0]', newText: 'answer = amount' }] } };
    if (calls === 3) input = { repair: { baseCodeSha256: sha(source), parentCodeVersion: version,
      edits: [{ oldText: 'label = 0', newText: 'label = 1' },
        { oldText: 'answer = amount', newText: 'answer = amount[0]' }] } };
    if (fullSource && input?.repair) {
      let next = source;
      for (const edit of input.repair.edits) next = next.replace(edit.oldText, edit.newText);
      input = { source: next };
    }
    if (input) yield { type: 'tool-call-delta', toolCallId: 'proposal-' + calls, toolName: 'python_execute', input };
    else yield { type: 'text-delta', text: 'Use the validated result.' };
    yield { type: 'finish', reason: input ? 'tool-calls' : 'stop' };
  } };
  const gateway = { async reserveModel() {}, async settleModel() {},
    async preparePython() { const scriptVersion = ++version, fileName = `fixture_20261026T000000000Z_abcdef012345_v${version}.py`;
      return { scriptVersion, fileName, scriptPath: '/workspace/scripts/' + fileName,
        workingDirectory: 'tasks/' + ['a'.repeat(16), 'b'.repeat(16), 'c'.repeat(16), 'd'.repeat(24)].join('/') }; },
    async executePython(_task, proposal) {
      source = proposal.source; executions++;
      if (source.includes('amount[0]')) { valid = null; return { status: 'FAILED', executorStopped: true,
        resultAvailable: false, executionFeedback: { repair: { allowed: true, action: 'REPAIR_IN_CURRENT_CONTEXT' } } }; }
      valid = proposal.operationId;
      return { status: 'SUCCEEDED', executorStopped: true, resultAvailable: true,
        validationStatus: 'INCONCLUSIVE', resultDatasetRef: 'fixture:result', validationRecordRef: 'fixture:validation', evidenceBundleRef: 'fixture:evidence' };
    },
    async finish(_task, result) { if (result.status === 'completed' && (!valid || result.operationId !== valid))
      throw new Error('No current verified execution'); } };
  const started = performance.now(), cpu = process.cpuUsage(), memory = process.memoryUsage();
  const result = await createCodingRun(contract, gateway, { model }).run();
  const usedCpu = process.cpuUsage(cpu), endMemory = process.memoryUsage();
  return { success: result.status === 'completed', wallMs: performance.now() - started,
    modelCalls: calls, pythonExecutionAdmissions: executions, modelInputBytes: inputBytes,
    sdkCpuMs: (usedCpu.user + usedCpu.system) / 1000,
    sdkRssDeltaBytes: endMemory.rss - memory.rss, sdkHeapDeltaBytes: endMemory.heapUsed - memory.heapUsed };
}

const count = 20;
const warmupRoundsPerMode = 5;
for(let index=0;index<warmupRoundsPerMode;index++) {
  await sample(false,-index-1);await sample(false,-index-1,true);await sample(true,-index-1);
}
const before = [], after = [], beforeFullSource = [];
for (let index = 0; index < count; index++) {
  before.push(await sample(false, index));
  beforeFullSource.push(await sample(false, index, true));
  after.push(await sample(true, index));
}
function summary(values) {
  const mean = key => values.reduce((sum, value) => sum + value[key], 0) / values.length;
  const times = values.map(value => value.wallMs).sort((a, b) => a - b);
  return { tasks: values.length, successfulTasks: values.filter(value => value.success).length,
    successRate: values.filter(value => value.success).length / values.length,
    wallMsP50: times[Math.floor(times.length * 0.5)], wallMsP95: times[Math.floor(times.length * 0.95)],
    modelCallsMean: mean('modelCalls'), pythonExecutionAdmissionsMean: mean('pythonExecutionAdmissions'),
    modelInputBytesMean: mean('modelInputBytes'), sdkCpuMsMean: mean('sdkCpuMs'),
    sdkRssDeltaBytesMean: mean('sdkRssDeltaBytes'), sdkHeapDeltaBytesMean: mean('sdkHeapDeltaBytes') };
}
const report = { scope: 'CONTROLLED_SDK_REGRESSION_FIXTURE', actualProvider: false, actualSandbox: false,
  warmupRoundsPerMode,
  tokenUsage: null, sandboxCpuUsage: null, sandboxMemoryUsage: null,
  note: 'Same semantic proposals; before uses exact edits, beforeFullSource uses complete-source revisions. Execution admissions are fixture counters. SDK timings and process CPU/memory deltas exclude real provider and Sandbox. Memory deltas include GC/runtime noise and are not peak memory. Not a production success-rate estimate.',
  before: summary(before), beforeFullSource: summary(beforeFullSource), after: summary(after) };
if (report.before.successfulTasks !== 0 || report.after.successfulTasks !== count)
  throw new Error('Controlled acceptance did not meet its declared outcomes');
process.stdout.write(JSON.stringify(report, null, 2) + '\n');
