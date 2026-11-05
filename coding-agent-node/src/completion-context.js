/** Only Runtime receipts and estimates; no business conclusions or second transcript. */
export function technicalMemory(receipt, sourceContext) {
  const feedback = receipt?.executionFeedback;
  const compactFeedback = feedback ? Object.fromEntries([
    'schemaVersion','taskId','executionId','status','stage','exitCode','durationMs','codeHash',
    'error','repair','outputStatus','validationStatus','resultAvailable',
  ].filter(key => feedback[key] !== undefined).map(key => [key,feedback[key]])) : undefined;
  const memory = { operationId: receipt?.operationId, status: receipt?.status,
    executorStopped: receipt?.executorStopped, resultAvailable: receipt?.resultAvailable === true,
    codeHash: receipt?.codeHash, codeVersion: receipt?.codeVersion,
    resultDatasetRef: receipt?.resultDatasetRef, validationRecordRef: receipt?.validationRecordRef,
    evidenceBundleRef: receipt?.evidenceBundleRef, validationStatus: receipt?.validationStatus,
    executionFeedback: compactFeedback, repairSourceContext: sourceContext };
  // Keep the normalized error and source identity if a large source excerpt exceeds the bound.
  if (JSON.stringify(memory).length > 16000) delete memory.repairSourceContext;
  if (JSON.stringify(memory).length > 16000) delete memory.executionFeedback;
  return memory;
}

export function budgetGuidance(view) {
  if (view?.schemaVersion !== 'runtime_os.coding.budget.v1') throw new TypeError('Invalid Runtime budget snapshot');
  if (view.pendingOperations?.length) throw new Error('CODING_RECONCILIATION_REQUIRED: preserve original operation identities');
  if (view.remainingMillis <= 0 || view.remainingModelCalls <= 0 || view.remainingTokens <= 0)
    throw new Error('Runtime Coding budget exhausted');
  const { remainingTokens, remainingModelCalls, remainingMillis, contextTokensEstimate,
    status, verifiedDeliverables, pendingOperations, unknownUsageOperations, unresolvedReason } = view;
  const { phase, reservedTokens, reservedCalls, reservedMillis } = view.completionReservation ?? {};
  return '\nAuthoritative Runtime task resources (context is estimated): ' + JSON.stringify({
    remainingTokens, remainingModelCalls, remainingMillis, contextTokensEstimate, status,
    verifiedDeliverables, pendingOperations, unknownUsageOperations, unresolvedReason,
    completionReservation:{phase,reservedTokens,reservedCalls,reservedMillis} })
    + '\nCompletion allotment is within this budget. Prioritize verified delivery in COMPLETION; keep autonomous tools and necessary repairs. Reuse archived results; disclose evidence gaps. Never fabricate evidence or replace this task. Unknown usage stays reserved.';
}

export function contextEstimate(request) {
  return Math.ceil(Buffer.byteLength(JSON.stringify({ systemPrompt: request.systemPrompt,
    messages: request.messages, tools: request.tools })) / 4);
}
