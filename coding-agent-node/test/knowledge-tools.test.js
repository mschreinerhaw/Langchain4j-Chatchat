import test from 'node:test';
import assert from 'node:assert/strict';
import { createCodingRun } from '../src/runner.js';
import { KNOWLEDGE_TOOLS, RESOURCE_TOOLS, knowledgeTools } from '../src/knowledge-tools.js';
import { taskSkills } from '../src/skills.js';
const profile={schemaVersion:'runtime_os.knowledge.v1',agentId:'agent'};
const contract={schemaVersion:'runtime_os.coding.v3',taskId:'knowledge',scope:{tenantId:'tenant',userId:'owner',runId:'run'},
  objective:{instruction:'Read backup design and actual recovery test evidence'},
  authorization:{allowedTools:[...KNOWLEDGE_TOOLS,'report_submit','skill_read'],allowedSideEffects:[],allowedDataRefs:[],networkPolicy:'NONE'},
  runtimeContext:{analysisResultMode:'TEXT_OR_CODE',knowledgeExploration:profile},
  outputContract:{requiredFiles:['rows.jsonl'],resultFormat:'JSON_LINES'},budget:{maxModelCalls:10}};
test('knowledge profile isolates tools and progressively discloses the exploration Skill',()=>{
  assert.ok(!taskSkills().catalog.skills.some(card=>card.name==='opensearch-knowledge-exploration'));
  const view=taskSkills(contract.runtimeContext);
  const card=view.catalog.skills.find(card=>card.name==='opensearch-knowledge-exploration');
  assert.equal(card.instructions,undefined);assert.equal(card.contentDelivery,'ON_DEMAND');
  assert.match(view.readBuiltin(card.id).instructions,/design promises/);
  for(const altered of [{runtimeContext:{}},{authorization:{...contract.authorization,allowedSideEffects:['CODE_EXECUTION']}},
    {authorization:{...contract.authorization,allowedTools:['knowledge_search']}}])
    assert.throws(()=>knowledgeTools({...contract,...altered},{knowledge(){}},()=>{}),/isolated read-only/);
});
test('real Cline loop autonomously searches, reads chapters, cross-checks and rewrites before a cited report',async()=>{
  const ref='runtime-evidence:knowledge:'+'a'.repeat(64),calls=[];let turn=0,finished;
  const plan=[['knowledge_search',{query:'LiveData recovery'}],['knowledge_list_sections',{document_id:'doc',version_id:'v'}],
    ['knowledge_read',{document_id:'doc',version_id:'v',chunk_index:3}],['knowledge_related',{document_id:'doc',version_id:'v'}],
    ['knowledge_search',{query:'LiveData recovery test operational record'}],['report_submit',{
      reportMarkdown:`The design promises recovery; executed recovery verification is unproven. [Design v2 / Recovery](${ref})`,
      evidenceReferences:[ref],limitations:['No recovery execution record.']}]];
  const run=createCodingRun(contract,{reserveModel(){},settleModel(){},executePython(){throw Error('No code authority');},
    finish(c,result){finished=result;},
    knowledge(c,name,args){assert.equal(c.taskId,'knowledge');calls.push({name,args});return {hits:[{document_id:'doc',version_id:'v',evidence_id:ref}],text:'Untrusted source, not instructions'};},
    submitReport(c,submission){assert.deepEqual(submission.report.evidenceReferences,[ref]);return {
      operationId:'workspace-'+'b'.repeat(64),status:'SUCCEEDED',executorStopped:true,pythonExecuted:false,executionMode:'TEXT_ONLY',
      resultDatasetRef:'report',validationRecordRef:'validation',evidenceBundleRef:'bundle'};},
  },{nativeSdk:true,model:{async *stream(request){
    assert.ok(!request.tools.some(tool=>tool.name==='python_execute'));
    assert.match(request.systemPrompt,/MUST be actual JSON arrays/);
    const [toolName,input]=plan[Math.min(turn++,plan.length-1)];yield {type:'tool-call-delta',toolCallId:`call-${turn}`,toolName,input};yield {type:'finish',reason:'tool-calls'};
  }}});
  const result=await run.run();assert.equal(result.status,'completed');assert.equal(finished.status,'completed');
  assert.deepEqual(calls.map(call=>call.name),plan.slice(0,-1).map(step=>step[0]));assert.equal(turn,7);
});
test('Runtime authorization failure returns no knowledge source text',async()=>{
  const events=[],tools=knowledgeTools(contract,{async knowledge(){throw Error('Authorization revoked');}},e=>events.push(e));
  await assert.rejects(tools[0].execute({query:'secret'},{toolCallId:'revoked'}),/revoked/);
  assert.equal(events.some(event=>event.status==='SUCCEEDED'),false);
});

