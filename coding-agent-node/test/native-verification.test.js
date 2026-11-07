import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {nativeVerificationFact,nativeResourceFacts} from '../src/native-verification.js';

const hash='a'.repeat(64),other='b'.repeat(64);
const receipt=()=>({manifestHash:hash,operationId:'real-operation',commandHash:other,exitCode:0,resultAvailable:true,
  executorStopped:true,stopProofRef:'independent:stop',verificationPolicy:'BASH_PIPEFAIL_V1',declaredCommandSha256:other});

test('verification projection preserves exact identities without exposing unrelated payloads or granting completion',()=>{
  const raw={...receipt(),prompt:'private',authorization:{network:'ALL'},source:'private source'};
  const fact=nativeVerificationFact('task',hash,raw,()=>true);
  assert.equal(fact.verificationStatus,'PASSED');assert.equal(fact.taskId,'task');assert.equal(fact.executionOperationId,raw.operationId);
  assert.equal(fact.commandHash,other);assert.equal(fact.codeManifestHash,hash);assert.equal(fact.stopProofRef,raw.stopProofRef);
  for(const key of ['prompt','authorization','source','completed'])assert.ok(!(key in fact));
  assert.equal(raw.prompt,'private');
});

test('failure, missing output, absent stop proof and stale versions remain distinct',()=>{
  const failed={...receipt(),exitCode:1,stderr:'NameError: variable is undefined'};
  assert.equal(nativeVerificationFact('task',hash,failed,()=>true).verificationStatus,'FAILED');
  assert.equal(nativeVerificationFact('task',hash,{...receipt(),resultAvailable:false},()=>true).verificationStatus,'UNAVAILABLE');
  assert.equal(nativeVerificationFact('task',hash,{...receipt(),stopProofRef:''},()=>true).verificationStatus,'UNVERIFIED');
  assert.equal(nativeVerificationFact('task',hash,{...receipt(),resultAvailable:undefined},()=>true).verificationStatus,'UNVERIFIED');
  const stale=nativeVerificationFact('task',other,receipt(),()=>true);
  assert.equal(stale.verificationStatus,'STALE');assert.equal(stale.executedVersionStatus,'PASSED');
  assert.equal(nativeVerificationFact('task',hash,receipt(),()=>false).verificationStatus,'NOT_VERIFIED');
});

test('only bounded actual failure diagnostics are retained with the hash of visible original text',()=>{
  const stderr='界🙂'.repeat(3000)+'\nTypeError: actual error';
  const fact=nativeVerificationFact('task',hash,{...receipt(),exitCode:1,stderr},()=>true);
  assert.equal(Array.from(fact.diagnostics.stderrTail).length,1024);
  assert.ok(fact.diagnostics.stderrTail.endsWith('TypeError: actual error'));
  assert.equal(fact.diagnostics.stderrVisibleSha256,createHash('sha256').update(stderr).digest('hex'));
  assert.equal(fact.diagnostics.stderrTailTruncated,true);
  assert.ok(!nativeVerificationFact('task',hash,{...receipt(),stderr},()=>true).diagnostics);
});

test('resource projection uses charged original quotas and represents missing measurements as unknown',()=>{
  const contract={budget:{maxCodeExecutions:4}};
  assert.equal(nativeResourceFacts(contract,{chargedUsage:{codeExecutions:3}}).remainingCodeExecutions,1);
  assert.equal(nativeResourceFacts(contract,{chargedUsage:{codeExecutions:4}}).remainingCodeExecutions,0);
  assert.equal(nativeResourceFacts(contract,{}).remainingCodeExecutions,null);
  assert.equal(nativeResourceFacts(contract,{chargedUsage:{codeExecutions:-1}}).remainingCodeExecutions,null);
  assert.equal(nativeResourceFacts(contract,{}).remainingTokens,null);
  assert.equal(contract.budget.maxCodeExecutions,4);
});
