import test from 'node:test';
import assert from 'node:assert/strict';
import {createToolCatalog,DEFERRED_TOOLS_PROFILE} from '../src/tool-catalog.js';
import {createCodingRun} from '../src/runner.js';
import {modelInputBudget} from '../src/context-budget.js';

test('schema disclosure is task scoped, atomic, and invalidates on metadata changes',()=>{
  const tools=[{name:'read',description:'Read authorized data',inputSchema:{type:'object'}}];
  const catalog=createToolCatalog(tools);
  assert.deepEqual(catalog.expose(tools),[]);
  assert.throws(()=>catalog.describe(['read','ungranted']),/authorized index/);
  assert.equal(catalog.has('read'),false);
  const result=catalog.describe(['read']);result.loadedTools[0]='changed';
  assert.deepEqual(catalog.describe(['read']).loadedTools,['read']);
  assert.equal(catalog.expose(tools)[0].inputSchema,tools[0].inputSchema);
  assert.equal(result.tools,undefined);
  assert.equal(createToolCatalog(tools,catalog.snapshot()).has('read'),true);
  assert.equal(createToolCatalog([{...tools[0],description:'new metadata'}],catalog.snapshot()).has('read'),false);
  assert.equal(createToolCatalog([],catalog.snapshot()).has('read'),false);
});

test('real Cline session discovers schemas before loading a skill; disclosure grants no execution',async()=>{
  const task={schemaVersion:'runtime_os.coding.v3',taskId:'disclosure',scope:{tenantId:'t',userId:'u',runId:'r'},
    metadataRefs:[DEFERRED_TOOLS_PROFILE],objective:{instruction:'Explain version control; use a methodology only when helpful'},
    authorization:{allowedTools:['skill_read','tool_describe'],allowedSideEffects:[],allowedDataRefs:[],networkPolicy:'NONE'},
    outputContract:{resultFormat:'JSON_LINES',requiredFiles:[]},budget:{maxModelCalls:4}};
  let calls=0,checkpoint;
  const run=createCodingRun(task,{reserveModel(){},settleModel(){},finish(){},checkpoint(_task,value){checkpoint=value;},
    executePython(){assert.fail('No execution grant');}},{nativeSdk:true,model:{async *stream(request){
      calls++;
      assert.ok(!request.tools.some(tool=>tool.name==='python_execute'));
      if(calls===1){
        assert.deepEqual(request.tools.map(tool=>tool.name),['tool_describe']);
        yield {type:'text-delta',text:'<initial_plan>Discover the authorized '};
        yield {type:'text-delta',text:'method, then answer the question.</initial_plan>'};
        yield {type:'tool-call-delta',toolCallId:'schemas',toolName:'tool_describe',input:{names:['skill_read']}};
      }else if(calls===2){
        assert.ok(request.tools.some(tool=>tool.name==='skill_read'));
        yield {type:'tool-call-delta',toolCallId:'method',toolName:'skill_read',input:{skillId:'builtin:python-data-analysis'}};
      }else{yield {type:'text-delta',text:'Version control records changes.'};yield {type:'finish',reason:'stop'};return;}
      yield {type:'finish',reason:'tool-calls'};
    }}});
  assert.equal((await run.run()).status,'completed');
  assert.equal(calls,3);assert.deepEqual(checkpoint.toolCatalog.loaded,['skill_read']);
  assert.equal(run.events.filter(event=>event.type==='PLAN_READY').length,1);
  assert.equal(run.events.find(event=>event.type==='PLAN_READY').modelCall,1);
  assert.match(run.events.find(event=>event.type==='PLAN_READY').planText,/Discover the authorized method/);
  assert.ok(run.events.some(event=>event.type==='SESSION_TIMING'&&event.constructionDurationMs>=0));
});

test('context allowance accounts for declared window, reply reservation and safety margin',()=>{
  assert.equal(modelInputBudget({contextWindowTokens:10000,outputReserveTokens:2000}),7000);
  assert.equal(modelInputBudget({contextWindow:10000,maxInputTokens:3000},2000),3000);
  assert.equal(modelInputBudget({}),undefined);
  assert.throws(()=>modelInputBudget({contextWindowTokens:1000,outputReserveTokens:1000}),/cannot fit/);
});

test('optional timing delivery failure cannot strand a prepared task before the model loop',async()=>{
  let calls=0;
  const task={schemaVersion:'runtime_os.coding.v3',taskId:'timing',scope:{tenantId:'t',userId:'u',runId:'r'},
    objective:{instruction:'Explain version control'},authorization:{allowedTools:['skill_read'],allowedSideEffects:[],allowedDataRefs:[],networkPolicy:'NONE'},
    outputContract:{resultFormat:'JSON_LINES',requiredFiles:[]},budget:{maxModelCalls:1}};
  const result=await createCodingRun(task,{reserveModel(){},settleModel(){},finish(){},executePython(){},
    event(event){if(event.type==='SESSION_TIMING')throw new Error('Optional telemetry unavailable');}},
    {nativeSdk:true,model:{async *stream(){calls++;yield {type:'text-delta',text:'Version control records changes.'};yield {type:'finish',reason:'stop'};}}}).run();
  assert.equal(calls,1);assert.equal(result.status,'completed');
});