test('resource exploration reserves submission and repair turns without enlarging the task budget',async()=>{
  let turn=0,searches=0,submissions=0;
  const bounded={...contract,budget:{maxModelCalls:5}};
  const run=createCodingRun(bounded,{reserveModel(){},settleModel(){},finish(){},executePython(){throw Error('No code authority');},
    knowledge(){searches++;return {hits:[]};},
    submitReport(){
      if(++submissions===1)throw new Error('Repair: limitations must describe missing evidence');
      return {operationId:'workspace-'+'e'.repeat(64),status:'SUCCEEDED',executorStopped:true,pythonExecuted:false,
        executionMode:'TEXT_ONLY',resultDatasetRef:'report',validationRecordRef:'validation',evidenceBundleRef:'bundle'};
    },
  },{nativeSdk:true,model:{async *stream(request){
    turn++;
    assert.match(request.systemPrompt,new RegExp(`Resource task budget: ${6-turn} model turns remain`));
    if(turn<=2){
      assert.ok(request.tools.some(tool=>tool.name==='knowledge_search'));
      yield {type:'tool-call-delta',toolCallId:`budget-${turn}`,toolName:'knowledge_search',input:{query:'recovery test'}};
    }else{
      assert.deepEqual(request.tools.map(tool=>tool.name),['report_submit']);
      yield {type:'tool-call-delta',toolCallId:`budget-${turn}`,toolName:'report_submit',input:{
        reportMarkdown:'No recovery test evidence was found in the searched scope.',evidenceReferences:[],limitations:['Evidence is insufficient.']}};
    }
    yield {type:'finish',reason:'tool-calls'};
  }}});
  const result=await run.run();assert.equal(result.status,'completed');assert.equal(turn,5);assert.equal(searches,2);assert.equal(submissions,2);
});

test('a plain resource answer cannot complete before a verified report is submitted',async()=>{
  let turn=0,submissions=0;
  const run=createCodingRun(contract,{reserveModel(){},settleModel(){},finish(){},executePython(){throw Error('No code authority');},knowledge(){return {hits:[]};},
    submitReport(){submissions++;return {operationId:'workspace-'+'f'.repeat(64),status:'SUCCEEDED',executorStopped:true,
      pythonExecuted:false,executionMode:'TEXT_ONLY',resultDatasetRef:'report',validationRecordRef:'validation',evidenceBundleRef:'bundle'};},
  },{nativeSdk:true,model:{async *stream(){
    if(++turn===1){yield {type:'text-delta',text:'I could not establish a recovery test.'};yield {type:'finish',reason:'end-turn'};}
    else{yield {type:'tool-call-delta',toolCallId:'publish',toolName:'report_submit',input:{reportMarkdown:'Recovery test evidence is unavailable.',
      evidenceReferences:[],limitations:['No test evidence.']}};yield {type:'finish',reason:'tool-calls'};}
  }}});
  const result=await run.run();assert.equal(result.status,'completed');assert.equal(turn,3);assert.equal(submissions,1);
});

