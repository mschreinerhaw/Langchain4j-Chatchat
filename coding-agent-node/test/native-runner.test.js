import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {createNativeCodingRun,NATIVE_CODING_PROFILE,NATIVE_PIPEFAIL_PROFILE} from '../src/native-runner.js';

const hash='a'.repeat(64);
test('an unavailable MCP service is isolated while an authorization refusal remains fatal',async t=>{
  for(const statusCode of [503,403]){
    const h=await host(t,{async openCodeWorkspace(){return{files:{'main.py':'x=1'},requireExecution:false,mcpServers:[{serverName:'good'},{serverName:'bad'}]};},
      async listCodeMcpTools(_c,server){if(server==='bad')throw Object.assign(Error('Unavailable'),{statusCode});return[{name:'lookup',agentToolName:'published_lookup',inputSchema:{type:'object'}}];},
      async callCodeMcpTool(){return{value:7};}});
    const c=contract();c.authorization.allowedTools.push('published_lookup');let disclosed=false;
    const run=createNativeCodingRun(c,h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
      {assert(request){assert.ok(request.tools.some(t=>t.name==='published_lookup'));disclosed=true;},text:'No external lookup is needed for this task.'}
    ])});
    if(statusCode===503){assert.equal((await run.run()).status,'completed');assert.equal(disclosed,true);assert.ok(h.events.some(e=>e.type==='MCP_UNAVAILABLE'));}
    else{await assert.rejects(run.run(),/Unavailable/);assert.equal(disclosed,false);}
  }
});
test('governed MCP uses SDK schemas and preserves autonomous tool input and original results',async t=>{
  const c=contract();c.authorization.allowedTools.push('published_lookup');
  const schema={type:'object',properties:{key:{type:'string'}},required:['key']};
  const calls=[];let observed=false,revoked=false;
  const h=await host(t,{async openCodeWorkspace(){return{files:{'main.py':'value = 1\n'},requireExecution:false,mcpServers:[{serverName:'published-server'}]};},
    async listCodeMcpTools(_c,server){assert.equal(server,'published-server');return revoked?[]:[{name:'lookup',agentToolName:'published_lookup',description:'Look up a key',inputSchema:schema}];},
    async callCodeMcpTool(_c,request){calls.push(request);revoked=true;return{content:[{type:'text',text:'discovered-value-42'}]};}});
  const result=await createNativeCodingRun(c,h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {assert(request){const tool=request.tools.find(t=>t.name==='published_lookup');assert.deepEqual(tool.inputSchema,schema);},
      calls:[{id:'mcp-original-call',name:'published_lookup',input:{key:'chosen-by-agent'}}]},
    {assert(request){assert.match(JSON.stringify(request.messages),/discovered-value-42/);assert.ok(!request.tools.some(t=>t.name==='published_lookup'));observed=true;},text:'The returned value is 42.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(observed,true);
  assert.equal(calls.length,1);assert.equal(calls[0].serverName,'published-server');assert.equal(calls[0].toolName,'lookup');
  assert.deepEqual(calls[0].arguments,{key:'chosen-by-agent'});assert.equal(calls[0].context.toolCallId,'mcp-original-call');
});
test('MCP descriptors outside frozen tool grants fail before model disclosure',async t=>{
  const h=await host(t,{async openCodeWorkspace(){return{files:{'main.py':'x=1'},mcpServers:[{serverName:'server'}]};},
    async listCodeMcpTools(){return[{name:'secret',agentToolName:'not-granted',inputSchema:{type:'object'}}];},async callCodeMcpTool(){throw Error('Must not execute');}});
  const run=createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([])});
  await assert.rejects(run.run(),/MCP catalog differs/);
});
function contract(){return{schemaVersion:'runtime_os.coding.v2',taskId:'native-task',scope:{tenantId:'tenant',userId:'owner',runId:'native-run'},
  metadataRefs:[NATIVE_CODING_PROFILE],inputSnapshotRef:'input:native',workspaceRef:'workspace:native',
  objective:{instruction:'Fix the supplied project and run its tests.'},outputContract:{resultFormat:'WORKSPACE_FILES'},
  authorization:{networkPolicy:'NONE',allowedSideEffects:['WORKSPACE_WRITE','CODE_EXECUTION'],allowedTools:['read_files','search_codebase','editor','apply_patch','run_commands']},
  budget:{maxModelCalls:8,deadlineAt:new Date(Date.now()+60000).toISOString()}};}
