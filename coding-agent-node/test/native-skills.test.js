import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { promisify } from 'node:util';
import { execFile } from 'node:child_process';
import { prepareNativeSkills } from '../src/native-skills.js';
import { taskSkillPackages, imageSkillBytes, sha256 } from '../src/skill-distribution.js';
import { createCodingRun } from '../src/runner.js';
import { sdkPrepareTurn } from '../src/sdk-adapter.js';

const context = () => ({ toolCallId: randomUUID(), signal: new AbortController().signal });
const task = (name = randomUUID()) => ({ schemaVersion:'runtime_os.coding.v3',taskId:name,
  scope:{tenantId:'native-tenant',userId:'owner',runId:name}, objective:{instruction:'Analyze the supplied synthetic fixture'},
  authorization:{allowedTools:['skills','read_files'],allowedDataRefs:[],allowedSideEffects:[],networkPolicy:'NONE'},
  outputContract:{resultFormat:'JSON_LINES'},budget:{maxModelCalls:8} });
const gateway = {reserveModel(){},settleModel(){},finish(){},executePython(){assert.fail('No execution grant');}};
const invoke = (runtime, name, input) => runtime.tools.find(t=>t.name===name).execute(input, context());
async function prepare(t=task(), g=gateway, options={}) {return prepareNativeSkills(t,g,{sessionId:'native-fixture',...options});}

test('native preparation retains one Agent entry and cannot activate a standalone host file reader',async()=>{
  const invalid=task();invalid.authorization.allowedTools=['read_files'];
  assert.throws(()=>createCodingRun(invalid,gateway),/native Skills lifecycle grant/);
  let models=0;const run=createCodingRun(task(),gateway,{nativeSdk:true,model:{async *stream(){models++;
    yield {type:'text-delta',text:'No methodology needed.'};yield {type:'finish',reason:'stop'};}}});
  const first=run.run();await assert.rejects(run.run(),/already started/);
  assert.equal((await first).status,'completed');assert.equal(models,1);
});

