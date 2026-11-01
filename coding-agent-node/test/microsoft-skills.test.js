import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {taskSkills,validateNativeDisclosure,bundledSkillFiles} from '../src/skills.js';

test('Microsoft originals remain separate assets; metadata does not contain instructions or scripts',()=>{
 const view=taskSkills(),cards=view.catalog.skills.filter(c=>c.publisher==='Microsoft');
 assert.equal(cards.length,4);
 for(const card of cards){
  assert.equal(card.provenance.commit,'3f5b461b2314e01c400564051e92469114c5b68f');
  assert.equal(card.provenance.license,'MIT');
  assert.equal(card.contentDelivery,'ON_DEMAND');
  assert.equal(card.resources,undefined);assert.equal(card.instructions,undefined);
  const disclosed=view.readBuiltin(card.id);
  assert.equal(disclosed.nativeOriginal,true);
  assert.equal(disclosed.instructions,readFileSync(bundledSkillFiles().find(c=>c.id===card.id).filePath,'utf8'));
  assert.equal(createHash('sha256').update(disclosed.instructions).digest('hex'),card.provenance.sha256);
  assert.equal(validateNativeDisclosure(disclosed,card),disclosed);
  assert.throws(()=>validateNativeDisclosure({...disclosed,instructions:disclosed.instructions+'\n'},card),/hash mismatch/);
  assert.equal(disclosed.authority,'METHODOLOGY_ONLY');
  for(const file of disclosed.resources){
   const resource=view.readBuiltin(card.id,file.path);
   assert.equal(createHash('sha256').update(resource.content,resource.encoding==='base64'?'base64':'utf8').digest('hex'),file.sha256);
  }
 }
 const builder=view.readBuiltin('builtin:microsoft:mcp-builder');
 assert.ok(builder.resources.some(r=>r.path==='reference/microsoft_mcp_patterns.md'));
 assert.ok(builder.resources.some(r=>r.path==='scripts/evaluation.py'));
 assert.match(view.readBuiltin('builtin:microsoft:mcp-builder','scripts/evaluation.py').content,/python|import/);
 for(const path of ['../catalog.json','scripts/../../src/runner.js','scripts%2fevaluation.py','/etc/passwd','scripts/evaluation.py?x','scripts\\evaluation.py'])
  assert.throws(()=>view.readBuiltin('builtin:microsoft:mcp-builder',path),/outside the task manifest/);
});

test('legacy disable cannot override platform-owned native asset availability',()=>{
 const id='builtin:microsoft:azure-kusto';
 const isolated=taskSkills({builtinSkillPolicy:{disabledSkillIds:[id]}});
 assert.ok(isolated.catalog.skills.some(c=>c.id===id));assert.ok(isolated.readBuiltin(id));
 assert.ok(taskSkills().readBuiltin(id).instructions.startsWith('---\n'));
});
