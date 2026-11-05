/** Evaluate an explicitly admitted, immutable cohort using Runtime evidence, never monitor status alone. */
export function completionEvaluation(tasks) {
  const known = value => Number.isSafeInteger(value) && value >= 0;
  let successes=0, delivered=0, knownTokens=0, calls=0, unknownUsage=0, eligible=0, recovered=0;
  let repeat=0, bytes=0, measuredRequests=0;
  const firstValid=[];
  const taskIds=new Set();
  for (const task of tasks) {
    if (!task.taskId || taskIds.has(task.taskId)) throw new TypeError('Distinct admitted task identities required');
    taskIds.add(task.taskId);
    const models=task.modelCalls ?? [];
    calls+=models.length;
    let taskTokens=0, usageComplete=true;
    for(const model of models) {
      if(known(model.usage?.inputTokens) && known(model.usage?.outputTokens)) {
        const tokens=model.usage.inputTokens+model.usage.outputTokens;knownTokens+=tokens;taskTokens+=tokens;
      } else {unknownUsage++;usageComplete=false;}
    }
    const valid=(task.deliverables ?? []).filter(asset=>asset.integrityVerified===true && asset.structureVerified===true
      && asset.resultDatasetRef && asset.validationRecordRef && asset.evidenceBundleRef);
    if(valid.length) {
      delivered++;
      const observed=valid.map(asset=>Date.parse(asset.verifiedAt)).filter(Number.isFinite);
      const elapsed=Math.min(...observed)-Date.parse(task.startedAt);
      if(Number.isFinite(elapsed)&&elapsed>=0)firstValid.push(elapsed);
    }
    const budget=task.budget;
    if(task.runtimeStatus==='COMPLETED' && task.requirementsVerified===true && valid.length && usageComplete && budget
        && known(budget.maxModelTokens) && taskTokens<=budget.maxModelTokens
        && known(budget.maxModelCalls) && models.length<=budget.maxModelCalls
        && Date.parse(task.endedAt)<=Date.parse(budget.deadlineAt))successes++;
    for(const reconciliation of task.reconciliations ?? []) {
      eligible++;
      if(reconciliation.originalOperationId===reconciliation.recoveredOperationId
          && reconciliation.stopProofVerified===true && reconciliation.originalTaskContinued===true
          && reconciliation.duplicateDispatch===false)recovered++;
    }
    for(const event of task.timingEvents ?? []) {
      if(known(event.avoidableRepeatedContextBytes)&&known(event.requestBytes)&&event.avoidableRepeatedContextBytes<=event.requestBytes) {
        repeat+=event.avoidableRepeatedContextBytes;bytes+=event.requestBytes;measuredRequests++;
      }
    }
  }
  return {schemaVersion:'runtime_os.coding.completion_evaluation.v1',admittedTasks:tasks.length,
    budgetedTaskSuccessRate:tasks.length?successes/tasks.length:null,
    verifiedDeliverableRate:tasks.length?delivered/tasks.length:null,
    successfulTasks:successes,knownTokens,unknownUsageCalls:unknownUsage,
    // Includes costs of FAILED tasks; unknown provider usage must not become zero.
    tokensPerSuccessfulTask:successes&&unknownUsage===0?knownTokens/successes:null,
    modelCallsPerSuccessfulTask:successes?calls/successes:null,
    firstValidResultMillis:firstValid,firstValidResultCensoredTasks:tasks.length-firstValid.length,
    reconciliationRecoveryRate:eligible?recovered/eligible:null,reconciliationOperations:eligible,
    wastedRepeatedContextRatio:bytes?repeat/bytes:null,contextMeasurementRequests:measuredRequests,
    contextRatioUnit:'EXACT_DUPLICATE_READ_OUTPUT_UTF8_BYTES_NOT_PROVIDER_TOKENS'};
}
