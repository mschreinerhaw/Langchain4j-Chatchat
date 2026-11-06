import test from 'node:test';
import assert from 'node:assert/strict';
import { taskSkillPackages, imageSkillBytes } from '../src/skill-distribution.js';
test('legacy disable hints cannot remove authorized image packages or mutate metadata',()=>{
  const a=taskSkillPackages({builtinSkillPolicy:{disabledSkillIds:['builtin:python-data-analysis']}});
  assert.equal(a.length,15);assert(a.some(p=>p.card.id==='builtin:python-data-analysis'));
  a[0].card.name='mutated';assert.equal(taskSkillPackages()[0].card.name,'python-data-analysis');
});
test('delivery rejects duplicate source identities; SDK namespaces handle names',()=>{
  const dynamic={id:'dynamic',name:'python-data-analysis',version:'1'};
  assert.equal(taskSkillPackages({skills:{skills:[dynamic]}}).length,16);
  assert.throws(()=>taskSkillPackages({skills:{skills:[dynamic,dynamic]}}),/Duplicate authorized/);
});
test('image delivery rejects traversal and undeclared resource paths',()=>{
  const pkg=taskSkillPackages().find(p=>p.card.name==='python-data-analysis');
  assert.match(imageSkillBytes(pkg).toString(),/Python Sandbox/);
  for(const path of ['../catalog.json','scripts/run.py','/etc/passwd','references/../../secret','references%2fdata-methods.md'])
    assert.throws(()=>imageSkillBytes(pkg,path),/outside the delivery manifest/);
});