async function host(t,options={}){
  const base=await mkdtemp(join(tmpdir(),'native-runner-test-'));t.after(()=>rm(base,{recursive:true,force:true}));
  let savedWorkspace=null;const checkpoints=[],commands=[],events=[],admissions=[];
  const toolResults=new Map();
  const gateway={
    async openCodeWorkspace(){return savedWorkspace??{files:{'calc.py':'def add(a, b):\n    return a - b\n','tests/test_calc.py':'# authorized fixed test fixture\n'},modelId:'qwen3.8-flash'};},
    async commitCodeWorkspace(_c,request){savedWorkspace={files:{...request.files},manifest:request.manifest};return{manifestHash:request.manifest.sha256};},
    async executeCodeCommand(_c,request){commands.push(request);return{operationId:'command-'+commands.length,manifestHash:request.manifest.sha256,
      executorStopped:true,stopProofRef:'test:stop:'+commands.length,exitCode:commands.length===1?1:0,
      stdout:commands.length===1?'':'Ran 1 test\nOK\n',stderr:commands.length===1?'AssertionError: -1 != 3\n':''};},
    async authorizeCodeTool(){},async checkpoint(_c,value){checkpoints.push(structuredClone(value));options.afterCheckpoint?.(value);},async finish(){return{};},
    async lookupCodeTool(_c,request){return toolResults.get(request.toolCallId)??{};},
    async finishCodeTool(_c,request){toolResults.set(request.toolCallId,structuredClone(request.result));return{saved:true};},
    async reserveModel(_c,value){admissions.push(value);},async settleModel(){},async event(value){events.push(value);},
    ...options};
  return{base,gateway,checkpoints,commands,events,admissions,get workspace(){return savedWorkspace;}};
}
function scripted(turns,startOrdinal=0){let index=0;return{async *stream(request){
  const turn=turns[index++];const ordinal=startOrdinal+index;assert.ok(turn,'Unexpected additional model call');await turn.assert?.(request);
  for(const [i,call]of(turn.calls??[]).entries())yield{type:'tool-call-delta',toolCallId:call.id??`turn-${ordinal}-tool-${i}`,toolName:call.name,input:call.input};
  if(turn.text)yield{type:'text-delta',text:turn.text};
  yield{type:'usage',usage:{inputTokens:10,outputTokens:5}};yield{type:'finish',reason:turn.calls?.length?'tool-calls':'stop'};
}};}
function pipefailContract(){const c=contract();c.budget.deadlineAt=new Date(Date.now()+5000).toISOString();c.metadataRefs=[NATIVE_PIPEFAIL_PROFILE];c.runtimeContext={nativeExecutionPolicy:'BASH_PIPEFAIL_V1',
  nativeExecutorCapability:'NATIVE_EXECUTOR:BASH_PIPEFAIL_V1:'+hash+':'+hash};return c;}

function latestFacts(request){
  const text=request.messages.flatMap(message=>message.content).filter(part=>part.type==='text'
    &&part.text.startsWith('Latest Runtime execution facts,')).at(-1)?.text;
  assert.ok(text,'Runtime facts must be visible in the actual SDK model request');
  return JSON.parse(text.slice(text.indexOf('{')));
}