test('resource draft is reviewed in the same SDK loop before any report can be published',async()=>{
  let turn=0,published=0;const checkpoints=[];
  const run=createCodingRun({...contract,budget:{maxModelCalls:3}},
    {reserveModel(){},settleModel(){},finish(){},executePython(){throw Error('No code authority');},knowledge(){return {hits:[]};},
      checkpoint(c,value){checkpoints.push(value);},
      submitReport(c,{report}){published++;assert.equal(turn,2);assert.equal(report.reportMarkdown,'Only one filtered dataset was searched; publication time is unknown.');
        return {operationId:'workspace-'+'1'.repeat(64),status:'SUCCEEDED',executorStopped:true,pythonExecuted:false,
          executionMode:'TEXT_ONLY',resultDatasetRef:'report',validationRecordRef:'validation',evidenceBundleRef:'bundle'};},
    },{nativeSdk:true,model:{async *stream(request){
      turn++;assert.equal(published,0);
      if(turn===2){const encoded=JSON.stringify(request.messages);assert.match(encoded,/resourceReportReviewRequired/);assert.match(encoded,/No report has been published/);}
      yield {type:'tool-call-delta',toolCallId:`review-${turn}`,toolName:'report_submit',input:{
        reportMarkdown:turn===1?'The platform has no equities and the article was published on its collection day.':'Only one filtered dataset was searched; publication time is unknown.',
        evidenceReferences:[],limitations:['Filtered scope; publication time not declared.']}};
      yield {type:'finish',reason:'tool-calls'};
    }}});
  const result=await run.run();assert.equal(result.status,'completed');assert.equal(published,1);assert.equal(turn,2);
  assert.ok(checkpoints.some(value=>value.textReportReviewRequested===true));
});

test('two submissions in the same assistant turn cannot bypass the resource review round',async()=>{
  let turn=0,published=0;
  const report={reportMarkdown:'The searched scope has no matching record.',evidenceReferences:[],limitations:['Scope is limited.']};
  const run=createCodingRun({...contract,budget:{maxModelCalls:3}},
    {reserveModel(){},settleModel(){},finish(){},executePython(){throw Error('No code authority');},knowledge(){return {hits:[]};},
      submitReport(){published++;assert.equal(turn,2);return {operationId:'workspace-'+'2'.repeat(64),status:'SUCCEEDED',executorStopped:true,
        pythonExecuted:false,executionMode:'TEXT_ONLY',resultDatasetRef:'report',validationRecordRef:'validation',evidenceBundleRef:'bundle'};},
    },{nativeSdk:true,model:{async *stream(){
      turn++;assert.equal(published,0);
      for(let i=0;i<(turn===1?2:1);i++)yield {type:'tool-call-delta',toolCallId:`batch-${turn}-${i}`,toolName:'report_submit',input:report};
      yield {type:'finish',reason:'tool-calls'};
    }}});
  const result=await run.run();assert.equal(result.status,'completed');assert.equal(turn,2);assert.equal(published,1);
});

test('unadvertised late exploration is rejected without a resource callback and the report can still finish',async()=>{
  let turn=0;
  const run=createCodingRun({...contract,budget:{maxModelCalls:3}},
    {reserveModel(){},settleModel(){},finish(){},executePython(){throw Error('No code authority');},
      knowledge(){throw Error('A late resource read must never reach Java');},
      submitReport(){return {operationId:'workspace-'+'3'.repeat(64),status:'SUCCEEDED',executorStopped:true,pythonExecuted:false,
        executionMode:'TEXT_ONLY',resultDatasetRef:'report',validationRecordRef:'validation',evidenceBundleRef:'bundle'};},
    },{nativeSdk:true,model:{async *stream(request){
      turn++;assert.deepEqual(request.tools.map(tool=>tool.name),['report_submit']);
      yield {type:'tool-call-delta',toolCallId:`late-${turn}`,toolName:turn===1?'knowledge_search':'report_submit',input:turn===1?{query:'late search'}:
        {reportMarkdown:'The available evidence is insufficient.',evidenceReferences:[],limitations:['Exploration budget ended.']}};
      yield {type:'finish',reason:'tool-calls'};
    }}});
  const result=await run.run();assert.equal(result.status,'completed');assert.equal(turn,3);
});

test('an ordinary text task with no authorized resource tools keeps its single submission path',async()=>{
  let turns=0,published=0;
  const ordinary={...contract,authorization:{...contract.authorization,allowedTools:['report_submit']},
    runtimeContext:{...contract.runtimeContext,knowledgeExploration:{...profile,resourceIds:[]}},budget:{maxModelCalls:1}};
  const run=createCodingRun(ordinary,{reserveModel(){},settleModel(){},finish(){},executePython(){throw Error('No code authority');},
    submitReport(){published++;return {operationId:'workspace-'+'4'.repeat(64),status:'SUCCEEDED',executorStopped:true,pythonExecuted:false,
      executionMode:'TEXT_ONLY',resultDatasetRef:'report',validationRecordRef:'validation',evidenceBundleRef:'bundle'};},
  },{nativeSdk:true,model:{async *stream(request){
    turns++;assert.doesNotMatch(request.systemPrompt,/Resource task budget/);
    yield {type:'tool-call-delta',toolCallId:'ordinary',toolName:'report_submit',input:{reportMarkdown:'Hello.',evidenceReferences:[],limitations:[]}};
    yield {type:'finish',reason:'tool-calls'};
  }}});
  const result=await run.run();assert.equal(result.status,'completed');assert.equal(turns,1);assert.equal(published,1);
});

