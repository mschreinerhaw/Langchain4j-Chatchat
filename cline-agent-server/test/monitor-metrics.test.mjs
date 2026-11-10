import {test} from 'node:test';
import assert from 'node:assert/strict';
import {executionMetrics} from '../monitor/metrics.mjs';
test('unpriced usage remains unknown while observed counts survive',()=>{
 const x=executionMetrics({outcome:{startedAt:'2026-11-10T00:00:00Z',endedAt:'2026-11-10T00:01:00Z',usage:{meteringComplete:false,consumed:{modelCalls:41,toolCalls:40,modelTokens:900,costAmount:'0'}}},modelReceipts:[{usage:{inputTokens:600,outputTokens:300}}],executions:[{repair:{attemptsUsed:8}}],assets:[{},{}]});
 assert.equal(x.modelRequests,41);assert.equal(x.toolCalls,40);assert.equal(x.tokens,900);
 assert.equal(x.cost,null);assert.equal(x.automaticRepairs,8);assert.equal(x.elapsedMillis,60000);assert.equal(x.archivedFiles,2);
});
test('missing evidence is not fabricated',()=>{
 const x=executionMetrics({modelReceipts:[{usage:{inputTokens:100}}],events:[{type:'tool.started'},{type:'tool.completed'}]});
 assert.equal(x.tokens,null);assert.equal(x.cost,null);assert.equal(x.automaticRepairs,null);assert.equal(x.archivedFiles,null);assert.equal(x.toolCalls,1);
});
test('native elapsed time comes from observed turn events and running unknown is not interruption',()=>{
 const events=[{type:'model.iteration.started',timestamp:'2026-11-10T00:00:00Z'},{type:'turn.completed',timestamp:'2026-11-10T00:00:30Z'}];
 assert.equal(executionMetrics({events}).elapsedMillis,30000);
 const x=executionMetrics({outcome:{status:'RUNNING',reasonCode:'EXECUTION_OUTCOME_UNKNOWN'},modelReceipts:[{state:'STARTED'}],assets:[{kind:'ARTIFACT'},{kind:'LOG'}]});
 assert.equal(x.interruptionReason,null);assert.equal(x.phase,'MODEL_RUNNING');assert.equal(x.artifacts,1);
 const tools=executionMetrics({outcome:{usage:{consumed:{toolCalls:1}}},events:[{type:'tool.started',payload:{toolCallId:'read-1'}},{type:'tool.started',payload:{toolCallId:'python-1'}},{type:'tool.started',payload:{toolCallId:'python-1'}}]});
 assert.equal(tools.toolCalls,2);assert.equal(tools.managedToolCalls,1);
});
