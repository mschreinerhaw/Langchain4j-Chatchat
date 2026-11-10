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
