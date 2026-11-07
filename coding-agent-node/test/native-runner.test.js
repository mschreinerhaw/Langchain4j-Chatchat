import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {createNativeCodingRun,NATIVE_CODING_PROFILE} from '../src/native-runner.js';

const hash='a'.repeat(64);
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
