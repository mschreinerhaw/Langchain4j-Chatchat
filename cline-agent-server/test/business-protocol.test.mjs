import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {projectRequest,projectResponse,permittedTool} from '../src/business/model-protocol.mjs';
import {runtimeTools,operationId,verifiedReceipt} from '../src/business/runtime-tools.mjs';
import {nativeContext,inputHash,observeNativeSession} from '../src/business/native-context.mjs';
import {toolFailed} from '../src/tool-outcome.mjs';
import {nativeProposal} from '../src/business/code-lineage.mjs';
import {deliver} from '../src/business/delivery.mjs';
const contract={taskId:'t',scope:{tenantId:'tenant',userId:'user',runId:'run'},authorization:{allowedTools:['python_execute','artifact_read'],allowedSideEffects:['CODE_EXECUTION']},budget:{deadlineAt:new Date(Date.now()+60000).toISOString()},runtimeContext:{codingLifecycle:{schemaVersion:'runtime_os.code_lifecycle.v1',patchOnlyAfterFirst:true}}};
test('native tool names, arguments and actual results survive Runtime wire projection',()=>{
 const request=projectRequest({messages:[{role:'system',content:'rules'},{role:'user',content:'task'},{role:'assistant',content:null,tool_calls:[{id:'native-42',function:{name:'runtime__python_execute',arguments:'{"sourceFile":"analysis.py"}'}}]},{role:'tool',tool_call_id:'native-42',content:'{"status":"FAILED"}'}],tools:[{function:{name:'run_commands',parameters:{}}},{function:{name:'runtime__python_execute',parameters:{type:'object'}}}]},contract,3);
 assert.equal(request.operationId,'model-3');assert.equal(request.managedAttempt,true);assert.deepEqual(request.tools.map(t=>t.name),['runtime__python_execute']);
 assert.equal(request.messages[2].content[0].toolCallId,'native-42');assert.equal(request.messages[2].content[0].output,'{"status":"FAILED"}');
});
test('native local shell and unbound tool receipts are rejected',()=>{
 assert.equal(permittedTool('run_commands',contract),false);assert.equal(permittedTool('runtime__web_search',contract),false);
 assert.equal(permittedTool('editor',{authorization:{allowedTools:[],allowedSideEffects:[]}}),false);
 assert.throws(()=>projectRequest({messages:[{role:'tool',tool_call_id:'invented',content:'success'}]},contract,1),/Unbound/);
});
test('provider failure cannot become successful completion',()=>{
 assert.throws(()=>projectResponse({error:true,modelResponseFeedback:{diagnosticCode:'MODEL_OUTPUT_TRUNCATED'}},'m'),/rejected/);
 assert.equal(projectResponse({toolCalls:[{id:'id',name:'editor',input:{path:'a'}}],usage:{inputTokens:11,outputTokens:2},finishReason:'TOOL_EXECUTION'},'m').choices[0].finish_reason,'tool_calls');
 assert.equal(verifiedReceipt({status:'SUCCEEDED',executorStopped:true}),undefined);
});
test('authorized provider truncation keeps raw fragments for official Core recovery',()=>{
 const raw='{"patch":"unfinished';
 const projected=projectResponse({error:true,text:'Actual provider text',nativeToolCalls:[{id:'real-call',name:'apply_patch',arguments:raw}],modelResponseFeedback:{schemaVersion:'runtime_os.coding.model_feedback.v1',repairAllowed:true,maxRepairAttempts:2,diagnosticCode:'MODEL_OUTPUT_TRUNCATED'}},'model');
 assert.equal(projected.choices[0].finish_reason,'length');assert.equal(projected.choices[0].message.tool_calls[0].function.arguments,raw);assert.equal(projected.choices[0].message.content,'Actual provider text');
});
test('persistent operation identity uses Runtime scope and native invocation, never RPC id',()=>{
 const actual=operationId(contract,'native-session:native-tool');
 assert.equal(actual,'cline-'+createHash('sha256').update('tenant\0user\0run\0t\0native-session:native-tool').digest('hex'));
 assert.notEqual(actual,operationId({...contract,scope:{...contract.scope,userId:'other'}},'native-session:native-tool'));
});
test('native context binding rejects stale Epoch before dispatch',async()=>{
 const run={contract,nativeAgentSessionId:'native-session',epoch:'new',abort:new AbortController(),activeOperations:new Set()};
 const tools=runtimeTools(run,{},'.',async()=>{});
 await assert.rejects(tools.call('python_execute',{sourceFile:'a.py'},{sessionId:'native-session',toolCallId:'id',epoch:'old'}),/context mismatch/);
});
test('official MCP metadata binds the SDK snapshot and exact approved invocation',()=>{
 const run={epoch:'e',approvedCalls:new Map([['native-call',{toolName:'runtime__python_execute',inputHash:inputHash({sourceFile:'a.py'})}]])};
 observeNativeSession(run,{eventType:'native.event',payload:{type:'session_snapshot',payload:{sessionId:'sdk-session'}}});
 const actual={nativeSessionId:'sdk-session',nativeToolCallId:'native-call',epoch:'e'};
 assert.deepEqual(nativeContext(actual,run,'python_execute',{sourceFile:'a.py'}),{sessionId:'sdk-session',toolCallId:'native-call',epoch:'e'});
 assert.throws(()=>nativeContext({...actual,epoch:'old'},run,'python_execute',{sourceFile:'a.py'}),/mismatch/);
 assert.throws(()=>nativeContext(actual,run,'python_execute',{sourceFile:'changed.py'}),/permission/);
 assert.throws(()=>nativeContext({...actual,nativeToolCallId:'json-rpc-1'},run,'python_execute',{sourceFile:'a.py'}),/permission/);
 assert.equal(toolFailed({output:{isError:true,content:[{type:'text',text:'actual MCP failure'}]}}),true);
});
test('native byte delta expands repeated anchors and permits an unchanged re-execution identity',()=>{
 const h={currentSource:'x=0\ny=0\n',versions:[{version:1,codeHash:'hash'}]};
 const patch=nativeProposal(h,'x=1\ny=0\n');assert.deepEqual(patch.proposal.repair.edits,[{oldText:'x=0',newText:'x=1'}]);
 assert.equal(nativeProposal(h,h.currentSource).unchanged,true);
});
test('legacy named Skill keeps its authorized bytes and version under a safe native name',async()=>{
 const workspace=mkdtempSync(join(tmpdir(),'native-skill-'));const instructions='原有授权方法。';
 const card={id:'agent:method',name:'自定义方法',version:'7',description:'Authorized methodology'};
 const run={contract:{...contract,runtimeContext:{skills:{skills:[card]}}},abort:new AbortController()};
 try{
  const delivery=await deliver(run,{readInputContext:async()=>({files:[]}),readSkill:async(c,id,path)=>{assert.equal(path,'');return {skillId:id,version:'7',instructions,contentSha256:createHash('sha256').update(instructions).digest('hex')};}},workspace);
  const skill=delivery.skills.find(s=>s.skillId===card.id);assert.match(skill.name,/^method-[a-f0-9]{16}$/);assert.equal(skill.version,'7');assert.equal(skill.originalHash,createHash('sha256').update(instructions).digest('hex'));assert.equal(skill.loaded,false);
 }finally{rmSync(workspace,{recursive:true,force:true});}
});
test('real callback-shaped failed receipt remains failure, latest source lineage is verified',async()=>{
 // Unit boundary test only; these callback fixtures are never business acceptance evidence.
 const workspace=mkdtempSync(join(tmpdir(),'native-business-'));
 const failed={status:'FAILED',executorStopped:true,executionFeedback:{repair:{action:'REPAIR',allowed:true}}};
 const run={contract,nativeAgentSessionId:'native-session',epoch:'e',abort:new AbortController(),activeOperations:new Set()};let index=0;const dispatched=[];
 const gateway={preparePython:async()=>({fileName:`analysis_v${++index}.py`,scriptVersion:index}),executePython:async(c,v)=>{dispatched.push(v);return failed;},getPython:async()=>failed};
 const tools=runtimeTools(run,gateway,workspace,async()=>{});const context={sessionId:'native-session',epoch:'e',toolCallId:'one'};
 try{
  writeFileSync(join(workspace,'a.py'),'print(1/0)\n');assert.deepEqual(await tools.call('python_execute',{sourceFile:'a.py'},context),failed);
  assert.equal(run.lastOperation,null);writeFileSync(join(workspace,'a.py'),'print(1/1)\n');
  await tools.call('python_execute',{sourceFile:'a.py'},{...context,toolCallId:'two'});
  assert.equal(run.codeLifecycle.versions[1].parentHash,run.codeLifecycle.versions[0].codeHash);
  assert.equal(dispatched[1].source,'print(1/1)\n');assert.equal(run.codeLifecycle.versions[1].kind,'PATCH');
  await tools.call('python_execute',{sourceFile:'a.py'},{...context,toolCallId:'two'});assert.equal(dispatched.length,2,'Same native invocation reuses the actual receipt');
 }finally{rmSync(workspace,{recursive:true,force:true});}
});