test('Phase 4 preserves current declared verification and original resource facts after later inspection and native compaction',async t=>{
  const declared='python -m unittest discover -s tests -v';
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:declared,verificationPolicy:'BASH_PIPEFAIL_V1'};},
    async budget(){return{remainingTokens:262144,remainingModelCalls:8,remainingMillis:4000,
      chargedUsage:{codeExecutions:h.commands.length},completionReservation:{phase:'COMPLETION',ratio:0.35}};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{h.commands.push(value);return{operationId:'physical-'+h.commands.length,
    manifestHash:value.manifest.sha256,commandHash:createHash('sha256').update(value.command).digest('hex'),
    executorStopped:true,stopProofRef:'independent:stop:'+h.commands.length,resultAvailable:true,exitCode:0,stdout:'OK',stderr:'',
    verificationPolicy:'BASH_PIPEFAIL_V1',declaredCommandMatched:value.command===declared,
    declaredCommandSha256:createHash('sha256').update(declared).digest('hex')};};
  const c=pipefailContract();c.budget.maxCodeExecutions=4;
  const result=await createNativeCodingRun(c,h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[declared]}}]},
    {calls:[{name:'run_commands',input:{commands:['pwd']}}]},
    {assert(request){const facts=latestFacts(request);assert.equal(facts.verification.verificationStatus,'PASSED');
      assert.equal(facts.verification.executionOperationId,'physical-1');assert.equal(facts.lastExecution.operationId,'physical-2');
      assert.equal(facts.verification.taskId,c.taskId);assert.equal(facts.verification.codeManifestHash,facts.codeManifestHash);
      assert.equal(facts.verification.stopProofRef,'independent:stop:1');assert.equal(facts.budget.remainingCodeExecutions,2);
      assert.equal(facts.independentAcceptanceStatus,'NOT_EVALUATED');},text:'The actual declaration is verified. Deliver the unchanged project.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(h.commands.length,2);
  assert.ok(h.checkpoints.some(value=>value.compactionCount>0),'Use the installed SDK compactor');
});

test('Phase 4 exposes stale declaration after editing and never certifies a new source with an old pass',async t=>{
  let observedStale=false;
  const declared='python -m unittest discover -s tests -v';
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:declared,verificationPolicy:'BASH_PIPEFAIL_V1'};}});
  h.gateway.executeCodeCommand=async(_c,value)=>({operationId:'test-old-source',manifestHash:value.manifest.sha256,
    commandHash:createHash('sha256').update(value.command).digest('hex'),executorStopped:true,stopProofRef:'independent:old-stop',
    resultAvailable:true,exitCode:0,stdout:'OK',stderr:'',verificationPolicy:'BASH_PIPEFAIL_V1',declaredCommandMatched:true,
    declaredCommandSha256:createHash('sha256').update(declared).digest('hex')});
  const c=pipefailContract();
  const result=await createNativeCodingRun(c,h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[declared]}}]},
    {calls:[{name:'editor',input:{path:'calc.py',old_text:'value = 1',new_text:'value = 2'}}]},
    {assert(request){const facts=latestFacts(request);assert.equal(facts.verification.verificationStatus,'STALE');
      assert.notEqual(facts.verification.codeManifestHash,facts.codeManifestHash);observedStale=true;},text:'Done.'},
  ])}).run();
  assert.equal(observedStale,true,'The model must actually observe stale verification; an assertion hidden in SDK failed status is insufficient');
  assert.equal(result.status,'failed');
});

