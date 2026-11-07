import test from 'node:test';import assert from 'node:assert/strict';
import {createNativeSession} from '../src/native-session.js';
const contract={taskId:'native',inputSnapshotRef:'input:1',workspaceRef:'code:1',
  authorization:{allowedTools:['read_files']},budget:{deadlineAt:new Date(Date.now()+60000).toISOString(),maxModelCalls:8}};
const hash='a'.repeat(64);
const request=()=>({systemPrompt:'PRIVATE SYSTEM PROMPT',messages:[{role:'user',content:[{type:'text',text:'private task'}]}],
  tools:[{name:'read_files',description:'Read own source',inputSchema:{type:'object'}}]});
async function collect(stream){const result=[];for await(const value of stream)result.push(value);return result;}
test('an unknown provider outcome keeps its existing reservation unsettled',async()=>{
  const reserved=[],settled=[];const session=createNativeSession(contract,{checkpoint:async()=>{},reserveModel:async(_c,v)=>reserved.push(v),settleModel:async(_c,v)=>settled.push(v)},
    {contractHash:hash,model:{async *stream(){throw Error('CONNECTION_LOST');}}});
  await assert.rejects(collect(session.model.stream(request())),/CONNECTION_LOST/);
  assert.equal(reserved.length,1);assert.equal(settled.length,0);
});
test('a stopped archived model receipt replays under the same ordinal without spending a new admission',async()=>{
  let reserved=0,settled=0;
  const checkpoint={sdkSessionId:'original-sdk',contractHash:hash,inputSnapshotRef:'input:1',workspaceRef:'code:1',modelOrdinal:0,
    checkpointOrdinal:1,eventOrdinal:0,receiptReplayOperationId:'model-1'};
  const session=createNativeSession(contract,{checkpoint:async()=>{throw Error('Must not overwrite original bound checkpoint');},
    reserveModel:async()=>reserved++,settleModel:async()=>settled++},{checkpoint,contractHash:hash,
      model:{async *stream(req){assert.equal(req.runtimeReceiptReplay,true);yield{type:'runtime-model-metadata',metadata:{receiptReplayed:true}};yield{type:'text-delta',text:'original response'};yield{type:'finish',reason:'stop'};}}});
  await session.checkpoint([]);const result=await collect(session.model.stream(request()));
  assert.equal(result[0].text,'original response');assert.equal(reserved,0);assert.equal(settled,0);
  assert.equal(session.state().modelOrdinal,1);assert.equal(session.state().receiptReplayOperationId,null);
});

test('the final admitted model receipt can replay at the call cap and cannot grant another inference',async()=>{
  const events=[];let inference=0,reservations=0;
  const c={...contract,budget:{...contract.budget,maxModelCalls:1}};
  const checkpoint={sdkSessionId:'last-receipt',contractHash:hash,inputSnapshotRef:'input:1',workspaceRef:'code:1',modelOrdinal:0,
    checkpointOrdinal:1,eventOrdinal:0,receiptReplayOperationId:'model-1'};
  const session=createNativeSession(c,{event:e=>events.push(e),checkpoint:async()=>{},reserveModel:async()=>reservations++,settleModel:async()=>{}},
    {checkpoint,contractHash:hash,model:{async *stream(){inference++;yield{type:'runtime-model-metadata',metadata:{receiptReplayed:true}};yield{type:'finish',reason:'stop'};}}});
  await collect(session.model.stream(request()));
  assert.equal(reservations,0);assert.equal(inference,1);assert.equal(events.find(e=>e.type==='MODEL_USAGE').receiptReplayed,true);
  await assert.rejects(collect(session.model.stream(request())),/budget exhausted/);assert.equal(inference,1);
});
test('a new inference cannot masquerade as a replay',async()=>{
  const checkpoint={sdkSessionId:'original-sdk',contractHash:hash,inputSnapshotRef:'input:1',workspaceRef:'code:1',modelOrdinal:0,checkpointOrdinal:1,receiptReplayOperationId:'model-1'};
  const session=createNativeSession(contract,{checkpoint:async()=>{}},{checkpoint,contractHash:hash,
    model:{managesAdmissions:true,async *stream(){yield{type:'finish',reason:'stop'};}}});
  await assert.rejects(collect(session.model.stream(request())),/original model receipt/);
});
test('model request audit contains schema identity and names without private prompts',async()=>{
  const events=[];const session=createNativeSession(contract,{checkpoint:async()=>{},event:async v=>events.push(v)},
    {contractHash:hash,model:{managesAdmissions:true,async *stream(){yield{type:'usage',usage:{inputTokens:10,outputTokens:2}};yield{type:'finish',reason:'stop'};}}});
  await collect(session.model.stream(request()));const started=events.find(e=>e.type==='MODEL_STARTED');
  assert.deepEqual(started.disclosedTools,['read_files']);assert.match(started.toolSchemaSha256,/^[a-f0-9]{64}$/);
  assert.doesNotMatch(JSON.stringify(events),/PRIVATE SYSTEM PROMPT|private task/);
});
test('invalid response repair is bounded and a valid response clears the pending failure',async()=>{
  let calls=0;const session=createNativeSession(contract,{checkpoint:async()=>{}},{contractHash:hash,
    model:{managesAdmissions:true,async *stream(){if(++calls===1)yield{type:'runtime-model-feedback',feedback:{repairAllowed:true,maxRepairAttempts:1,diagnosticCode:'MODEL_TOOL_ARGUMENTS_INVALID'}};yield{type:'finish',reason:'stop'};}}});
  await collect(session.model.stream(request()));await collect(session.model.stream(request()));await collect(session.model.stream(request()));
  assert.equal(calls,3);
});