test('official package discovery and service load all image Skills; only native tools are exposed',async()=>{
  const events=[],r=await prepare(task(),gateway,{observe:e=>events.push(e)});
  try {
    assert.equal(r.records.length,15);assert.deepEqual(r.tools.map(t=>t.name).sort(),['read_files','skills']);
    const discovered=events.find(e=>e.type==='SKILLS_DISCOVERED');
    assert.equal(discovered.discovery,'loadAgentPluginPackages');assert.equal(discovered.loader,'createUserInstructionConfigService');
    assert.equal(events.some(e=>e.type==='SKILLS_LOADED'),false);
    assert.match(r.tools.find(t=>t.name==='read_files').description,/Skill files and references/);
    assert.match(r.tools.find(t=>t.name==='read_files').description,/\/data\/input.*outside this tool/);
    assert.match(r.tools.find(t=>t.name==='skills').description,/runtime-image:python-data-analysis/);
    assert.match(r.tools.find(t=>t.name==='skills').inputSchema.properties.skill.description,/"description":"Analyze delivered Python datasets/);
    assert.doesNotMatch(JSON.stringify(r.tools),/Check join key cardinality/);
    for(const pkg of taskSkillPackages()) {
      const record=r.records.find(x=>x.id==='runtime-image:'+pkg.card.name);
      assert.deepEqual(await readFile(record.filePath),imageSkillBytes(pkg));
      for(const file of pkg.resources)assert.deepEqual(await readFile(join(dirname(record.filePath),file.path)),imageSkillBytes(pkg,file.path));
    }
  }finally{await r.dispose();}
});

test('official skills invocation supplies its root and native read_files loads the requested reference',async()=>{
  const events=[],r=await prepare(task(),gateway,{observe:e=>events.push(e)});
  try{
    const body=await invoke(r,'skills',{skill:'runtime-image:python-data-analysis'});
    assert.match(JSON.stringify(body),/Check join key cardinality/);assert.match(JSON.stringify(body),/skill-root/);
    const record=r.records.find(x=>x.item.name==='python-data-analysis');
    const reference=await invoke(r,'read_files',{files:[{path:join(dirname(record.filePath),'references','data-methods.md')}]});
    assert.match(JSON.stringify(reference),/many_to_one/);
    assert.equal(events.filter(e=>e.type==='SKILLS_LOADED').length,2);
    assert.equal(events.at(-1).skillReference,true);
    const path=join(dirname(record.filePath),'references','data-methods.md');
    await invoke(r,'read_files',{files:[{path,start_line:1,end_line:2}]});assert.equal(events.at(-1).duplicateRead,false);
    await invoke(r,'read_files',{files:[{path,start_line:3,end_line:4}]});assert.equal(events.at(-1).duplicateRead,false);
    await invoke(r,'read_files',{files:[{path,start_line:1,end_line:2}]});assert.equal(events.at(-1).duplicateRead,true);
  }finally{await r.dispose();}
});

test('several relevant native Skills can cooperate without loading unrelated bodies',async()=>{
  const events=[],r=await prepare(task(),gateway,{observe:e=>events.push(e)});
  try{
    for(const skill of ['python-data-analysis','plotting','runtime-report-delivery'])
      await invoke(r,'skills',{skill:'runtime-image:'+skill});
    assert.deepEqual(events.filter(e=>e.type==='SKILLS_LOADED').map(e=>e.skillId),
      ['builtin:python-data-analysis','builtin:cline:plotting','builtin:runtime-report-delivery']);
    assert(!events.some(e=>e.skillId==='builtin:microsoft:azure-kusto'));
  }finally{await r.dispose();}
});

function dynamicFixture(t=task()) {
  const instructions='---\nname: private-method\ndescription: Private fixture methodology.\n---\nPRIVATE_NATIVE_BODY\nRead references/check.md when needed.\n';
  const reference='PRIVATE_REFERENCE\n';let revoked=false;const calls=[];
  t.runtimeContext={skills:{skills:[{id:'private-id',name:'private-method',description:'Private fixture methodology.',version:'frozen-1',nativeOriginal:true}]}};
  const g={...gateway,readSkill(contract,id,path){calls.push([contract.scope.tenantId,id,path]);if(revoked)throw Error('Skill revoked');
    return path?{skillId:id,version:'frozen-1',content:reference,encoding:'utf-8',sha256:sha256(reference)}
      :{skillId:id,version:'frozen-1',nativeOriginal:true,instructions,contentSha256:sha256(instructions),resources:[{path:'references/check.md',sha256:sha256(reference)}]};}};
  return {t,g,calls,instructions,reference,revoke(){revoked=true;}};
}

test('legacy methodology distribution preserves its body and exposes SDK-discovered purpose metadata',async()=>{
  const t=task(),body='LEGACY_PRIVATE_BODY\n保留原方法。\n';
  t.runtimeContext={skills:{skills:[{id:'legacy',name:'财务方法',description:'用于财务计算的领域方法',version:'1'}]}};
  const r=await prepare(t,{...gateway,readSkill(){return{skillId:'legacy',version:'1',instructions:body,contentSha256:sha256(body),resources:[]};}});
  try{
    const record=r.records.find(x=>x.id.startsWith('runtime-agent-'));
    assert((await readFile(record.filePath,'utf8')).endsWith(body));
    const description=r.tools.find(t=>t.name==='skills').inputSchema.properties.skill.description;
    assert.match(description,/用于财务计算的领域方法/);assert.doesNotMatch(description,/LEGACY_PRIVATE_BODY/);
    assert.match(JSON.stringify(await invoke(r,'skills',{skill:record.id})),/LEGACY_PRIVATE_BODY/);
  }finally{await r.dispose();}
});

test('dynamic original package bytes and resources remain exact; distribution is not model loading',async()=>{
  const f=dynamicFixture(),events=[],r=await prepare(f.t,f.g,{observe:e=>events.push(e)});
  try{
    const record=r.records.find(x=>x.item.name==='private-method');
    assert.equal(await readFile(record.filePath,'utf8'),f.instructions);
    assert.equal(await readFile(join(dirname(record.filePath),'references/check.md'),'utf8'),f.reference);
    assert.equal(events.filter(e=>e.type==='SKILLS_LOADED').length,0);
    assert.match(JSON.stringify(await invoke(r,'skills',{skill:record.id})),/PRIVATE_NATIVE_BODY/);
    assert.equal(f.calls.length,3); // Two delivery reads, then a live authorization check.
  }finally{await r.dispose();}
});

test('native resource access remains task isolated and cannot read the host or another tenant workspace',async()=>{
  const fa=dynamicFixture(),fb=dynamicFixture();fb.t.scope.tenantId='another-tenant';
  const a=await prepare(fa.t,fa.g),b=await prepare(fb.t,fb.g);
  try{
    assert.notEqual(a.root,b.root);
    const foreign=b.records.find(x=>x.item.name==='private-method').filePath;
    for(const path of [foreign,join(a.root,'..','secret.txt'),process.execPath,'/data/input/private/original-0.csv']){
      const result=await invoke(a,'read_files',{files:[{path}]});
      assert.match(JSON.stringify(result),/outside the authorized/);assert.doesNotMatch(JSON.stringify(result),/PRIVATE_NATIVE_BODY/);
    }
  }finally{await a.dispose();await b.dispose();}
});

test('revoked dynamic authorization blocks cached native Skill instructions and reference reads',async()=>{
  const f=dynamicFixture(),r=await prepare(f.t,f.g);
  try{
    const record=r.records.find(x=>x.item.name==='private-method');f.revoke();
    await assert.rejects(invoke(r,'skills',{skill:record.id}),/revoked/);
    const result=await invoke(r,'read_files',{files:[{path:join(dirname(record.filePath),'references/check.md')}]});
    assert.match(JSON.stringify(result),/revoked/);assert.doesNotMatch(JSON.stringify(result),/PRIVATE_REFERENCE/);
  }finally{await r.dispose();}
});

test('staged content tampering is rejected before the native file executor discloses it',async()=>{
  const r=await prepare();
  try{
    const record=r.records.find(x=>x.item.name==='python-data-analysis'),path=join(dirname(record.filePath),'references/data-methods.md');
    await writeFile(path,'TAMPERED_SECRET');
    const result=await invoke(r,'read_files',{files:[{path}]});
    assert.match(JSON.stringify(result),/integrity mismatch/);assert.doesNotMatch(JSON.stringify(result),/TAMPERED_SECRET/);
  }finally{await r.dispose();}
});

test('SDK discovery resumes at stable task paths after service disposal and redistribution',async()=>{
  const t=task(),first=await prepare(t);const before=first.records.map(r=>[r.id,r.filePath]);await first.dispose();
  const second=await prepare(t);
  try{assert.deepEqual(second.records.map(r=>[r.id,r.filePath]),before);
    assert.match(JSON.stringify(await invoke(second,'skills',{skill:'runtime-image:runtime-report-delivery'})),/evidence-based/);
  }finally{await second.dispose();}
});

test('new Node processes independently rediscover the same authorized original packages',async()=>{
  const t=task(),url=new URL('../src/native-skills.js',import.meta.url).href;
  const program=`import {prepareNativeSkills} from ${JSON.stringify(url)};const r=await prepareNativeSkills(${JSON.stringify(t)},{},{sessionId:'process-restart'});console.log(JSON.stringify(r.records.map(x=>[x.id,x.filePath])));await r.dispose();`;
  const run=promisify(execFile);const one=await run(process.execPath,['--input-type=module','-e',program]);
  const two=await run(process.execPath,['--input-type=module','-e',program]);
  assert.deepEqual(JSON.parse(two.stdout),JSON.parse(one.stdout));assert.equal(JSON.parse(one.stdout).length,15);
});

test('native Skill can be loaded again after real installed SDK compaction removes its prior output',async()=>{
  const r=await prepare();
  try{
    const tool=r.tools.find(t=>t.name==='skills');const original=await tool.execute({skill:'runtime-image:python-data-analysis'},context());
    const prepareTurn=sdkPrepareTurn('native-compression');
    const messages=[{role:'user',content:[{type:'text',text:'Analyze the authorized data.'}]},
      {role:'assistant',content:[{type:'tool-call',toolCallId:'method',toolName:'skills',input:{skill:'runtime-image:python-data-analysis'}}]},
      {role:'tool',content:[{type:'tool-result',toolCallId:'method',toolName:'skills',output:original}]},
    ];
    for(let i=0;i<12;i++)messages.push(
      {role:'assistant',content:[{type:'tool-call',toolCallId:'later-'+i,toolName:'python_execute',input:{source:'x'.repeat(16000)}}]},
      {role:'tool',content:[{type:'tool-result',toolCallId:'later-'+i,toolName:'python_execute',output:{status:'FAILED',diagnosis:'repair '+i},isError:true}]});
    const result=await prepareTurn({agentId:'native-compression',iteration:13,messages,systemPrompt:'Authorized fixture',tools:[],model:{info:{maxInputTokens:16384,contextWindow:20000}},signal:context().signal});
    assert(!JSON.stringify(result.messages).includes('Check join key cardinality'));
    assert.match(JSON.stringify(await tool.execute({skill:'runtime-image:python-data-analysis'},context())),/Check join key cardinality/);
  }finally{await r.dispose();}
});

test('one real Cline session may skip all Skills and directly propose simple CSV analysis code',async()=>{
  const t=task();t.authorization={...t.authorization,allowedTools:['skills','read_files','python_execute'],allowedDataRefs:['c'+'a'.repeat(63)],allowedSideEffects:['CODE_EXECUTION']};
  t.outputContract.requiredFiles=['rows.jsonl'];let calls=0,executions=0;
  const run=createCodingRun(t,{...gateway,
    preparePython(){return {scriptVersion:1,fileName:'analysis_v1.py',scriptPath:'/workspace/scripts/analysis_v1.py',
      workingDirectory:'tasks/'+['a'.repeat(16),'b'.repeat(16),'c'.repeat(16),'d'.repeat(24)].join('/')};},
    executePython(){executions++;return{status:'SUCCEEDED',executorStopped:true,resultDatasetRef:'result',validationRecordRef:'validation',evidenceBundleRef:'bundle'};}},
    {nativeSdk:true,model:{async *stream(request){calls++;assert(!request.tools.some(t=>t.name==='skill_read'));
      assert(request.tools.some(t=>t.name==='skills'));assert.doesNotMatch(request.systemPrompt,/Read relevant Skills before|skill_read/);
      assert.doesNotMatch(JSON.stringify(request.messages),/Check join key cardinality/);
      if(calls===1){yield{type:'tool-call-delta',toolName:'python_execute',toolCallId:'code',input:{source:'import csv\nprint(sum([10,14,22]))'}};yield{type:'finish',reason:'tool-calls'};}
      else{yield{type:'text-delta',text:'Calculated.'};yield{type:'finish',reason:'stop'};}}}});
  assert.equal((await run.run()).status,'completed');assert.equal(calls,2);
  assert.equal(run.events.filter(e=>e.type==='CODE_READY').length,1);
  assert.equal(executions,1);
  assert.equal(run.events.filter(e=>e.type==='SKILLS_LOADED').length,0);
});
