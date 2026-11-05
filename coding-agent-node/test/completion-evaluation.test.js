import test from 'node:test';
import assert from 'node:assert/strict';
import { completionEvaluation } from '../src/completion-evaluation.js';
import { createRequestContextTracker } from '../src/request-context.js';

test('completion metrics charge failed work to the admitted cohort and retain censored observations',()=>{
  const task={taskId:'success',runtimeStatus:'COMPLETED',requirementsVerified:true,startedAt:'2026-11-05T00:00:00Z',
    endedAt:'2026-11-05T00:00:20Z',budget:{maxModelTokens:100,maxModelCalls:2,deadlineAt:'2026-11-05T00:01:00Z'},
    modelCalls:[{usage:{inputTokens:10,outputTokens:5}}],
    deliverables:[{integrityVerified:true,structureVerified:true,resultDatasetRef:'r',validationRecordRef:'v',evidenceBundleRef:'b',verifiedAt:'2026-11-05T00:00:10Z'}]};
  const failed={taskId:'failed',runtimeStatus:'FAILED',modelCalls:[{usage:{inputTokens:20,outputTokens:5}}]};
  const result=completionEvaluation([task,failed]);
  assert.equal(result.budgetedTaskSuccessRate,.5);assert.equal(result.verifiedDeliverableRate,.5);
  assert.equal(result.tokensPerSuccessfulTask,40);assert.equal(result.modelCallsPerSuccessfulTask,2);
  assert.deepEqual(result.firstValidResultMillis,[10000]);assert.equal(result.firstValidResultCensoredTasks,1);
  assert.equal(result.reconciliationRecoveryRate,null);assert.equal(result.wastedRepeatedContextRatio,null);
  assert.equal(completionEvaluation([task,{...failed,modelCalls:[{}]}]).tokensPerSuccessfulTask,null);
  const untimed=completionEvaluation([{...task,deliverables:task.deliverables.map(({verifiedAt,...asset})=>asset)}]);
  assert.equal(untimed.verifiedDeliverableRate,1);assert.equal(untimed.budgetedTaskSuccessRate,1);
  assert.equal(untimed.firstValidResultCensoredTasks,1);
  assert.throws(()=>completionEvaluation([task,task]),/Distinct/);
});

test('different input pages and necessary cross-request history are not classified as wasted context',()=>{
  const track=createRequestContextTracker();
  const part=output=>({type:'tool-result',toolName:'input_read',output});
  const request={messages:[{role:'tool',content:[part({offset:0,rows:[1]}),part({offset:1,rows:[2]})]}]};
  assert.equal(track(request).avoidableRepeatedContextBytes,0);
  assert.equal(track(request).avoidableRepeatedContextBytes,0);
  request.messages[0].content.push(part({offset:0,rows:[1]}));
  assert.ok(track(request).avoidableRepeatedContextBytes>0);
});