test('versioned native policy accepts host-bound filtered verification without rewriting SDK commands',async t=>{
  const declared='python -m unittest discover -s tests -v',command=declared+' 2>&1 | tail -20';
  const h=await host(t,{async openCodeWorkspace(){return{files:{'main.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:declared,verificationPolicy:'BASH_PIPEFAIL_V1'};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{h.commands.push(value);assert.equal(value.command,command);return{operationId:'filtered',manifestHash:value.manifest.sha256,
    commandHash:createHash('sha256').update(command).digest('hex'),executorStopped:true,stopProofRef:'test:stop',resultAvailable:true,exitCode:0,stdout:'OK',stderr:'',
    verificationPolicy:'BASH_PIPEFAIL_V1',declaredCommandMatched:true,declaredCommandSha256:createHash('sha256').update(declared).digest('hex')};};
  const result=await createNativeCodingRun(pipefailContract(),h.gateway,{model:scripted([
    {calls:[{name:'run_commands',input:{commands:[command]}}]}, {text:'Read the successful original test result.'},
  ]),contractHash:hash,workspaceBase:h.base}).run();
  assert.equal(result.status,'completed');assert.equal(h.commands.length,1);
});

test('Phase 4 restored SDK session reads persisted declaration facts without executing the old test again',async t=>{
  const declared='python -m unittest discover -s tests -v';let verification=null,execution=null;
  const h=await host(t,{async openCodeWorkspace(){return{...(h.workspace??{files:{'calc.py':'value = 1\n'}}),
    modelId:'qwen3.8-flash',acceptanceCommand:declared,verificationPolicy:'BASH_PIPEFAIL_V1',lastExecution:execution,lastVerification:verification};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{h.commands.push(value);execution={operationId:'stopped-'+h.commands.length,
    manifestHash:value.manifest.sha256,commandHash:createHash('sha256').update(value.command).digest('hex'),
    executorStopped:true,stopProofRef:'independent:stop:'+h.commands.length,resultAvailable:true,exitCode:0,stdout:'OK',stderr:'',
    verificationPolicy:'BASH_PIPEFAIL_V1',declaredCommandMatched:value.command===declared,
    declaredCommandSha256:createHash('sha256').update(declared).digest('hex')};
    if(execution.declaredCommandMatched)verification=execution;return execution;};
  const c=pipefailContract();
  const first=await createNativeCodingRun(c,h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[declared]}}]},
    {calls:[{name:'run_commands',input:{commands:['pwd']}}]}, {text:'Verified.'},
  ])}).run();
  assert.equal(first.status,'completed');const checkpoint=h.checkpoints.at(-1);let sawRecovered=false;
  const restored=await createNativeCodingRun(c,h.gateway,{contractHash:hash,workspaceBase:h.base,checkpoint,model:scripted([
    {assert(request){const fact=latestFacts(request).verification;assert.equal(fact.verificationStatus,'PASSED');
      assert.equal(fact.executionOperationId,verification.operationId);sawRecovered=true;},text:'Deliver the already verified unchanged project.'},
  ],checkpoint.modelOrdinal)}).run();
  assert.equal(restored.status,'completed',JSON.stringify(restored.error));assert.equal(sawRecovered,true);
  assert.equal(restored.sdkSessionId,first.sdkSessionId);assert.equal(h.commands.length,2);
});

test('Phase 4 lost edit ACK reloads the Host version before projecting verification into that model request',async t=>{
  const declared='python -m unittest discover -s tests -v';let verification=null,opens=0,observed=false;
  const h=await host(t,{async openCodeWorkspace(){opens++;return{...(h.workspace??{files:{'calc.py':'value = 1\n'}}),
    modelId:'qwen3.8-flash',acceptanceCommand:declared,verificationPolicy:'BASH_PIPEFAIL_V1',lastVerification:verification};}});
  h.gateway.executeCodeCommand=async(_c,value)=>verification={operationId:'verified-original',manifestHash:value.manifest.sha256,
    commandHash:createHash('sha256').update(value.command).digest('hex'),executorStopped:true,stopProofRef:'independent:stop',
    resultAvailable:true,exitCode:0,stdout:'OK',stderr:'',verificationPolicy:'BASH_PIPEFAIL_V1',declaredCommandMatched:true,
    declaredCommandSha256:createHash('sha256').update(declared).digest('hex')};
  const commit=h.gateway.commitCodeWorkspace;
  h.gateway.commitCodeWorkspace=async(...args)=>{const answer=await commit(...args);
    if(args[1].kind==='EDIT')throw Error('Actual durable edit succeeded; transport ACK lost');return answer;};
  const result=await createNativeCodingRun(pipefailContract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[declared]}}]},
    {calls:[{name:'editor',input:{path:'calc.py',old_text:'value = 1',new_text:'value = 2'}}]},
    {assert(request){const facts=latestFacts(request);assert.equal(opens,2);assert.equal(facts.codeManifestHash,h.workspace.manifest.sha256);
      assert.equal(facts.verification.verificationStatus,'STALE');observed=true;},text:'Done.'},
  ])}).run();
  assert.equal(observed,true,'Refresh after prepareTurn would send stale passing facts to this model request');
  assert.equal(result.status,'failed');
});

