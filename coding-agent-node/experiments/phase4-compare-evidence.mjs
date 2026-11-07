/** Post-stop comparison of existing evidence only; no model or task mutations. */
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
const root=resolve(process.argv[2]??'.'),repeat=Number(process.argv[3]??2);
const oldDir=resolve(root,'docs/testing/evidence/native-coding-20261107');
const newDir=resolve(root,`docs/testing/evidence/native-coding-phase4/after-r${repeat}`);
const read=async path=>JSON.parse(await readFile(path,'utf8'));
const oldSummary=await read(resolve(oldDir,'C-general-r4-summary.json'));
const oldReceipts=await read(resolve(oldDir,'C-general-r4-receipts.json'));
const newSummary=await read(resolve(newDir,'summary.json'));
const metrics=await read(resolve(newDir,'metrics.json'));
const stableBudget=value=>Object.fromEntries(Object.entries(value).filter(([k])=>!['budgetId','deadlineAt'].includes(k)));
const sorted=value=>JSON.stringify([...value].sort());
const one=(values,predicate)=>{const found=values.filter(predicate);assert.equal(found.length,1);return found[0];};
const checks=[];
for(const result of newSummary.results){
 const previous=one(oldSummary.results,x=>x.case===result.case);
 // Historical receipt files contain more than one cohort: join by exact task ID.
 const old=one(oldReceipts.results,x=>x.taskId===previous.taskId);
 const current=await read(resolve(newDir,result.case+'-receipts.json'));
 const facts=await read(resolve(newDir,result.case+'-model-facts.json'));
 const first=old.modelReceipts[0].modelInfo;
 const controls={requestSha256Unchanged:previous.requestSha256===result.requestSha256,
  fixtureUnchanged:previous.fixtureSha256===result.fixtureSha256,harnessUnchanged:previous.harnessSha256===result.harnessSha256,
  modelConnectionUnchanged:JSON.stringify(old.frozenModel)===JSON.stringify(current.frozenModel),
  frozenLimitsUnchanged:JSON.stringify(stableBudget(old.frozenBudget))===JSON.stringify(stableBudget(current.frozenBudget)),
  modelAndToolsUnchanged:current.modelReceipts.every(m=>m.modelInfo.selectedModel===first.selectedModel
   &&m.modelInfo.providerModel===first.providerModel&&m.modelInfo.maxOutputTokens===first.maxOutputTokens
   &&sorted(m.modelInfo.authorizedTools)===sorted(first.authorizedTools)
   &&sorted(m.modelInfo.disclosedTools)===sorted(first.disclosedTools)&&m.modelInfo.toolSchemaSha256===first.toolSchemaSha256),
  factObservationForEveryAdmittedModel:current.modelReceipts.every(m=>facts.some(f=>f.operationId===m.operationId
   &&f.toolSchemaSha256===m.modelInfo.toolSchemaSha256&&sorted(f.disclosedTools)===sorted(m.modelInfo.disclosedTools))),
  allCodeOperationsStopped:current.codeOperations.every(op=>Boolean(op.stopProofRef)),
  originalTaskNotChangedByHoldout:result.taskUsageUnchangedByHoldout===true,
  archiveReadAgain:result.archivedFilesReadAgain===true};
 checks.push({case:result.case,oldTaskId:previous.taskId,newTaskId:result.taskId,controls,
  allControlsPassed:Object.values(controls).every(Boolean),observedRequestCount:facts.length,
  observedVerificationStatuses:[...new Set(facts.map(f=>f.verification.verificationStatus))].sort(),
  compactionCount:current.sdkCheckpoint.compactionCount,
  completed:result.completed,finalSourcePassed:result.independent?.passed===true,
  finalManifestHash:result.manifestHash,elapsedHarnessMs:result.endedAtEpochMs-result.startedAtEpochMs});
}
const report={scope:'POST_STOP_CONTROL_CHECKS_NOT_STATISTICAL_CAUSAL_PROOF',repeat,
 cases:checks.length,allControlsPassed:checks.every(c=>c.allControlsPassed),
 completed:metrics.completed,finalSourcesPassed:metrics.finalSourcesPassed,modelCalls:metrics.modelCalls,
 knownModelTokens:metrics.knownModelTokens,admittedToolReceipts:metrics.admittedToolReceipts,
 admittedFailedToolReceipts:metrics.admittedFailedToolReceipts,admittedToolFailureRate:metrics.admittedToolFailureRate,
 cpuMillis:null,peakMemoryBytes:null,actualCost:null,
 meteringLimit:'Original incomplete reservations retained; raw zero CPU/memory/cost fields are not actual zero consumption.',
 latencyLimit:'Post-stop harness elapsed includes independent verification. Archive first-valid times and receipt timestamps have separate definitions.',
 baselineLimit:'Historical Runtime cohort matched by task ID; official CLI not executed here and SDK reference has documented environment differences.',
 results:checks};
await writeFile(resolve(newDir,'control-checks.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({repeat,cases:report.cases,allControlsPassed:report.allControlsPassed,completed:report.completed}));
if(!report.allControlsPassed)process.exitCode=1;
