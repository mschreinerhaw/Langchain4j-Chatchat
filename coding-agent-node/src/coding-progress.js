import { createHash } from 'node:crypto';

const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const boundedThreshold = (value, fallback) => {
  const parsed = value === undefined ? fallback : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0 || parsed > 32) throw new TypeError('Progress threshold must be 0..32');
  return parsed;
};

/** Advisory observations restored with the existing SDK checkpoint. No transcript or authority. */
export function createCodingProgress({ preparationCalls, stalledCalls, initialCodeAvailable = false, now = Date.now, checkpoint, modelOrdinal } = {}) {
  const preparation = boundedThreshold(preparationCalls, 4), stalled = boundedThreshold(stalledCalls, 3);
  let started = now();
  const seen = new Set(), failures = new Set(), executions = new Set();
  const skills = new Set(), references = new Set();
  let newResults = false, rounds = 0, stalledRounds = 0;
  let codeAvailable = initialCodeAvailable;
  const state = { modelCall: 0, callsSinceProgress: 0, newToolResults: 0, newEvidenceReads: 0,
    skillReads: 0, discoveryCalls: 0, repeatedFailures: 0, proposals: 0, acceptedProposals: 0,
    rejectedProposals: 0, pythonDispatched: 0, pythonExecutions: 0, modelResponseErrors: 0,
    knownInputTokens: 0, knownOutputTokens: 0, firstProposalMs: null, firstValidCodeMs: null,
    firstPythonMs: null, firstResultMs: null, remainingTokens: null, remainingModelCalls: null,
    preCodeModelCalls:0, preCodeInputTokens:0, preCodeUsageKnownCalls:0,
    loadedSkillCount:0, loadedReferenceCount:0, loadedSkillBytes:0, skillDeliveredBytes:0,
    skillEstimatedTokens:0, duplicateSkillReads:0, skillReuses:0, inputReadCount:0,
    rejectedToolCallCount:0, lastProgressType:'NONE',
    waitingFor: 'MODEL', phase: 'PREPARING', lastTool: '', feedbackCount: 0 };
  // Private diagnostic state only. Old or malformed checkpoints keep the legacy
  // observation scope rather than failing an otherwise authorized SDK resume.
  const validCounter = value => Number.isSafeInteger(value) && value >= 0;
  const valid = checkpoint?.schemaVersion === 1 && validCounter(checkpoint.startedAt)
    && validCounter(checkpoint.rounds) && validCounter(checkpoint.stalledRounds)
    && typeof checkpoint.newResults === 'boolean' && typeof checkpoint.codeAvailable === 'boolean'
    && checkpoint.state && Object.entries(state).every(([key, value]) => {
      const restored = checkpoint.state[key];
      return typeof value === 'string' ? typeof restored === 'string' && restored.length <= 128
        : restored === null ? value === null : validCounter(restored);
    }) && ['seen','failures','skills','references','executions'].every(key => {
      const list = checkpoint.identities?.[key];
      return Array.isArray(list) && list.length <= (key === 'executions' ? 64 : 512)
        && list.every(value => typeof value === 'string' && (key === 'executions'
          ? value.length <= 256 : /^[a-f0-9]{64}$/.test(value)));
    });
  if (valid) {
    started = checkpoint.startedAt;
    for (const key of Object.keys(state)) state[key] = checkpoint.state[key];
    rounds = checkpoint.rounds; stalledRounds = checkpoint.stalledRounds;
    newResults = checkpoint.newResults;
    codeAvailable = initialCodeAvailable || checkpoint.codeAvailable;
    for (const [name, set] of Object.entries({seen, failures, executions, skills, references}))
      for (const value of checkpoint.identities[name]) set.add(value);
    // Java may advance the admitted ordinal after the last complete SDK message
    // boundary. Such attempts have unknown usage, not new evidence. Account for
    // them without replaying a model, settling tokens, or claiming feedback was sent.
    if (validCounter(modelOrdinal) && modelOrdinal > state.modelCall) {
      const missing = modelOrdinal - state.modelCall;
      stalledRounds = (newResults ? 1 : stalledRounds + 1) + missing - 1;
      newResults = false; rounds += missing;
      if (state.proposals === 0) state.preCodeModelCalls += missing;
      state.modelCall = modelOrdinal; state.callsSinceProgress = stalledRounds;
    }
  }
  const elapsed = () => Math.max(0, now() - started);
  const snapshot = () => ({ ...state });
  return {
    snapshot,
    checkpoint: () => ({ schemaVersion: 1, startedAt: started, rounds, stalledRounds,
      newResults, codeAvailable, state: snapshot(), identities: Object.fromEntries(
        Object.entries({seen, failures, executions, skills, references}).map(([name, set]) => [name, [...set]])) }),
    observe(event) {
      if (event.type === 'CODE_PROPOSED') { state.proposals++; state.firstProposalMs ??= elapsed(); state.phase = 'WRITING'; state.lastProgressType='CODE_PROPOSED'; }
      if (event.type === 'CODE_READY') { state.acceptedProposals++; state.firstValidCodeMs ??= elapsed(); codeAvailable = true; newResults = true; }
      if (event.type === 'CODE_PROPOSAL_REJECTED') { state.rejectedProposals++; state.phase = 'REPAIRING'; }
      if (event.type === 'MODEL_RESPONSE_REJECTED' || event.type === 'MODEL_FAILED') state.modelResponseErrors++;
      if (event.type === 'SKILLS_LOADED') {
        const key=hash([event.skillId,event.skillVersion,event.skillPath,event.skillContentHash]);
        const inventory=event.skillReference?references:skills;
        if(!inventory.has(key) && inventory.size<512){
          inventory.add(key);state.loadedSkillBytes+=event.skillSourceBytes??0;
          state.loadedSkillCount=skills.size;state.loadedReferenceCount=references.size;
        }
        state.skillDeliveredBytes+=event.skillContentBytes??0;
        state.skillEstimatedTokens+=event.skillEstimatedTokens??0;
        if(event.duplicateRead)state.duplicateSkillReads++;
        if(event.contentReused)state.skillReuses++;
      }
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
      if (name === 'input_read') state.inputReadCount++;
      const failure = result.isError === true || output?.status === 'FAILED'
        || name === 'python_execute' && output?.resultAvailable === false
        || output?.reportReferenceValidation?.valid === false;
      const failureDetail = output?.executionFeedback?.error ?? output?.error ?? output;
      if(result.isError || output?.error)state.rejectedToolCallCount++;
      const identity = hash([name, call.input, failure ? failureDetail : output]);
      const inventory = failure ? failures : seen;
      if (failure && inventory.has(identity)) state.repeatedFailures++;
      if (!inventory.has(identity) && inventory.size < 512) {
        inventory.add(identity);
        if (!failure && !(name==='skill_read' && output?.contentAlreadyInContext===true)) {
          state.newToolResults++; newResults = true; state.lastProgressType=name;
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
      if(state.proposals===0)state.preCodeModelCalls++;
      state.remainingTokens = Number.isFinite(budget?.remainingTokens) ? budget.remainingTokens : null;
      state.remainingModelCalls = Number.isFinite(budget?.remainingModelCalls) ? budget.remainingModelCalls : null;
      const idle = stalled > 0 && stalledRounds >= stalled && stalledRounds % stalled === 0;
      const preparing = preparation > 0 && !codeAvailable && stalledRounds > 1
        && rounds >= preparation && rounds % preparation === 0;
      const feedback = idle || preparing ? (state.feedbackCount++,
        '\nCoding progress check (advisory, within this same authorized SDK session): '
        + JSON.stringify({ reason: idle ? 'NO_NEW_TOOL_RESULT' : 'PREPARATION_WITHOUT_CODE',
          callsSinceProgress:state.callsSinceProgress,repeatedFailures:state.repeatedFailures,
          acceptedProposals:state.acceptedProposals,remainingTokens:state.remainingTokens,
          remainingModelCalls:state.remainingModelCalls })
        + '\nChoose the next useful action. Reuse already read evidence and Skills; avoid repeating unchanged failures or planning. '
        + 'If the supplied evidence is sufficient, submit focused code: an initial program only when none exists, otherwise a minimal repair against the latest version. Otherwise continue necessary authorized discovery or state the exact missing input. '
        + 'Complex analysis may continue; this feedback grants no permissions, budget or evidence and does not require unsafe or unsupported code.') : '';
      return { progress: snapshot(), feedback };
    },
    usage(usage) {
      if (Number.isFinite(usage?.inputTokens)) state.knownInputTokens += usage.inputTokens;
      if(state.proposals===0 && Number.isFinite(usage?.inputTokens)) {
        state.preCodeInputTokens+=usage.inputTokens;state.preCodeUsageKnownCalls++;
      }
      if (Number.isFinite(usage?.outputTokens)) state.knownOutputTokens += usage.outputTokens;
    },
  };
}