for(const failure of ['nonzero','missing-result'])test('later declared verification '+failure+' invalidates an earlier success on unchanged source',async t=>{
  const declared='python -m unittest discover -s tests -v';
  const h=await host(t,{async openCodeWorkspace(){return{files:{'main.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:declared,verificationPolicy:'BASH_PIPEFAIL_V1'};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{h.commands.push(value);const bad=h.commands.length===2;return{
    operationId:'verification-'+h.commands.length,manifestHash:value.manifest.sha256,executorStopped:true,stopProofRef:'test:stop',
    resultAvailable:!(bad&&failure==='missing-result'),exitCode:bad&&failure==='nonzero'?1:0,stdout:bad?'':'OK',stderr:bad?'Actual observed failure':'',
    verificationPolicy:'BASH_PIPEFAIL_V1',declaredCommandMatched:true,declaredCommandSha256:createHash('sha256').update(declared).digest('hex')};};
  const call={name:'run_commands',input:{commands:[declared]}};
  const result=await createNativeCodingRun(pipefailContract(),h.gateway,{model:scripted([
    {calls:[call]},{calls:[call]},{text:'Done.'},
    {assert(request){assert.match(JSON.stringify(request.messages),/no successful, stopped execution receipt/);},calls:[call]},
    {text:'Read the later successful result.'},
  ]),contractHash:hash,workspaceBase:h.base}).run();
  assert.equal(result.status,'completed');assert.equal(h.commands.length,3);assert.equal(result.modelOrdinal,5);
});

test('versioned profile cannot accept legacy receipt flags or a different declaration',async t=>{
  const declared='python -m unittest discover -s tests -v';
  const h=await host(t,{async openCodeWorkspace(){return{files:{'main.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:declared,verificationPolicy:'BASH_PIPEFAIL_V1'};}});
  h.gateway.executeCodeCommand=async(_c,value)=>({operationId:'not-bound',manifestHash:value.manifest.sha256,executorStopped:true,stopProofRef:'test:stop',
    resultAvailable:true,exitCode:0,stdout:'OK',stderr:'',commandHash:createHash('sha256').update(declared).digest('hex'),
    verificationPolicy:'LITERAL_COMMAND_V1',declaredCommandMatched:true,declaredCommandSha256:hash});
  const result=await createNativeCodingRun(pipefailContract(),h.gateway,{model:scripted([
    {calls:[{name:'run_commands',input:{commands:[declared]}}]}, {text:'Done.'},
  ]),contractHash:hash,workspaceBase:h.base}).run();
  assert.equal(result.status,'failed');
});
test('one native Agent reads, executes failing tests, edits a file, executes and finishes',async t=>{
  const h=await host(t);const model=scripted([
    {calls:[{name:'read_files',input:{files:[{path:'calc.py'},{path:'tests/test_calc.py'}]}}]},
    {calls:[{name:'run_commands',input:{commands:['python -m unittest discover -s tests -v']}}]},
    {assert:request=>assert.match(JSON.stringify(request.messages),/AssertionError/),calls:[{name:'editor',input:{path:'calc.py',old_text:'return a - b',new_text:'return a + b'}}]},
    {calls:[{name:'run_commands',input:{commands:['python -m unittest discover -s tests -v']}}]},
    {text:'Implemented the correction and inspected the passing test result.'},
  ]);
  const result=await createNativeCodingRun(contract(),h.gateway,{model,contractHash:hash,workspaceBase:h.base}).run();
  assert.equal(result.status,'completed');assert.equal(h.commands.length,2);assert.equal(h.admissions.length,5);
  assert.notEqual(h.commands[0].manifest.sha256,h.commands[1].manifest.sha256);
  assert.equal(h.workspace.files['calc.py'],'def add(a, b):\n    return a + b\n');
  assert.ok(h.events.some(e=>e.tool==='run_commands'&&e.status==='FAILED'));
  assert.ok(h.checkpoints.every(c=>c.contractHash===hash&&c.sdkSessionId===result.sdkSessionId));
});

test('sequential SDK batches start durable tools at execution time without blocking a later command',async t=>{
  const pending=new Set(),order=[];let reconciliationRequired=false;
  const h=await host(t);const finish=h.gateway.finishCodeTool;
  h.gateway.authorizeCodeTool=async(_c,value)=>{if(value.action==='TOOL'){pending.add(value.operationId);order.push('start:'+value.tool);}};
  h.gateway.finishCodeTool=async(c,value)=>{await finish(c,value);pending.delete(value.toolCallId);reconciliationRequired=pending.size>0;order.push('finish:'+value.result.toolName);};
  h.gateway.executeCodeCommand=async(_c,value)=>{
    assert.equal(reconciliationRequired,false,'Unexecuted SDK batch entries must not look like unknown running operations');
    h.commands.push(value);return{operationId:'actual-command',manifestHash:value.manifest.sha256,executorStopped:true,stopProofRef:'test:stop',exitCode:0,stdout:'OK',stderr:''};
  };
  const result=await createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{id:'read-first',name:'read_files',input:{files:[{path:'calc.py'}]}},{id:'execute-second',name:'run_commands',input:{commands:['python -m unittest discover -s tests -v']}}]},
    {text:'Inspected the test result.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(h.commands.length,1);
  assert.equal(order[0],'start:read_files');assert.equal(order[2],'start:run_commands');assert.equal(pending.size,0);
});

test('native compaction preserves completion feedback after the latest tool result in chronological order',async t=>{
  const verification='python -m unittest discover -s tests -v';
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:verification};},
    async budget(){return{remainingTokens:262144,remainingModelCalls:8,completionReservation:{phase:'COMPLETION',ratio:0.35}};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{h.commands.push(value);return{operationId:'actual-'+h.commands.length,manifestHash:value.manifest.sha256,
    commandHash:createHash('sha256').update(value.command).digest('hex'),executorStopped:true,stopProofRef:'test:stop',exitCode:0,stdout:'actual output',stderr:''};};
  const result=await createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:['pwd']}}]},
    {text:'The project appears complete.'},
    {assert(request){
      const guard=request.messages.findLastIndex(m=>m.role==='user'&&m.content.some(p=>p.type==='text'&&p.text.includes('Run the task-declared verification command')));
      const tool=request.messages.findLastIndex(m=>m.content.some(p=>p.type==='tool-result'));
      assert.ok(guard>tool,`Verification feedback must follow its causal tool result: guard=${guard}, tool=${tool}`);
    },calls:[{name:'run_commands',input:{commands:[verification]}}]},
    {text:'Verified the declared command.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(h.commands.length,2);
  assert.ok(h.checkpoints.some(c=>c.compactionCount>0),'Exercise the installed SDK compactor');
});

test('a host-verified fixed-cwd command remains valid after later inspection of the same code',async t=>{
  const verification='python -m unittest discover -s tests -v',approved='cd /workspace/code && '+verification;
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:verification,
    acceptanceCommandHashes:[verification,approved].map(v=>createHash('sha256').update(v).digest('hex'))};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{h.commands.push(value);return{operationId:'actual-'+h.commands.length,manifestHash:value.manifest.sha256,
    commandHash:createHash('sha256').update(value.command).digest('hex'),executorStopped:true,stopProofRef:'test:stop',exitCode:0,resultAvailable:true,stdout:'actual output',stderr:''};};
  const result=await createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[approved]}}]},
    {calls:[{name:'run_commands',input:{commands:['pwd']}}]},
    {text:'Verified and inspected the unchanged source.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(h.commands.length,2);
});

