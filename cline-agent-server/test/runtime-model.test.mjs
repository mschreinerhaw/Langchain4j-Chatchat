import test from 'node:test';
import assert from 'node:assert/strict';
import {resolveRuntimeModel,applyPublishedModelLimit,applyPublishedReasoning} from '../src/runtime-model.mjs';
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
test('Executor only grants Studio metadata tools listed in the signed Native Runtime contract',()=>{
 const tenant={users:['user'],workspaces:{project:'/template'},models:[],tools:[],datasets:[],dynamicModels:true,mcp:{authMode:'native-runtime'}};
 const cfg={signingKey:key,workerRuntime:'cline-python311',enableNativeSilo:true,dynamicModels:true,tenants:{tenant}};
 const c={...contract(),studioBudget:{modelCalls:8,modelTokens:100000},runtimeTools:['workspace_metadata'],toolPermissions:['workspace_metadata','runtime__workspace_metadata','tenant_data__workspace_metadata']};
 assert.deepEqual(taskGrant(signContract(c,key),cfg).toolPermissions,c.toolPermissions);
 assert.throws(()=>taskGrant(signContract({...c,runtimeTools:[]},key),cfg),/policy denied/);
 assert.throws(()=>taskGrant(signContract({...c,toolPermissions:['runtime__workspace_validate']},key),cfg),/policy denied/);
 assert.throws(()=>taskGrant(signContract(c,key),{...cfg,tenants:{tenant:{...tenant,mcp:undefined}}}),/policy denied/);
});
test('model credentials use only fixed authority; response is task-bound and never follows redirects',async()=>{
 const c=contract();let options;
 const resolve=(value,status=200)=>resolveRuntimeModel(c,'http://java/internal/model','signed-grant',{fetchImpl:async(url,o)=>{assert.equal(url.hostname,'java');options=o;return new Response(JSON.stringify(value),{status});}});
 const value=await resolve({model:model.providerModel,baseUrl:'https://provider/v1',apiKey:'private'});
 assert.equal(value.apiKey,'private');assert.equal(options.redirect,'manual');assert.equal(options.headers.Authorization,'Bearer signed-grant');
 const connection={model:model.providerModel,baseUrl:'https://provider/v1',apiKey:'private'};
 const frozen=await resolve({...connection,requestedReasoningEffort:'auto',reasoningEffort:'high'});
 assert.equal(frozen.reasoningEffort,'high');
 await assert.rejects(resolve({...connection,requestedReasoningEffort:'low',reasoningEffort:'high'}),/Invalid frozen model reasoning/);
 await assert.rejects(resolve({model:'other',baseUrl:'https://provider/v1',apiKey:'private'}),/binding differs/);
 await assert.rejects(resolve({},403),/authority rejected/);
 await assert.rejects(resolve({model:model.providerModel,baseUrl:'https://provider/v1',apiKey:'x'.repeat(40000)}),/byte budget/);
});

test('publication overrides Core defaults and unlimited removes all token caps',()=>{
 assert.deepEqual(applyPublishedModelLimit({max_tokens:8192,max_completion_tokens:12},{maxTokens:65536}),{max_tokens:65536});
 assert.deepEqual(applyPublishedModelLimit({max_output_tokens:8192},{maxTokens:-1},true),{});
 assert.deepEqual(applyPublishedModelLimit({},{maxTokens:123},true),{max_output_tokens:123});
 assert.throws(()=>applyPublishedModelLimit({},{maxTokens:0}));
});

test('frozen efforts override worker hints in both supported wire formats',()=>{
 const low={requestedReasoningEffort:'low',reasoningEffort:'low'};
 const high={requestedReasoningEffort:'auto',reasoningEffort:'high'};
 assert.deepEqual(applyPublishedReasoning({model:'selected',reasoning_effort:'xhigh'},low),
  {model:'selected',reasoning_effort:'low'});
 assert.deepEqual(applyPublishedReasoning({reasoning_effort:'xhigh',reasoning:{effort:'minimal'}},high,true),{reasoning:{effort:'high'}});
 assert.deepEqual(applyPublishedReasoning({reasoning_effort:'high'},{requestedReasoningEffort:'auto'}),{});
});

test('mapped provider efforts use only their published request fields',()=>{
 const configured=(reasoningParameter,reasoningEffort)=>({requestedReasoningEffort:'medium',reasoningParameter,reasoningEffort});
 assert.deepEqual(applyPublishedReasoning({reasoning_effort:'xhigh',chat_template_kwargs:{other:true}},
   configured('hunyuan-template','high')),{chat_template_kwargs:{other:true,reasoning_effort:'high'}});
 assert.deepEqual(applyPublishedReasoning({},configured('deepseek-thinking','high')),
   {reasoning_effort:'high',thinking:{type:'enabled'}});
 assert.deepEqual(applyPublishedReasoning({thinking_budget:32},configured('qwen-thinking','medium')),
   {reasoning_effort:'medium',enable_thinking:true});
 assert.deepEqual(applyPublishedReasoning({},configured('standard','xhigh'),true),{reasoning:{effort:'xhigh'}});
 assert.throws(()=>applyPublishedReasoning({},configured('hunyuan-template','xhigh')),/Invalid provider reasoning/);
});
