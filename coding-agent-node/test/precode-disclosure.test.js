import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingRun } from '../src/runner.js';
import { createCodingProgress } from '../src/coding-progress.js';

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