test('SDK object command receipts use canonical object identity at the verification gate',async t=>{
  const verification='python -m unittest discover -s tests -v',command={command:'cd /workspace/code && '+verification};
  const objectHash=createHash('sha256').update(JSON.stringify(command)).digest('hex');
  const strings=[verification,'cd /workspace/code && '+verification,"cd '/workspace/code' && "+verification,'cd "/workspace/code" && '+verification];
  const hashes=strings.flatMap(s=>[s,JSON.stringify({command:s})]).map(s=>createHash('sha256').update(s).digest('hex'));
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:verification,acceptanceCommandHashes:hashes};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{assert.deepEqual(value.command,command);h.commands.push(value);return{
    operationId:'object-command',manifestHash:value.manifest.sha256,commandHash:objectHash,executorStopped:true,
    stopProofRef:'test:stop',exitCode:0,resultAvailable:true,stdout:'OK',stderr:''};};
  const result=await createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[command]}}]}, {text:'Verified the current project.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(h.commands.length,1);
});

test('a zero-exit pipeline cannot substitute for the host-declared verification command',async t=>{
  const verification='python -m unittest discover -s tests -v';
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:verification,
    acceptanceCommandHashes:[verification,'cd /workspace/code && '+verification].map(v=>createHash('sha256').update(v).digest('hex'))};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{h.commands.push(value);return{operationId:'actual',manifestHash:value.manifest.sha256,
    commandHash:createHash('sha256').update(value.command).digest('hex'),executorStopped:true,stopProofRef:'test:stop',exitCode:0,resultAvailable:true,stdout:'FAILED (failures=3)',stderr:''};};
  const result=await createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[verification+' 2>&1 | tail -1']}}]}, {text:'Done.'},
  ])}).run();
  assert.equal(result.status,'failed');assert.equal(h.commands.length,1);
});

