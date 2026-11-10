import test from 'node:test';
import assert from 'node:assert/strict';
import {admitModelResponseRepair,observeModelResponse} from '../src/business/native-policy.mjs';
const truncated={error:'Actual truncated response',modelResponseFeedback:{schemaVersion:'runtime_os.coding.model_feedback.v1',repairAllowed:true,diagnosticCode:'MODEL_OUTPUT_TRUNCATED',maxRepairAttempts:2}};

test('an unlimited Runtime grant preserves actual response-repair counts beyond the old two/eight limits',()=>{
 for(const limit of ['unlimited',-1]){
  const run={};const receipt={...truncated,modelResponseFeedback:{...truncated.modelResponseFeedback,maxRepairAttempts:limit}};
  observeModelResponse(run,receipt);
  for(let i=0;i<40;i++){admitModelResponseRepair(run);observeModelResponse(run,receipt);}
  assert.equal(run.modelResponseRepairs,40);assert.equal(run.modelResponseRepairLimitReached,undefined);
  observeModelResponse(run,{error:'Permission denied',modelResponseFeedback:{repairAllowed:false}});
  assert.equal(run.modelResponseFeedback,null);
 }
});
test('Native cannot exceed the two response repairs granted by Runtime',()=>{
 const run={};
 admitModelResponseRepair(run);observeModelResponse(run,truncated);
 for(let i=0;i<2;i++){admitModelResponseRepair(run);observeModelResponse(run,truncated);}
 assert.throws(()=>admitModelResponseRepair(run),/MODEL_RESPONSE_REPAIR_LIMIT_REACHED/);
 assert.equal(run.modelResponseRepairLimitReached,true);
 assert.equal(run.modelResponseRepairs,2);
});
test('a successful response ends the repair sequence and missing grants create no retries',()=>{
 const run={};observeModelResponse(run,truncated);admitModelResponseRepair(run);
 observeModelResponse(run,{text:'Actual complete response'});
 assert.equal(run.modelResponseFeedback,null);assert.equal(run.modelResponseRepairs,0);
 observeModelResponse(run,{error:'Transport failure'});admitModelResponseRepair(run);
 assert.equal(run.modelResponseFeedback,null);
});

test('an unrelated or invalid error receipt revokes the previous response repair grant',()=>{
 for(const answer of [{error:'Transport failure'},
  {error:'Denied',modelResponseFeedback:{...truncated.modelResponseFeedback,repairAllowed:false}},
  {error:'Invalid grant',modelResponseFeedback:{...truncated.modelResponseFeedback,maxRepairAttempts:0}}]){
  const run={};observeModelResponse(run,truncated);admitModelResponseRepair(run);
  observeModelResponse(run,answer);
  assert.equal(run.modelResponseFeedback,null);
  assert.equal(run.modelResponseRepairs,1,'Revocation preserves actual repair usage');
 }
});
