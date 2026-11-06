import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { taskSkillPackages, imageSkillBytes, sha256 } from '../src/skill-distribution.js';
test('Microsoft original packages and every resource preserve publisher hashes and license',()=>{
  const packages=taskSkillPackages().filter(p=>p.card.publisher==='Microsoft');assert.equal(packages.length,4);
  for(const pkg of packages){
    assert.equal(pkg.card.provenance.commit,'3f5b461b2314e01c400564051e92469114c5b68f');
    assert.equal(pkg.card.provenance.license,'MIT');assert.equal(pkg.card.instructions,undefined);
    const source=imageSkillBytes(pkg);assert.deepEqual(source,readFileSync(pkg.filePath));
    assert.equal(sha256(source),pkg.card.provenance.sha256);
    for(const resource of pkg.resources)assert.equal(sha256(imageSkillBytes(pkg,resource.path)),resource.sha256);
  }
  const builder=packages.find(p=>p.card.name==='mcp-builder');
  assert(builder.resources.some(r=>r.path==='scripts/evaluation.py'));
  assert.match(imageSkillBytes(builder,'scripts/evaluation.py').toString(),/python|import/);
});
