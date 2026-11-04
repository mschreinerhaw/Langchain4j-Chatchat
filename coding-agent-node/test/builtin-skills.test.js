import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { createSkillsConfigDefinition, UnifiedConfigFileWatcher } from '@cline/core';
import { taskSkills, bundledSkillFiles } from '../src/skills.js';
import { createCodingRun } from '../src/runner.js';

test('pinned Cline discovers all original packaged Skills without network access', async () => {
  const folders = bundledSkillFiles().map(item => dirname(item.filePath));
  const definition = createSkillsConfigDefinition({ directories: [resolve(folders[0], '..')], includePluginSkills: false });
  const watcher = new UnifiedConfigFileWatcher([definition]);
  try {
    await watcher.start();
    const snapshot = [...watcher.getSnapshot('skill').values()];
    assert.equal(snapshot.length, 10);
    assert.deepEqual(snapshot.map(item => item.item.name).sort(), ['azure-ai-ml-py','azure-kusto','evidence-based-answering','financial-market-analysis','financial-news-analysis','mcp-builder','opensearch-knowledge-exploration','python-code-development','python-data-analysis','skill-creator']);
  } finally { watcher.stop(); }
});

test('legacy Agent disable policy cannot remove platform Skills; collisions and resources remain scoped', () => {
  const before = bundledSkillFiles().map(item => readFileSync(item.filePath, 'utf8'));
  const dynamic = { id: 'domain-a', name: 'Client analysis', version: '1' };
  const a = taskSkills({ skills: { skills: [dynamic,
    { id:'collision', name:'python-data-analysis', version:'999' }] } });
  const b = taskSkills({ builtinSkillPolicy: { disabledSkillIds: ['builtin:python-data-analysis'] } });
  dynamic.version = '2';
  assert.equal(a.catalog.skills.find(card => card.id === 'domain-a').version, '1');
  assert.equal(a.catalog.conflicts[0].reason, 'BUILTIN_NAME_RESERVED');
  assert.equal(a.catalog.skills.length, 7);
  assert.equal(b.catalog.skills.length, 6);
  assert.ok(b.readBuiltin('builtin:python-data-analysis'));
  assert.equal(taskSkills({builtinSkillPolicy:{disabledSkillIds:'legacy-malformed'}}).catalog.skills.length,6);
  for(const card of b.catalog.skills){assert.equal(card.builtin,true);assert.equal(card.enabled,true);assert.ok(card.displayName&&card.descriptionZh&&card.source&&card.sourceVersion);}
  assert.equal(taskSkills().catalog.skills.length, 6);
  const instructions = a.readBuiltin('builtin:python-data-analysis');
  assert.match(instructions.instructions, /Python Sandbox/);
  assert.match(a.readBuiltin('builtin:python-data-analysis', instructions.resources[0].path).content, /Parquet/);
  for (const path of ['../catalog.json','scripts/run.py','/etc/passwd','references/../../secret'])
    assert.throws(() => a.readBuiltin('builtin:python-data-analysis', path), /outside the task manifest/);
  assert.deepEqual(bundledSkillFiles().map(item => readFileSync(item.filePath, 'utf8')), before);
});