test('SDK stderr merge can verify the current version without accepting a pipeline',async t=>{
  const verification='python -m unittest discover -s tests -v',command={command:'cd /workspace/code && '+verification+' 2>&1'};
  const strings=[verification,'cd /workspace/code && '+verification,"cd '/workspace/code' && "+verification,'cd "/workspace/code" && '+verification];
  const identities=strings.flatMap(s=>[s,s+' 2>&1']).flatMap(s=>[s,JSON.stringify({command:s})]);
  const hashes=identities.map(s=>createHash('sha256').update(s).digest('hex'));
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:verification,acceptanceCommandHashes:hashes};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{assert.deepEqual(value.command,command);h.commands.push(value);return{
    operationId:'stderr-command',manifestHash:value.manifest.sha256,commandHash:createHash('sha256').update(JSON.stringify(command)).digest('hex'),executorStopped:true,
    stopProofRef:'test:stop',exitCode:0,resultAvailable:true,stdout:'actual merged test output',stderr:''};};
  const result=await createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[command]}}]}, {text:'Verified the current project.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(h.commands.length,1);
});

test('editing code invalidates the retained verification until the new version executes',async t=>{
  const verification='python -m unittest discover -s tests -v';
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',acceptanceCommand:verification};}});
  h.gateway.executeCodeCommand=async(_c,value)=>{h.commands.push(value);return{operationId:'actual-'+h.commands.length,manifestHash:value.manifest.sha256,
    commandHash:createHash('sha256').update(value.command).digest('hex'),executorStopped:true,stopProofRef:'test:stop',exitCode:0,resultAvailable:true,stdout:'OK',stderr:''};};
  const result=await createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[{name:'run_commands',input:{commands:[verification]}}]},
    {calls:[{name:'editor',input:{path:'calc.py',old_text:'value = 1',new_text:'value = 2'}}]},
    {text:'Done.'},
    {assert(request){assert.match(JSON.stringify(request.messages),/current project version has no successful/);},calls:[{name:'run_commands',input:{commands:[verification]}}]},
    {text:'Verified the changed source.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(h.commands.length,2);
  assert.notEqual(h.commands[0].manifest.sha256,h.commands[1].manifest.sha256);
});
test('restored SDK retains session/code and cumulative model ordinal',async t=>{
  let interrupted=false;
  const h=await host(t,{afterCheckpoint(value){if(!interrupted&&value.messages.some(m=>m.content?.some(p=>p.type==='tool-result'&&p.toolName==='editor'))){interrupted=true;throw Error('WORKER_INTERRUPTED');}}});
  const c=contract();const first=scripted([
    {calls:[{name:'editor',input:{path:'calc.py',old_text:'return a - b',new_text:'return a + b'}}]},
  ]);
  const stopped=await createNativeCodingRun(c,h.gateway,{model:first,contractHash:hash,workspaceBase:h.base}).run();
  assert.equal(stopped.status,'failed');
  const checkpoint=h.checkpoints.at(-1),source=h.workspace.manifest.sha256;
  assert.equal(checkpoint.modelOrdinal,1);
  const restored=scripted([{calls:[{name:'run_commands',input:{commands:['python -m unittest discover -s tests -v']}}]},
    {calls:[{name:'run_commands',input:{commands:['python -m unittest discover -s tests -v']}}]},{text:'Verified the unchanged restored source.'}],checkpoint.modelOrdinal);
  const result=await createNativeCodingRun(c,h.gateway,{model:restored,checkpoint,contractHash:hash,workspaceBase:h.base}).run();
  assert.equal(result.sdkSessionId,checkpoint.sdkSessionId);assert.equal(result.modelOrdinal,4);
  assert.equal(h.workspace.manifest.sha256,source);assert.equal(h.commands.length,2);
});
test('model schema disclosure outside the task grant is denied before provider dispatch',async t=>{
  const h=await host(t);let dispatched=false;const c=contract();c.authorization.allowedTools=c.authorization.allowedTools.filter(n=>n!=='editor');
  await assert.rejects(createNativeCodingRun(c,h.gateway,{model:{async *stream(){dispatched=true;}},contractHash:hash,workspaceBase:h.base}).run(),/immutable task grants/);
  assert.equal(dispatched,false);
});
test('a stopped Runtime failure cannot be shown as a completed SDK task',async t=>{
  const h=await host(t,{async openCodeWorkspace(){return{files:{'main.py':'print(1)\n'},modelId:'qwen3.8-flash',requireExecution:false};},
    async finish(){return{status:'FAILED'};}});
  const result=await createNativeCodingRun(contract(),h.gateway,{model:scripted([{text:'Done.'}]),contractHash:hash,workspaceBase:h.base}).run();
  assert.equal(result.status,'failed');
});

