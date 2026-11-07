/** Read-only verification of the committed Phase 4 P0 evidence and report links. */
import assert from 'node:assert/strict';
import {access,readFile,readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {dirname,resolve} from 'node:path';
const root=resolve(process.argv[2]??'.'),base=resolve(root,'docs/testing/evidence/native-coding-phase4');
const read=async path=>JSON.parse(await readFile(path,'utf8'));
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
let cases=0,models=0,commands=0,versions=0,facts=0;
const corpus=await read(resolve(base,'all-evidence-hashes.json'));
const tracked=new Set(execFileSync('git',['ls-files','-z','--','docs/testing/evidence/native-coding-phase4'],
 {cwd:root,encoding:'utf8'}).split('\0').filter(Boolean).map(name=>resolve(root,name)));
for(const [name,expected] of Object.entries(corpus)){
 assert.ok(tracked.has(resolve(base,name)),'Evidence must be Git-tracked: '+name);
 assert.equal(digest(await readFile(resolve(base,name))),expected,name);
}
for(const repeat of [1,2,3]){
 const dir=resolve(base,'after-r'+repeat),hashes=await read(resolve(dir,'evidence-hashes.json'));
 for(const [name,expected] of Object.entries(hashes))assert.equal(digest(await readFile(resolve(dir,name))),expected,name);
 for(const name of (await readdir(dir)).filter(n=>n.endsWith('-result.json'))){
  const prefix=name.slice(0,-12),result=await read(resolve(dir,name));
  const receipt=await read(resolve(dir,prefix+'-receipts.json')),history=await read(resolve(dir,prefix+'-history.json'));
  assert.equal(result.completed,true);assert.equal(result.status,'COMPLETED');assert.equal(result.independent.passed,true);
  assert.equal(result.independent.exitCode,0);assert.equal(result.independent.executorStopped,true);
  assert.equal(result.archivedFilesReadAgain,true);assert.equal(result.taskUsageUnchangedByHoldout,true);
  assert.equal(receipt.modelReceipts.length,result.usage.consumed.modelCalls);
  assert.equal(receipt.codeOperations.length,result.usage.consumed.codeExecutions);
  assert.ok(receipt.codeOperations.every(v=>v.startIntent&&v.stopProofRef));
  assert.ok(history.versions.every(v=>v.assetReadAgainIdentical&&v.independent.executorStopped));
  assert.equal(history.originalTaskUnchanged,true);
  if(repeat>1){
   const observed=await read(resolve(dir,prefix+'-model-facts.json'));facts+=observed.length;
   for(const model of receipt.modelReceipts)assert.equal(observed.filter(v=>v.operationId===model.operationId).length,1);
   const last=observed.at(-1),verification=last.verification;
   assert.equal(verification.verificationStatus,'PASSED');assert.equal(verification.codeManifestHash,result.manifestHash);
   const original=receipt.commandObservations.find(v=>v.operationId===verification.executionOperationId);
   assert.ok(original);assert.equal(original.declaredCommandMatched,true);assert.equal(original.exitCode,0);
   assert.equal(original.stopProofRef,verification.stopProofRef);assert.equal(original.manifestHash,result.manifestHash);
   assert.equal(last.budget.chargedCodeExecutions,result.usage.consumed.codeExecutions);
   assert.equal(last.budget.remainingCodeExecutions,4-result.usage.consumed.codeExecutions);
  }
  cases++;models+=receipt.modelReceipts.length;commands+=receipt.codeOperations.length;versions+=history.versions.length;
 }
}
const recovery=await read(resolve(base,'recovery/result-public.json'));
for(const key of ['completed','sameSdkSessionId','contractHashUnchanged','sameVerifiedReceiptOnRestore',
 'sameModelOrdinalOnRestore','originalExecutionsRetained','allStartedOperationsStopped','archiveReadAgain','taskUsageUnchangedByHoldout'])assert.equal(recovery[key],true,key);
const security=await read(resolve(base,'final/security/result-public.json'));assert.equal(security.completed,true);
const census=await read(resolve(base,'final/phase4-final-stop-census-public.json'));
assert.equal(census.allTerminalAndStopped,true);assert.equal(census.workerReady.activeSessions,0);assert.equal(census.fixedSandboxRunning.length,0);
assert.ok(census.originalFailedTasks.every(v=>v.statusUnchanged&&v.usageUnchanged&&v.executionControlUnchanged));
const reports=['docs/audits/native-coding-phase4-failure-analysis.md','docs/audits/native-cline-sdk-capability-gap-phase4.md',
 'docs/design/native-coding-phase4-design.md',...['regression','recovery','comparison'].map(n=>'docs/testing/native-coding-phase4-'+n+'.md'),
 'docs/audits/native-coding-phase4-final-review.md'];
for(const relative of reports){
 const path=resolve(root,relative),body=await readFile(path,'utf8');
 for(const match of body.matchAll(/\]\(([^)#]+)(?:#[^)]*)?\)/g)){
  if(!/^(https?:|\/)/.test(match[1]))await access(resolve(dirname(path),match[1]));
 }
}
console.log(JSON.stringify({scope:'READ_ONLY_EVIDENCE_VERIFICATION',fixedCases:cases,admittedModels:models,
 originalCodeCommands:commands,historicalVersions:versions,actualRequestFacts:facts,
 recoveryCompleted:recovery.completed,securityCompleted:security.completed,terminalTasks:census.taskCount,
 evidenceFilesHashed:Object.keys(corpus).length,reportsLinked:reports.length}));
