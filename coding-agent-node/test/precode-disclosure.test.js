import test from 'node:test';
import assert from 'node:assert/strict';
import { createSkillDisclosure } from '../src/skill-disclosure.js';
import { createCodingRun } from '../src/runner.js';
import { createCodingProgress } from '../src/coding-progress.js';
import { sdkPrepareTurn } from '../src/sdk-adapter.js';

test('Skill reuse requires the exact versioned body in the SDK-prepared context',()=>{
  const d=createSkillDisclosure(),input={skillId:'s'},body={skillId:'s',version:'1',instructions:'PRIVATE_BODY',contentSha256:'hash'};
  assert.equal(d.disclose(body,input).result,body);
  d.prepare({messages:[{content:[{type:'tool-result',toolName:'skill_read',toolCallId:'original',output:body}]}]});
  const reused=d.disclose(body,input);
  assert.equal(reused.result.reusedFromToolCallId,'original');assert.equal(reused.observation.contentReused,true);
  assert.doesNotMatch(JSON.stringify(reused.result),/PRIVATE_BODY/);
  assert.equal(d.disclose({...body,version:'2'},input).result.instructions,'PRIVATE_BODY');
  d.prepare({messages:[{content:[{type:'tool-result',toolName:'skill_read',toolCallId:'original',output:{...body,instructions:'truncated'}}]}]});
  assert.equal(d.disclose(body,input).result.instructions,'PRIVATE_BODY');
  assert.equal(createSkillDisclosure().disclose(body,input).observation.duplicateRead,false);
});

test('Reference paging preserves original integrity metadata and Unicode offsets; native bodies remain whole',()=>{
  const d=createSkillDisclosure(),body={skillId:'s',version:'1',path:'refs/a.md',content:'a😀中z',sha256:'original-hash',encoding:'utf-8'};
  const page=d.disclose(body,{path:'refs/a.md',offset:1,limit:2}).result;
  assert.equal(page.content,'😀中');assert.equal(page.nextOffset,3);assert.equal(page.sha256,'original-hash');assert.equal(page.complete,false);
  assert.equal(body.content,'a😀中z');assert.equal(d.disclose(body,{path:'refs/a.md',offset:3,limit:2}).result.content,'z');
  assert.throws(()=>d.disclose(body,{path:'SKILL.md',offset:0}),/in full/);
  assert.throws(()=>d.disclose({...body,encoding:'base64'},{path:body.path,offset:0}),/UTF-8/);
});

test('Installed native SDK compaction evicts an early Skill and its next authorized disclosure restores full content',async()=>{
  const disclosure=createSkillDisclosure(),body={skillId:'method',version:'1',instructions:'EARLY_METHOD_'+('methodology '.repeat(3000))};
  const input={skillId:'method'};
  const messages=[{role:'user',content:[{type:'text',text:'Analyze only authorized inputs'}]},
    {role:'assistant',content:[{type:'tool-call',toolCallId:'early-skill',toolName:'skill_read',input}]},
    {role:'tool',content:[{type:'tool-result',toolCallId:'early-skill',toolName:'skill_read',output:body}]}];
  for(let i=0;i<12;i++)messages.push(
    {role:'assistant',content:[{type:'tool-call',toolCallId:'later-'+i,toolName:'python_execute',input:{source:'x'.repeat(16000)}}]},
    {role:'tool',content:[{type:'tool-result',toolCallId:'later-'+i,toolName:'python_execute',output:{status:'FAILED',diagnosis:'repair '+i},isError:true}]});
  disclosure.prepare({messages});assert.equal(disclosure.disclose(body,input).result.reusedFromToolCallId,'early-skill');
  const compacted=await sdkPrepareTurn('skill-compaction')({agentId:'skill-compaction',iteration:13,messages,tools:[],
    model:{info:{maxInputTokens:16384,contextWindow:20000}},systemPrompt:'Authorized analysis',signal:new AbortController().signal});
  assert.ok(compacted);assert.ok(!compacted.messages.flatMap(m=>m.content).some(p=>p.type==='tool-result'&&p.toolCallId==='early-skill'));
  disclosure.prepare(compacted);
  const reread=disclosure.disclose(structuredClone(body),input);
  assert.equal(reread.result.instructions,body.instructions);assert.equal(reread.observation.contentReused,false);
  assert.equal(messages[2].content[0].output.instructions,body.instructions);
});

test('Invalid snapshot reference is structured repair feedback and Cline corrects it in the original task',async()=>{
  const contract={schemaVersion:'runtime_os.coding.v3',taskId:'ref-test',scope:{tenantId:'t',userId:'u',runId:'r'},
    objective:{instruction:'Read the delivered input'},inputSnapshotRef:'bound-snapshot',
    authorization:{allowedTools:['input_read'],allowedDataRefs:[],allowedSideEffects:[],networkPolicy:'NONE'},
    outputContract:{resultFormat:'JSON_LINES'},budget:{maxModelCalls:3}};
  let calls=0,reads=0;
  const gateway={async reserveModel(){},async settleModel(){},async finish(){},async executePython(){assert.fail();},
    async readInput(_c,input){reads++;assert.equal(input.datasetReference,'delivered-dataset');return {text:'actual input'};}};
  const model={async *stream(request){calls++;
    if(calls===2){const output=request.messages.flatMap(m=>m.content).find(p=>p.type==='tool-result').output;
      assert.equal(output.error.code,'INVALID_REFERENCE_TYPE');assert.equal(output.error.receivedType,'INPUT_SNAPSHOT_REFERENCE');}
    if(calls<3)yield {type:'tool-call-delta',toolCallId:'read-'+calls,toolName:'input_read',input:{datasetReference:calls===1?'bound-snapshot':'delivered-dataset',offset:0,limit:100}};
    else yield {type:'text-delta',text:'Read the authorized data.'};
    yield {type:'finish',reason:'stop'};
  }};
  const run=createCodingRun(contract,gateway,{model});const result=await run.run();assert.equal(result.status,'completed',JSON.stringify(result));
  assert.equal(reads,1);assert.equal(run.events.find(e=>e.diagnosticCode==='INVALID_REFERENCE_TYPE').status,'FAILED');
});