test('archived native tool receipts replay without another edit or command execution',async t=>{
  const h=await host(t);
  const edit={id:'edit-original',name:'editor',input:{path:'calc.py',old_text:'return a - b',new_text:'return a + b'}};
  const run={id:'command-original',name:'run_commands',input:{commands:['python -m unittest discover -s tests -v']}};
  const result=await createNativeCodingRun(contract(),h.gateway,{contractHash:hash,workspaceBase:h.base,model:scripted([
    {calls:[edit]}, {calls:[edit]}, {calls:[run]}, {calls:[run]},
    {calls:[{...run,id:'new-command'}]}, {text:'Inspected the passing result.'},
  ])}).run();
  assert.equal(result.status,'completed');assert.equal(h.workspace.manifest.version,1);
  assert.equal(h.commands.length,2);
  assert.equal(h.events.filter(e=>e.toolCallId==='command-original'&&e.status==='FAILED').length,2);
});

test('one native read_files tool reads scoped project files and published Skill references on both operating systems',async t=>{
  const body='---\nname: project-checks\ndescription: Inspect project tests.\n---\nMETHOD_CHECK_ONLY\nRead references/check.md when needed.\n';
  const reference='Inspect the declared test outcome.\n',sha=s=>createHash('sha256').update(s).digest('hex');
  const c=contract();c.authorization.allowedTools.push('skills');
  c.runtimeContext={builtinSkillPolicy:{allowedIds:[]},skills:{skills:[{id:'checks',name:'project-checks',description:'Inspect project tests.',version:'v1'}]}};
  const h=await host(t,{async openCodeWorkspace(){return{files:{'calc.py':'value = 1\n'},modelId:'qwen3.8-flash',requireExecution:false};},
    async readSkill(_c,id,path){return path?{skillId:id,version:'v1',content:reference,encoding:'utf-8',sha256:sha(reference)}:
      {skillId:id,version:'v1',nativeOriginal:true,instructions:body,contentSha256:sha(body),resources:[{path:'references/check.md',sha256:sha(reference)}]};}});
  const skill={name:'skills',input:{}},read={name:'read_files',input:{files:[]}};
  const result=await createNativeCodingRun(c,h.gateway,{workspaceBase:h.base,contractHash:hash,model:scripted([
    {assert(request){
      const schema=request.tools.find(t=>t.name==='skills');assert.doesNotMatch(JSON.stringify(request.tools),/METHOD_CHECK_ONLY/);
      skill.input.skill=schema.description.match(/runtime-agent-[a-f0-9]+:project-checks/)[0];
    },calls:[skill]},
    {assert(request){
      const output=request.messages.flatMap(m=>m.content??[]).find(p=>p.type==='tool-result'&&p.toolName==='skills').output;
      const root=output.match(/<skill-root>(.*?)<\/skill-root>/)[1];
      read.input.files=[{path:join(root,'references/check.md')},{path:'calc.py'}];
    },calls:[read]},
    {assert(request){const output=JSON.stringify(request.messages.flatMap(m=>m.content??[]).find(p=>p.type==='tool-result'&&p.toolName==='read_files'));assert.match(output,/Inspect the declared test outcome/);assert.match(output,/value = 1/);assert.doesNotMatch(output,/CODE_PATH_DENIED/);},text:'Inspected both authorized views.'},
  ])}).run();
  assert.equal(result.status,'completed',JSON.stringify(result.error));assert.equal(h.commands.length,0);
});
