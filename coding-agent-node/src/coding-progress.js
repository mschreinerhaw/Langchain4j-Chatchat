import { createHash } from 'node:crypto';

const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const boundedThreshold = (value, fallback) => {
  const parsed = value === undefined ? fallback : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0 || parsed > 32) throw new TypeError('Progress threshold must be 0..32');
  return parsed;
};

/** Advisory observations within one SDK run. No transcript, execution authority or model loop. */
export function createCodingProgress({ preparationCalls, stalledCalls, now = Date.now } = {}) {
  const preparation = boundedThreshold(preparationCalls, 4), stalled = boundedThreshold(stalledCalls, 3);
  const started = now(), seen = new Set(), failures = new Set(), executions = new Set();
  let newResults = false, rounds = 0, stalledRounds = 0;
  const state = { modelCall: 0, callsSinceProgress: 0, newToolResults: 0, newEvidenceReads: 0,
    skillReads: 0, discoveryCalls: 0, repeatedFailures: 0, proposals: 0, acceptedProposals: 0,
    rejectedProposals: 0, pythonDispatched: 0, pythonExecutions: 0, modelResponseErrors: 0,
    knownInputTokens: 0, knownOutputTokens: 0, firstProposalMs: null, firstValidCodeMs: null,
    firstPythonMs: null, firstResultMs: null, remainingTokens: null, remainingModelCalls: null,
    waitingFor: 'MODEL', phase: 'PREPARING', lastTool: '', feedbackCount: 0 };
  const elapsed = () => Math.max(0, now() - started);
  const snapshot = () => ({ ...state });
  return {
    snapshot,
    observe(event) {
      if (event.type === 'CODE_PROPOSED') { state.proposals++; state.firstProposalMs ??= elapsed(); state.phase = 'WRITING'; }
      if (event.type === 'CODE_READY') { state.acceptedProposals++; state.firstValidCodeMs ??= elapsed(); newResults = true; }
      if (event.type === 'CODE_PROPOSAL_REJECTED') { state.rejectedProposals++; state.phase = 'REPAIRING'; }
      if (event.type === 'MODEL_RESPONSE_REJECTED' || event.type === 'MODEL_FAILED') state.modelResponseErrors++;
      if (event.type === 'SDK_TOOL_STARTED') {
        state.lastTool = event.tool;
        state.waitingFor = event.tool === 'python_execute' ? 'CODE' : event.tool === 'skill_read' ? 'SKILL' : 'TOOL';
        state.phase = event.tool === 'python_execute' ? 'WRITING'
          : event.tool === 'skill_read' || event.tool === 'tool_describe' ? 'PREPARING' : 'EXPLORING';
      }
      if (event.type === 'TOOL_STARTED') {
        state.waitingFor = event.tool === 'python_execute' ? 'PYTHON' : 'TOOL';
        if (event.tool === 'python_execute') { state.pythonDispatched++; state.firstPythonMs ??= elapsed(); state.phase = 'EXECUTING'; }
      }
      if (event.type === 'TOOL_FINISHED' && event.resultAvailable) state.firstResultMs ??= elapsed();
      if (event.type === 'TOOL_FINISHED' && event.tool === 'python_execute' && event.executorStopped === true
          && event.operationId && !executions.has(event.operationId) && executions.size < 64) {
        executions.add(event.operationId); state.pythonExecutions++;
      }
    },
    tool(call, result) {
      const name = call.toolName, output = result.output;
      state.lastTool = name;
      if (name === 'skill_read') state.skillReads++;
      if (name === 'tool_describe') state.discoveryCalls++;
      const failure = result.isError === true || output?.status === 'FAILED'
        || name === 'python_execute' && output?.resultAvailable === false
        || output?.reportReferenceValidation?.valid === false;
      const failureDetail = output?.executionFeedback?.error ?? output?.error ?? output;
      const identity = hash([name, call.input, failure ? failureDetail : output]);
      const inventory = failure ? failures : seen;
      if (failure && inventory.has(identity)) state.repeatedFailures++;
      if (!inventory.has(identity) && inventory.size < 512) {
        inventory.add(identity);
        if (!failure) {
          state.newToolResults++; newResults = true;
          if (name !== 'skill_read' && /(?:read|search|snapshot|history|indicators|metadata)/.test(name)) state.newEvidenceReads++;
        }
      }
      state.phase = failure ? 'REPAIRING' : name === 'skill_read' || name === 'tool_describe' ? 'PREPARING'
        : name === 'artifact_read' ? 'REVIEWING' : name === 'python_execute' ? 'REVIEWING' : 'EXPLORING';
      state.waitingFor = 'MODEL';
    },
    model(ordinal, budget) {
      rounds++; stalledRounds = newResults ? 1 : stalledRounds + 1; newResults = false;
      state.modelCall = ordinal; state.callsSinceProgress = stalledRounds; state.waitingFor = 'MODEL';
      state.remainingTokens = Number.isFinite(budget?.remainingTokens) ? budget.remainingTokens : null;
      state.remainingModelCalls = Number.isFinite(budget?.remainingModelCalls) ? budget.remainingModelCalls : null;
      const idle = stalled > 0 && stalledRounds >= stalled && stalledRounds % stalled === 0;
      const preparing = preparation > 0 && !state.acceptedProposals && rounds >= preparation && rounds % preparation === 0;
      const feedback = idle || preparing ? (state.feedbackCount++,
        '\nCoding progress check (advisory, within this same authorized SDK session): '
        + JSON.stringify({ reason: idle ? 'NO_NEW_TOOL_RESULT' : 'PREPARATION_WITHOUT_CODE', ...snapshot() })
        + '\nChoose the next useful action. Reuse already read evidence and Skills; avoid repeating unchanged failures or planning. '
        + 'If the supplied evidence is sufficient, submit a focused initial program now. Otherwise continue necessary authorized discovery or state the exact missing input. '
        + 'Complex analysis may continue; this feedback grants no permissions, budget or evidence and does not require unsafe or unsupported code.') : '';
      return { progress: snapshot(), feedback };
    },
    usage(usage) {
      if (Number.isFinite(usage?.inputTokens)) state.knownInputTokens += usage.inputTokens;
      if (Number.isFinite(usage?.outputTokens)) state.knownOutputTokens += usage.outputTokens;
    },
  };
}
