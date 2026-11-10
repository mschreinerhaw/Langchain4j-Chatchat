import test from 'node:test';
import assert from 'node:assert/strict';
import {Agent} from '@cline/agents';
import {hasLimitRoom,sdkIterationLimit,validLimit} from '../src/execution-limits.js';
import {budgetGuidance} from '../src/completion-context.js';

test('unlimited bypasses count ceilings without making finite policy or counters permissive',()=>{
  for(const value of ['unlimited',-1]){
    assert.equal(sdkIterationLimit(value),undefined);
    assert.equal(hasLimitRoom(value,1000000),true);
  }
  assert.equal(sdkIterationLimit(65),65);
  assert.equal(hasLimitRoom(12,12),false);
  for(const value of [-2,NaN,Infinity,undefined,'12'])assert.equal(validLimit(value),false);
  assert.throws(()=>hasLimitRoom('unlimited',-1),/Invalid/);
});

test('unlimited counts and tokens keep deadline and unresolved-operation protections',()=>{
  const view={schemaVersion:'runtime_os.coding.budget.v1',remainingTokens:'unlimited',remainingModelCalls:'unlimited',remainingMillis:30000,pendingOperations:[]};
  assert.match(budgetGuidance(view),/unlimited/);
  assert.throws(()=>budgetGuidance({...view,remainingMillis:0}),/exhausted/);
  assert.throws(()=>budgetGuidance({...view,pendingOperations:[{operationId:'unresolved'}]}),/RECONCILIATION/);
});

test('installed official SDK continues beyond 32 iterations and repeated Python errors in the SAME run',async()=>{
  let calls=0,executions=0;const runs=new Set();
  const agent=new Agent({maxIterations:sdkIterationLimit('unlimited'),
    model:{async *stream(){
      calls++;
      if(calls<=40)yield {type:'tool-call-delta',toolCallId:'python-'+calls,toolName:'python_execute',input:{}};
      else yield {type:'text-delta',text:'completed'};
      yield {type:'finish',reason:calls<=40?'tool-calls':'stop'};
    }},tools:[{name:'python_execute',description:'regression fixture, not provider acceptance',inputSchema:{type:'object'},
      execute(){executions++;if(executions<=8)throw Error('ordinary Python fixture error');return {verified:true};}}],
    hooks:{onEvent(e){if(e.snapshot.runId)runs.add(e.snapshot.runId);}}});
  assert.equal((await agent.run('fixture')).status,'completed');
  assert.equal(calls,41);assert.equal(executions,40);assert.equal(runs.size,1);
});
