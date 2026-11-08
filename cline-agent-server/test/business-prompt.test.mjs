import test from 'node:test';
import assert from 'node:assert/strict';
import {promptContext} from '../src/business/prompt.mjs';
test('Native transport does not duplicate the frozen objective or lose the output contract and input identities',()=>{
 const contract={objective:{instruction:'user objective with exact decimal rules',description:'scope'},outputContract:{rowSchema:{type:'object'}},runtimeContext:{skills:{privateStaged:'delivered separately'},inputFiles:[],model:{selectedModel:'configured'},other:'preserve'},inputSnapshotRef:'snapshot:v1'};
 const delivery={files:[{nativePath:'inputs/id/original.csv',virtualPath:'/data/input/id/original.csv',sha256:'a'.repeat(64)}],context:{datasetReferences:['source:v1']}};
 const result=promptContext(contract,delivery);assert(!JSON.stringify(result).includes(contract.objective.instruction));assert.deepEqual(result.outputContract,contract.outputContract);assert.deepEqual(result.files,delivery.files);assert.deepEqual(result.inputs,delivery.context);assert.equal(result.runtimeContext.other,'preserve');
});
