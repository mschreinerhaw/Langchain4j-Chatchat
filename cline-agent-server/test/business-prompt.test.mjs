import test from 'node:test';
import assert from 'node:assert/strict';
import {promptContext} from '../src/business/prompt.mjs';
import {runtimeTools} from '../src/business/runtime-tools.mjs';
import {runtimeRepairStopped} from '../src/business/native-policy.mjs';
test('Native transport does not duplicate the frozen objective or lose the output contract and input identities',()=>{
 const contract={objective:{instruction:'user objective with exact decimal rules',description:'scope'},outputContract:{rowSchema:{type:'object'}},runtimeContext:{skills:{privateStaged:'delivered separately'},inputFiles:[],model:{selectedModel:'configured'},other:'preserve'},inputSnapshotRef:'snapshot:v1'};
 const delivery={files:[{nativePath:'inputs/id/original.csv',virtualPath:'/data/input/id/original.csv',sha256:'a'.repeat(64)}],context:{datasetReferences:['source:v1']}};
 const result=promptContext(contract,delivery);assert(!JSON.stringify(result).includes(contract.objective.instruction));assert.deepEqual(result.outputContract,contract.outputContract);assert.deepEqual(result.files,delivery.files);assert.deepEqual(result.inputs,delivery.context);assert.equal(result.runtimeContext.other,'preserve');
});
test('Sandbox paths receive a precise error without calling the governed logical-data reader',async()=>{
 let calls=0;const run={contract:{authorization:{allowedTools:['input_read']},runtimeContext:{},budget:{deadlineAt:new Date(Date.now()+60000).toISOString()}},nativeAgentSessionId:'agent',epoch:'epoch',abort:new AbortController()};
 const tools=runtimeTools(run,{readInput:async()=>{calls++;return {actual:true};}},'.',()=>{});
 const context={sessionId:'agent',epoch:'epoch',toolCallId:'call'};
 await assert.rejects(tools.call('input_read',{datasetReference:'/data/input/id/file.csv',offset:0,limit:100},context),/datasetReference, not a Sandbox/);
 assert.equal(calls,0);assert.deepEqual(await tools.call('input_read',{datasetReference:'authorized:dataset:v1',offset:0,limit:100},context),{actual:true});assert.equal(calls,1);
});
test('Only a real stopped Runtime STOP receipt terminates further Native model admission',()=>{
 const receipt={executorStopped:true,resultAvailable:false,executionFeedback:{repair:{action:'STOP'}}};
 assert.equal(runtimeRepairStopped(receipt),true);
 assert.equal(runtimeRepairStopped({...receipt,executorStopped:false}),false);
 assert.equal(runtimeRepairStopped({...receipt,resultAvailable:true}),false);
 assert.equal(runtimeRepairStopped({...receipt,executionFeedback:{repair:{action:'REPAIR_IN_CURRENT_CONTEXT'}}}),false);
 assert.equal(runtimeRepairStopped(undefined),false);
});
