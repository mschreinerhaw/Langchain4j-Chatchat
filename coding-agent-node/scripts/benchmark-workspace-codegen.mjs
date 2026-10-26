/** Real installed SDK, deterministic provider/validator. Not a live-model latency benchmark. */
import { pathToFileURL } from 'node:url';
import { createCodingRun } from '../src/runner.js';

export function workspaceTask(fast, language = 'python') {
  return { schemaVersion: 'runtime_os.coding.v2', taskId: 'workspace-benchmark',
    scope: { tenantId: 'fixture', userId: 'owner', runId: 'benchmark' },
    objective: { instruction: 'Produce a source candidate using the authorized immutable workspace context.' },
    inputSnapshotRef: 'fixture:context', validationPlanRef: 'fixture:parse-and-review',
    metadataRefs: fast ? ['runtime-coding:workspace-fast-path:v1'] : [],
    authorization: { allowedTools: ['workspace_read', 'workspace_propose'], allowedDataRefs: [],
      allowedSideEffects: ['WORKSPACE_WRITE'], networkPolicy: 'NONE' },
    outputContract: { resultFormat: 'WORKSPACE_FILES', resultSchemaRef: `${language}-studio.output.v1` },
    budget: { maxModelCalls: 6, deadlineAt: new Date(Date.now() + 10000).toISOString() } };
}

export async function sample(fast, language = 'python', invalidProposals = 0) {
  const task = workspaceTask(fast, language);
  let calls = 0, reads = 0, proposals = 0, inputBytes = 0, finish, lastSource;
  const snapshots = [];
  const source = (language === 'python' ? '# revision 0\nvalues = [1, 2, 3]\nassert sum(values) == 6\n' : '-- revision 0\nSELECT 1 + 2 + 3 AS total;');
  const gateway = { async reserveModel() {}, async settleModel() {},
    async readWorkspace() { reads++; return { context: 'AUTHORIZED_SNAPSHOT: generate the requested source.', dialect: language }; },
    async proposeWorkspace(_contract, id, input) {
      lastSource = input.files[0].content;
      proposals++;
      const valid = proposals > invalidProposals;
      return { valid, resultAvailable: valid, operationId: id,
        validation: { diagnostics: valid ? [] : [{ category: 'SYNTAX', file: input.files[0].path, line: 1, status: 'FAILED' }] },
        ...(valid ? { codeArtifactRef: 'evidence:code', executionReceiptRef: 'evidence:receipt',
          resultDatasetRef: 'evidence:result', validationRecordRef: 'evidence:validation', evidenceBundleRef: 'evidence:bundle' } : {}) };
    },
    async checkpoint(_contract, checkpoint) { snapshots.push(structuredClone(checkpoint)); },
    async finish(_contract, outcome) { finish = outcome; } };
  const model = { async *stream(request) {
    calls++; inputBytes += Buffer.byteLength(JSON.stringify(request));
    const text = JSON.stringify(request.messages);
    const needsRead = !text.includes('AUTHORIZED_SNAPSHOT');
    const needsProposal = proposals <= invalidProposals;
    if (needsRead || needsProposal) yield { type: 'tool-call-delta', toolCallId: 'tool-' + calls,
      toolName: needsRead ? 'workspace_read' : 'workspace_propose',
      input: needsRead ? {} : { files: [{ path: language === 'python' ? 'main.py' : 'query.sql',
        ...(fast && proposals > 0 ? { repair: { baseCodeSha256: (await import('node:crypto')).createHash('sha256').update(lastSource).digest('hex'),
          edits: [{ oldText: `revision ${proposals - 1}`, newText: `revision ${proposals}` }] } }
          : { content: source.replace('revision 0', `revision ${proposals}`) }) }], notes: 'Source candidate; review required.' } };
    else yield { type: 'text-delta', text: 'Candidate parsed; review required.' };
    yield { type: 'finish', reason: needsRead || needsProposal ? 'tool-calls' : 'stop' };
  } };
  const started = performance.now();
  const run = createCodingRun(task, gateway, { model });
  const result = await run.run();
  return { status: result.status, modelCalls: calls, workspaceReads: reads, proposals,
    requestBytes: inputBytes, sdkWallMs: performance.now() - started, generatedSource: lastSource,
    providerLatencyMs: null, tokenUsage: null, cost: null, finish, snapshots, events: run.events };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const cases = [];
  for (const language of ['python', 'sql']) for (const repairs of [0, 2]) {
    const before = await sample(false, language, repairs);
    const after = await sample(true, language, repairs);
    const metrics = s => Object.fromEntries(['status', 'modelCalls', 'workspaceReads', 'proposals', 'requestBytes', 'sdkWallMs', 'providerLatencyMs', 'tokenUsage', 'cost'].map(k => [k, s[k]]));
    cases.push({ language, injectedParserFailures: repairs, sameSource: before.generatedSource === after.generatedSource,
      before: metrics(before), after: metrics(after) });
  }
  console.log(JSON.stringify({ scope: 'INSTALLED_SDK_DETERMINISTIC_FIXTURE', actualProvider: false,
    actualSandbox: false, sdkVersion: '0.0.93', cases }, null, 2));
}