test('real Cline Agent loop sees metadata then autonomously loads builtin instructions and references', async () => {
  const contract = { schemaVersion:'runtime_os.coding.v3', taskId:'builtin-task',
    scope:{tenantId:'tenant',userId:'user',runId:'run'}, objective:{instruction:'Analyze the delivered CSV and Parquet files'},
    authorization:{allowedTools:['skill_read','python_execute'],allowedSideEffects:['CODE_EXECUTION'],
      allowedDataRefs:['c'+'a'.repeat(63)],networkPolicy:'NONE'},
    outputContract:{resultFormat:'JSON_LINES',requiredFiles:['rows.jsonl']},budget:{maxModelCalls:5} };
  let turns=0;
  const gateway={async reserveModel(){},async settleModel(){},async finish(){},
    async readSkill(){assert.fail('Builtin content must never use the Java/dynamic callback');},
    async preparePython(){return {fileName:'analysis_20261030T000000000Z_abcdef012345_v1.py',scriptVersion:1,
      scriptPath:'/workspace/scripts/analysis.py',workingDirectory:'tasks/'+['a'.repeat(16),'b'.repeat(16),'c'.repeat(16),'d'.repeat(24)].join('/')};},
    async executePython(){return {status:'SUCCEEDED',executorStopped:true,resultDatasetRef:'result',validationRecordRef:'validation',evidenceBundleRef:'bundle'};}};
  const model={async *stream(request){
    turns++;
    const messages=JSON.stringify(request.messages);
    if(turns===1){assert.match(messages,/python-data-analysis/);assert.doesNotMatch(messages,/Check join key cardinality/);
      yield {type:'tool-call-delta',toolCallId:'skill',toolName:'skill_read',input:{skillId:'builtin:python-data-analysis'}};}
    else if(turns===2){assert.match(messages,/Check join key cardinality/);
      yield {type:'tool-call-delta',toolCallId:'ref',toolName:'skill_read',input:{skillId:'builtin:python-data-analysis',path:'references/data-methods.md'}};}
    else if(turns===3){assert.match(messages,/many_to_one/);
      yield {type:'tool-call-delta',toolCallId:'execute',toolName:'python_execute',input:{fileName:'main.py',source:'print(1)'}};}
    else yield {type:'text-delta',text:'Done'};
    yield {type:'finish',reason:'stop'};
  }};
  const run=createCodingRun(contract,gateway,{model});
  assert.equal((await run.run()).status,'completed');
  assert.equal(turns,4);
  assert.equal(run.events.filter(event=>event.type==='SKILLS_LOADED').length,2);
});

test('same-name dynamic versions retain the first Agent binding and report the excluded version', () => {
  const view = taskSkills({ skills: { skills: [
    { id:'first',name:'Client analysis',version:'1' },
    { id:'second',name:' client ANALYSIS ',version:'2' },
  ] } }).catalog;
  assert.deepEqual(view.skills.filter(card => card.origin === 'AGENT_DYNAMIC').map(card => card.id), ['first']);
  assert.deepEqual(view.conflicts, [{ id:'second',name:' client ANALYSIS ',version:'2',
    reason:'DYNAMIC_NAME_DUPLICATE',retainedSkillId:'first',retainedVersion:'1' }]);
  assert.throws(() => taskSkills({ skills: { skills: [
    { id:'same',name:'Analysis',version:'1' }, { id:'same',name:'Analysis',version:'2' },
  ] } }), /Duplicate task Skill ID/);
});

test('an unrelated real Cline loop receives only discovery metadata and loads no Skill body', async () => {
  let calls=0;
  const model={async *stream(request){
    calls++;
    const messages=JSON.stringify(request.messages);
    assert.match(messages,/python-data-analysis/);
    assert.doesNotMatch(messages,/Check join key cardinality/);
    assert.doesNotMatch(messages,/Catch exceptions where recovery/);
    yield {type:'text-delta',text:'Version control records changes to files.'};
    yield {type:'finish',reason:'stop'};
  }};
  const contract={schemaVersion:'runtime_os.coding.v3',taskId:'unrelated-task',
    scope:{tenantId:'tenant',userId:'user',runId:'unrelated'},
    objective:{instruction:'Explain version control in one sentence. No programming or data analysis.'},
    authorization:{allowedTools:['skill_read'],allowedSideEffects:[],allowedDataRefs:[],networkPolicy:'NONE'},
    outputContract:{resultFormat:'JSON_LINES',requiredFiles:[]},budget:{maxModelCalls:2}};
  const gateway={async reserveModel(){},async settleModel(){},async finish(){},async readSkill(){assert.fail('No disclosure requested');},async executePython(){assert.fail('No execution authorized');}};
  const run=createCodingRun(contract,gateway,{model});
  assert.equal((await run.run()).status,'completed');
  assert.equal(calls,1);
  assert.equal(run.events.filter(event=>event.type==='SKILLS_LOADED').length,0);
});