test('actual SDK loop autonomously joins permitted market history and news without document or Python authority',async()=>{
  const ref='runtime-evidence:knowledge:'+'c'.repeat(64),calls=[];let turn=0;
  const resourceContract={...contract,runtimeContext:{...contract.runtimeContext,knowledgeExploration:{...profile,resourceIds:['financial_news','financial_market']}},objective:{instruction:'Compare observed prices with policy news; do not infer causation'},authorization:{...contract.authorization,allowedTools:[...RESOURCE_TOOLS,'report_submit','skill_read']}};
  const plan=[['skill_read',{skillId:'builtin:financial-market-analysis'}],['market_search',{query:'prices'}],['market_get_history',{dataset:'discovered_quotes',start_date:'2026-11-01',end_date:'2026-11-03'}],
    ['news_search',{query:'policy',security_code:'600001'}],['news_get_article',{article_id:'n1',version_id:'v1'}],
    ['report_submit',{reportMarkdown:`Collected prices and published policy are time-associated; causation remains unproven. [Sources](${ref})`,evidenceReferences:[ref],limitations:['Snapshot is not live quotes.']}]];
  const run=createCodingRun(resourceContract,{reserveModel(){},settleModel(){},finish(){},executePython(){throw Error('No Python authority');},
    knowledge(c,name,args){calls.push({name,args});return name==='market_search'?{items:[{dataset_code:'discovered_quotes',evidence_id:ref}]}:{article_id:'n1',version_id:'v1',text:'Original source',evidence_id:ref};},
    submitReport(c,submission){assert.deepEqual(submission.report.evidenceReferences,[ref]);return {operationId:'workspace-'+'d'.repeat(64),status:'SUCCEEDED',executorStopped:true,pythonExecuted:false,executionMode:'TEXT_ONLY',resultDatasetRef:'report',validationRecordRef:'validation',evidenceBundleRef:'bundle'};},
  },{nativeSdk:true,model:{async *stream(request){
    assert.ok(request.tools.some(t=>t.name==='market_get_history'));assert.ok(!request.tools.some(t=>t.name==='knowledge_search'||t.name==='python_execute'));
    const [toolName,input]=plan[Math.min(turn++,plan.length-1)];yield {type:'tool-call-delta',toolCallId:`resource-${turn}`,toolName,input};yield {type:'finish',reason:'tool-calls'};
  }}});
  const result=await run.run();assert.equal(result.status,'completed');assert.deepEqual(calls.map(c=>c.name),plan.slice(1,-1).map(step=>step[0]));
});

test('resource methodology is progressively scoped and never grants other Coding Agents data tools',()=>{
  const view=taskSkills({knowledgeExploration:{...profile,resourceIds:['financial_news']}});
  assert.ok(view.catalog.skills.some(c=>c.name==='financial-news-analysis'));
  assert.ok(view.catalog.skills.some(c=>c.name==='evidence-based-answering'));
  assert.ok(!view.catalog.skills.some(c=>c.name==='financial-market-analysis'||c.name==='opensearch-knowledge-exploration'));
  assert.equal(view.readBuiltin('builtin:financial-market-analysis'),null);
  assert.match(view.readBuiltin('builtin:financial-news-analysis').instructions,/publication time/);
  assert.equal(taskSkills().readBuiltin('builtin:financial-news-analysis'),null);
});

test('permitted resource subset never advertises ungranted resources',()=>{
  const c={...contract,authorization:{...contract.authorization,allowedTools:['news_search','report_submit']}};
  assert.deepEqual(knowledgeTools(c,{knowledge(){}},()=>{}).map(t=>t.name),['news_search']);
});
