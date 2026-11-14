import test from 'node:test';import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join} from 'node:path';
import {reviewProfessionalReports} from '../src/professional-report-review.mjs';
test('professional HTML requires companions while source-only delivery remains compatible',async()=>{
 await reviewProfessionalReports('.', [{path:'analysis.py',type:'SOURCE'}]);
 await assert.rejects(reviewProfessionalReports('.', [{path:'report.html',type:'REPORT'}]),/requires report_manifest/);
});
test('trusted delivery review detects wrong ranks despite model declaring quality passed',async()=>{
 const root=await mkdtemp(join(tmpdir(),'report-review-'));
 try{
 const files={'report_manifest.json':{schemaVersion:'professional-report.v1'},'quality_report.json':{status:'PASSED',errors:[],browserCheckedHtmlSha256:'html'},'evidence.json':{sources:[{id:'s',file:'source.json',sha256:'actual'}],metrics:[{id:'pe',value:[0,1],sourceIds:['s'],check:{op:'rank',inputs:[8,5],sourceInputs:[{sourceId:'s',path:[0]},{sourceId:'s',path:[1]}]}}]}};
 for(const [name,value]of Object.entries(files))await writeFile(join(root,name),JSON.stringify(value));
 await writeFile(join(root,'source.json'),JSON.stringify([8,5]));
 const artifacts=[...Object.keys(files).map(path=>({path})),{path:'report.html',sha256:'html'},{path:'source.json',sha256:'actual'}];
 await assert.rejects(reviewProfessionalReports(root,artifacts),/ranking mismatch/);
 files['evidence.json'].metrics[0].value=[1,0];await writeFile(join(root,'evidence.json'),JSON.stringify(files['evidence.json']));
 await reviewProfessionalReports(root,artifacts);
 const bound=files['evidence.json'].metrics[0].check.sourceInputs;
 delete files['evidence.json'].metrics[0].check.sourceInputs;await writeFile(join(root,'evidence.json'),JSON.stringify(files['evidence.json']));
 await assert.rejects(reviewProfessionalReports(root,artifacts),/not bound to source cells/);
 files['evidence.json'].metrics[0].check.sourceInputs=bound;await writeFile(join(root,'evidence.json'),JSON.stringify(files['evidence.json']));
 artifacts.at(-1).sha256='tampered';await assert.rejects(reviewProfessionalReports(root,artifacts),/hash missing/);
 }finally{await rm(root,{recursive:true,force:true});}
});
