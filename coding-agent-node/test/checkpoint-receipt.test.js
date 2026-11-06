import test from 'node:test';
import assert from 'node:assert/strict';
import {createCodingRun} from '../src/runner.js';
import {createRuntimeModel} from '../src/runtime-model.js';

// Real pinned SDK; the model bridge is a controlled double, not the live acceptance gate.
const contract={schemaVersion:'runtime_os.coding.v1',taskId:'receipt-restore',
 scope:{tenantId:'tenant',userId:'owner',runId:'run'},objective:{instruction:'Continue original analysis'},
 inputSnapshotRef:'input:immutable',workspaceRef:'workspace:immutable',
 authorization:{allowedTools:['python_execute'],allowedSideEffects:['CODE_EXECUTION'],allowedDataRefs:['c'+'a'.repeat(63)],networkPolicy:'NONE'},
 outputContract:{requiredFiles:['rows.jsonl']},budget:{maxModelCalls:4}};
function checkpoint(){return {sdkSessionId:'same-sdk-session',checkpointOrdinal:4,modelOrdinal:1,eventOrdinal:2,
 receiptReplayOperationId:'model-2',messages:[{role:'user',content:[{type:'text',text:'Original request with completed result: original-result'}]}]};}

test('missing or corrupt checkpoint identity cannot silently create another SDK session',()=>{
 for(const changed of [{sdkSessionId:undefined},{sdkSessionId:'../other'},{checkpointOrdinal:0},{checkpointOrdinal:1.5}])
  assert.throws(()=>createCodingRun(contract,{reserveModel(){},executePython(){}}, {checkpoint:{...checkpoint(),...changed}}),/replacement session is forbidden/);
});

for(const receiptAvailable of [true,false])test(`SDK restore consumes bound receipt only (available=${receiptAvailable})`,async()=>{
 const saved=[],requests=[];let executions=0,reservations=0,settlements=0;
 const gateway={atomicModelAttempts:true,
  async reserveModel(){reservations++;},async settleModel(){settlements++;},async finish(){},
  async executePython(){executions++;throw Error('Confirmed Python must not be repeated');},
  async checkpoint(_c,value){saved.push(structuredClone(value));},
  async model(_c,request){requests.push(request);assert.equal(saved.length,0,'Cannot overwrite the original replay boundary');
   assert.equal(request.operationId,'model-2');assert.equal(request.sdkCheckpointOrdinal,4);assert.equal(request.receiptReplayExpected,true);
   assert(JSON.stringify(request.messages).includes('original-result'));
   return {text:'Continued in the original session',finishReason:'STOP',runtimeReceiptReplayed:receiptAvailable,
    usage:{inputTokens:99,outputTokens:20}};
  }};
 const result=await createCodingRun(contract,gateway,{nativeSdk:true,model:createRuntimeModel(gateway,contract,1,{nativeSdk:true}),checkpoint:checkpoint()}).run();
 assert.equal(executions,0);assert.equal(reservations,0);assert.equal(settlements,0);assert.equal(requests.length,1);
 assert.equal(result.status,receiptAvailable?'completed':'failed');
 if(receiptAvailable){assert(saved.length>0);assert(saved.every(c=>c.sdkSessionId==='same-sdk-session'&&c.modelOrdinal===2&&c.checkpointOrdinal>4));
  assert(saved.at(-1).messages.some(m=>m.role==='assistant'&&m.content.some(p=>p.text?.includes('Continued'))));
 }else{assert.equal(saved.length,0);assert.match(result.error,/expected an archived model response/);}
});
