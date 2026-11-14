import test from 'node:test';
import assert from 'node:assert/strict';
import {taskSkillPackages,imageSkillBytes} from '../src/skill-distribution.js';
test('report rollout uses node configuration and permits task scoped rollback',()=>{
 const old=process.env.CLINE_PROFESSIONAL_REPORTS;
 try{
  delete process.env.CLINE_PROFESSIONAL_REPORTS;
  assert.equal(taskSkillPackages().filter(p=>p.card.professionalReport).length,0);
  assert.equal(taskSkillPackages({professionalReports:{enabled:true}}).filter(p=>p.card.professionalReport).length,4);
  process.env.CLINE_PROFESSIONAL_REPORTS='true';
  assert.equal(taskSkillPackages().filter(p=>p.card.professionalReport).length,4);
  assert.equal(taskSkillPackages({professionalReports:{enabled:false}}).filter(p=>p.card.professionalReport).length,0);
  for(const pkg of taskSkillPackages()){
   assert(imageSkillBytes(pkg).length);
   for(const resource of pkg.resources)assert(imageSkillBytes(pkg,resource.path).length);
  }
 }finally{if(old===undefined)delete process.env.CLINE_PROFESSIONAL_REPORTS;else process.env.CLINE_PROFESSIONAL_REPORTS=old;}
});
