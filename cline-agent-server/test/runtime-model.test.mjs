import test from 'node:test';
import assert from 'node:assert/strict';
import {resolveRuntimeModel} from '../src/runtime-model.mjs';
import {signContract,verifyContract,runtimeModelProfile} from '../src/task-contract.mjs';
import {taskGrant} from '../src/worker-executor-policy.mjs';
const model={logicalName:'selected-new',providerModel:'actual-provider-id'};
const contract=()=>({version:2,tenantId:'tenant',userId:'user',taskId:'task',sessionId:'session',workspaceRef:'project',modelProfile:runtimeModelProfile(model),runtimeModel:model,budgetPolicy:{status:'DEFERRED_STAGE_2_REVIEW'},toolPermissions:[],dataPermissions:[],networkPolicy:'broker-only',expiresAt:Date.now()+60000,runtimeProfile:'native-python-silo-v1',deliveryBinding:{tenantId:'tenant',taskId:'task',runId:'run',inputSnapshotVersion:'v1'}});
const key='k'.repeat(64);
test('dynamic models require exact signed descriptor and explicit Executor tenant opt-in',()=>{
 const c=contract(),cfg={signingKey:key,workerRuntime:'cline-python311',enableNativeSilo:true,dynamicModels:true,tenants:{tenant:{users:['user'],workspaces:{project:'/template'},models:[],tools:[],datasets:[],dynamicModels:true}}};
 assert.equal(taskGrant(signContract(c,key),cfg).runtimeModel.providerModel,'actual-provider-id');
 assert.throws(()=>taskGrant(signContract(c,key),{...cfg,dynamicModels:false}));
 assert.throws(()=>verifyContract(signContract({...c,runtimeModel:{...model,providerModel:'other'}},key),key));
 assert.throws(()=>verifyContract(signContract({...c,runtimeModel:{...model,apiKey:'forbidden'}},key),key));
 assert.throws(()=>taskGrant(signContract({...c,userId:'foreign'},key),cfg));
});
test('model credentials use only fixed authority; response is task-bound and never follows redirects',async()=>{
 const c=contract();let options;
 const resolve=(value,status=200)=>resolveRuntimeModel(c,'http://java/internal/model','signed-grant',{fetchImpl:async(url,o)=>{assert.equal(url.hostname,'java');options=o;return new Response(JSON.stringify(value),{status});}});
 const value=await resolve({model:model.providerModel,baseUrl:'https://provider/v1',apiKey:'private'});
 assert.equal(value.apiKey,'private');assert.equal(options.redirect,'manual');assert.equal(options.headers.Authorization,'Bearer signed-grant');
 await assert.rejects(resolve({model:'other',baseUrl:'https://provider/v1',apiKey:'private'}),/binding differs/);
 await assert.rejects(resolve({},403),/authority rejected/);
 await assert.rejects(resolve({model:model.providerModel,baseUrl:'https://provider/v1',apiKey:'x'.repeat(40000)}),/byte budget/);
});
