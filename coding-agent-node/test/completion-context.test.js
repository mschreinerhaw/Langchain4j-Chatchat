import test from 'node:test';
import assert from 'node:assert/strict';
import { technicalMemory, budgetGuidance, contextEstimate } from '../src/completion-context.js';
import { sdkPrepareTurn } from '../src/sdk-adapter.js';

test('unknown operations block inference instead of authorizing replacement execution', () => {
  const view = { schemaVersion:'runtime_os.coding.budget.v1', remainingTokens:1000, remainingModelCalls:2,
    remainingMillis:30000, pendingOperations:[{operationId:'original-operation'}] };
  assert.throws(()=>budgetGuidance(view),/CODING_RECONCILIATION_REQUIRED/);
  assert.throws(()=>budgetGuidance({...view,pendingOperations:[],remainingTokens:0}),/exhausted/);
  assert.match(budgetGuidance({...view,pendingOperations:[]}),/Unknown usage stays reserved/);
});

test('native compaction preserves the exact failed execution diagnostic without a summarizing model', async () => {
  const memory=technicalMemory({operationId:'same-python-operation',status:'FAILED',executorStopped:true,codeHash:'a'.repeat(64),
    executionFeedback:{error:{category:'ASSERTION_ERROR',message:'Assertion failed',stack:[{file:'main.py',line:541}]},
      repair:{allowed:true,baseCodeHash:'a'.repeat(64)},resultAvailable:false}}, {baseCodeSha256:'a'.repeat(64),snippets:['assert actual == expected']});
  let reads=0;
  const prepare=sdkPrepareTurn('budget-compaction',()=>null,{statefulCompactionEnabled:true,technicalMemory:()=>memory,
    budgetSnapshot:async()=>{reads++;return {remainingTokens:4000,remainingModelCalls:2};}});
  const messages=[{role:'user',content:[{type:'text',text:'Original authorized objective.'}]}];
  for(let i=0;i<8;i++)messages.push({role:i%2?'assistant':'user',content:[{type:'text',text:'historical context '.repeat(1000)}]});
  const result=await prepare({messages,systemPrompt:'Runtime tools only',tools:[],model:{info:{maxInputTokens:128000}}});
  assert.equal(reads,1);assert.ok(prepare.measurement().compactionCount>0);
  const text=JSON.stringify(result.messages);
  assert.match(text,/same-python-operation/);assert.match(text,/541/);assert.match(text,/assert actual == expected/);
  assert.ok(prepare.snapshot().compactionState);
  assert.equal(contextEstimate({messages:[]}),Math.ceil(Buffer.byteLength(JSON.stringify({messages:[]}))/4));
});