test('Dynamic Skill duplicate reuse still rechecks Runtime authority and never hides a revocation',async()=>{
  const contract={schemaVersion:'runtime_os.coding.v3',taskId:'revocation',scope:{tenantId:'t',userId:'u',runId:'r'},
    objective:{instruction:'Use the necessary methodology'},authorization:{allowedTools:['skill_read'],allowedDataRefs:[],allowedSideEffects:[],networkPolicy:'NONE'},
    runtimeContext:{skills:{skills:[{id:'s',name:'method',version:'1'}]}},outputContract:{resultFormat:'JSON_LINES'},budget:{maxModelCalls:4}};
  let turns=0,reads=0;
  const gateway={async reserveModel(){},async settleModel(){},async finish(){},async executePython(){assert.fail();},
    async readSkill(){if(++reads===3)throw new Error('Skill revoked');return {skillId:'s',version:'1',instructions:'PINNED_METHOD'};}};
  const model={async *stream(request){turns++;
    if(turns===3)assert.equal(request.messages.flatMap(m=>m.content).filter(p=>p.type==='tool-result').at(-1).output.contentAlreadyInContext,true);
    if(turns===4)assert.match(JSON.stringify(request.messages),/Skill revoked/);
    if(turns<4)yield {type:'tool-call-delta',toolCallId:'skill-'+turns,toolName:'skill_read',input:{skillId:'s'}};
    else yield {type:'text-delta',text:'The methodology was revoked; no additional access attempted.'};
    yield {type:'finish',reason:'stop'};
  }};
  const result=await createCodingRun(contract,gateway,{model}).run();assert.equal(result.status,'completed',JSON.stringify(result));assert.equal(reads,3);
});

test('New necessary Skills remain progress beyond the preparation threshold; reused bodies do not reset a stall',()=>{
  const p=createCodingProgress();
  for(let n=1;n<=8;n++){
    p.tool({toolName:'skill_read',input:{skillId:'s'+n}},{output:{instructions:'new '+n}});
    assert.equal(p.model(n).feedback,'');
  }
  for(let n=9;n<=11;n++){
    p.tool({toolName:'skill_read',input:{skillId:'s1'}},{output:{contentAlreadyInContext:true}});
    const turn=p.model(n);if(n===10)assert.match(turn.feedback,/NO_NEW_TOOL_RESULT/);
  }
});

test('Pre-code usage and content observations retain unknown usage separately and exclude sensitive text',()=>{
  const p=createCodingProgress();p.model(1);p.usage({inputTokens:50});p.model(2);p.usage({});
  p.observe({type:'SKILLS_LOADED',skillId:'s',skillVersion:'1',skillPath:'SKILL.md',skillContentHash:'hash',skillSourceBytes:100,skillContentBytes:100,skillEstimatedTokens:25});
  p.observe({type:'SKILLS_LOADED',skillId:'s',skillVersion:'1',skillPath:'SKILL.md',skillContentHash:'hash',skillSourceBytes:100,skillContentBytes:0,duplicateRead:true,contentReused:true});
  p.observe({type:'CODE_PROPOSED'});p.model(3);p.usage({inputTokens:70});
  const s=p.snapshot();assert.equal(s.preCodeModelCalls,2);assert.equal(s.preCodeInputTokens,50);
  assert.equal(s.preCodeUsageKnownCalls,1);assert.equal(s.loadedSkillCount,1);assert.equal(s.loadedSkillBytes,100);
  assert.equal(s.skillDeliveredBytes,100);assert.equal(s.duplicateSkillReads,1);assert.equal(s.skillReuses,1);
});

for(const [category,partial] of [['MODEL_REQUEST_TIMEOUT',false],['CALLBACK_RESPONSE_INTERRUPTED',true],['CALLBACK_DEADLINE',false]])
test(`Model ${category} stays distinct from Python failure and does not retry unknown operations`,async()=>{
  const contract={schemaVersion:'runtime_os.coding.v1',taskId:'timeout',scope:{tenantId:'t',userId:'u',runId:'r'},
    authorization:{allowedTools:[],allowedSideEffects:[],networkPolicy:'NONE'},budget:{maxModelCalls:3}};
  let calls=0;
  const model={async *stream(){calls++;if(partial)yield {type:'text-delta',text:'partial response'};
    throw Object.assign(new Error('controlled model failure'),{errorCategory:category});}};
  const run=createCodingRun(contract,{async reserveModel(){},async settleModel(){assert.fail('Unknown provider usage cannot settle');},
    async finish(){},async executePython(){assert.fail('Model failure cannot execute Python');}},{model});
  const result=await run.run();assert.equal(result.status,'failed');assert.equal(calls,1);
  assert.equal(run.events.find(e=>e.type==='MODEL_FAILED').diagnosticCode,category);
  const timing=run.events.find(e=>e.type==='MODEL_TIMING');assert.equal(timing.callbackFirstEventMs===null,!partial);
});
