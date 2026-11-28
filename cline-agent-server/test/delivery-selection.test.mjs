import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,rename,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {buildDeliveryManifest,discoverAnalysisDelivery,discoverContractDelivery,readDeliverySelection} from '../src/delivery-manifest.mjs';
import {isNativeAnalysis,signContract,verifyContract} from '../src/task-contract.mjs';

test('the Agent declares dynamic deliverables; Markdown repair notes are auxiliary',async()=>{
 const root=await mkdtemp(join(tmpdir(),'cline-selection-'));
 try{
  await writeFile(join(root,'delivery_manifest.json'),JSON.stringify({schemaVersion:'coding_node_delivery_selection.v1',artifacts:[{path:'account/report.md',type:'REPORT'},{path:'FIX_SUMMARY.md',type:'AUXILIARY'},{path:'account/reconcile.py',type:'SOURCE'}]}));
  const selection=await readDeliverySelection(root);
  assert.deepEqual(selection.filter(a=>a.type==='REPORT'),[{path:'account/report.md',type:'REPORT'}]);
  // Canonical manifests can carry the next selection, but their claimed hash,
  // identity or receipt fields confer no authority to this reader.
  await writeFile(join(root,'delivery_manifest.json'),JSON.stringify({schemaVersion:'coding_node_delivery.v1',taskId:'untrusted',nativeReceipts:['untrusted'],artifacts:[{path:'account/report.md',type:'REPORT',sha256:'untrusted',size:999}]}));
  assert.deepEqual(await readDeliverySelection(root),[{path:'account/report.md',type:'REPORT'}]);
 }finally{await rm(root,{recursive:true,force:true});}
});
test('unsafe, absent, duplicate and oversized delivery declarations fail closed',async()=>{
 const root=await mkdtemp(join(tmpdir(),'cline-selection-'));
 try{
  await assert.rejects(readDeliverySelection(root),e=>e.code==='ENOENT');
  for(const artifacts of [[{path:'../tenant/report.md',type:'REPORT'}],[{path:'input/data.parquet',type:'RESULT'}],[{path:'delivery_manifest.json',type:'REPORT'}],[{path:'report.md',type:'REPORT'},{path:'report.md',type:'AUXILIARY'}]]){
   await writeFile(join(root,'delivery_manifest.json'),JSON.stringify({schemaVersion:'coding_node_delivery_selection.v1',artifacts}));
   await assert.rejects(readDeliverySelection(root));
  }
  await writeFile(join(root,'delivery_manifest.json'),'x'.repeat(512*1024+1));
  await assert.rejects(readDeliverySelection(root),/512 KiB/);
 }finally{await rm(root,{recursive:true,force:true});}
});
test('signed analysis layouts inventory independently named reports and JSON without trusting a selection file',async()=>{
 const root=await mkdtemp(join(tmpdir(),'cline-analysis-delivery-'));
 try{
  await mkdir(join(root,'output/report/details'),{recursive:true});
  await mkdir(join(root,'output/result'),{recursive:true});
  await writeFile(join(root,'delivery_manifest.json'),'invalid model declaration');
  await writeFile(join(root,'output/draft.py'),'diagnostic only');
  await writeFile(join(root,'output/report/details/measurements.md'),'# Real report');
  await writeFile(join(root,'output/result/observations.json'),'{"observations":[]}');
  let files=await discoverAnalysisDelivery(root,'plain');
  assert.deepEqual(files,[
   {path:'output/report/details/measurements.md',type:'REPORT'},
   {path:'output/result/observations.json',type:'RESULT'}
  ]);
  const binding={taskId:'task',runId:'run',tenantId:'tenant',inputSnapshotVersion:'snapshot',deliveryVersion:'v1'};
  const session={id:'session',epoch:'epoch',status:'running',journal:{events:[]}};
  await assert.rejects(buildDeliveryManifest({workspace:root,binding,session,files}),/Native turn has not completed/);
  session.status='turn_completed';
  await assert.rejects(buildDeliveryManifest({workspace:root,binding,session,files}),/Missing native completion receipt/);
  session.journal.events.push({eventId:1,eventType:'turn.completed',epoch:session.epoch});
  const preview=await buildDeliveryManifest({workspace:root,binding,session,files});
  assert.equal(preview.acceptanceStatus,'PENDING_RUNTIME_VALIDATION');
  assert.equal(preview.artifacts.length,2);
  assert.ok(preview.artifacts.every(x=>x.sha256.length===64));
  await rename(join(root,'output/report/details/measurements.md'),join(root,'output/report/another-name.html'));
  await writeFile(join(root,'output/report/site.css'),'main { display: block }');
  await assert.rejects(discoverAnalysisDelivery(root,'plain'),/Unsupported file format/);
  files=await discoverAnalysisDelivery(root,'visual');
  assert.deepEqual(files.map(x=>x.type),['REPORT','AUXILIARY','RESULT']);
  await writeFile(join(root,'output/report/duplicate.md'),'# Duplicate entry');
  await assert.rejects(discoverAnalysisDelivery(root,'visual'),/exactly one REPORT/);
 }finally{await rm(root,{recursive:true,force:true});}
});

test('a signed delivery catalog permits alternate formats, extra artifact types and arbitrary file names',async()=>{
 const root=await mkdtemp(join(tmpdir(),'cline-contract-delivery-'));
 const definitions=[
  {directory:'output/summary',type:'REPORT',formats:['html'],minCount:1,maxCount:1,htmlCompanions:{CHART:['svg']}},
  {directory:'output/exchange',type:'RESULT',formats:['json'],minCount:1,maxCount:1,htmlCompanions:{}},
  {directory:'output/checks',type:'TEST',formats:['txt'],minCount:1,maxCount:2,htmlCompanions:{}}
 ];
 try{
  for(const {directory} of definitions)await mkdir(join(root,directory),{recursive:true});
  await writeFile(join(root,'output/summary/resume.HTML'),'<h1>Result</h1>');
  await writeFile(join(root,'output/summary/chart.svg'),'<svg/>');
  await writeFile(join(root,'output/exchange/sample.json'),'[{"reading":1}]');
  await writeFile(join(root,'output/checks/validation.txt'),'verified');
  await writeFile(join(root,'delivery_manifest.json'),'untrusted and intentionally invalid');
  assert.deepEqual(await discoverContractDelivery(root,definitions),[
   {path:'output/checks/validation.txt',type:'TEST'},
   {path:'output/exchange/sample.json',type:'RESULT'},
   {path:'output/summary/chart.svg',type:'CHART'},
   {path:'output/summary/resume.HTML',type:'REPORT'}
  ]);
  const alternative=definitions.map(r=>r.type==='REPORT'?{...r,formats:['md'],htmlCompanions:{}}:r);
  await assert.rejects(discoverContractDelivery(root,alternative),/Unsupported file format/);
  await assert.rejects(discoverContractDelivery(root,[{...definitions[0],directory:'output/../input'}]),/Invalid host delivery rules/);
  await rm(join(root,'output/checks/validation.txt'));
  await assert.rejects(discoverContractDelivery(root,definitions),/Deliver 1 to 2 TEST files/);
 }finally{await rm(root,{recursive:true,force:true});}
});

test('signed analysis kind controls admission even without a metadata path',()=>{
 const key='test signing key with enough material';
 const grant={version:2,tenantId:'tenant',userId:'user',taskId:'task',sessionId:'session',
  workspaceRef:'project',modelProfile:'approved-provider',runtimeProfile:'native-python-silo-v1',
  expiresAt:Date.now()+60000,toolPermissions:[],dataPermissions:[],networkPolicy:'broker-only',
  budgetPolicy:{status:'DEFERRED_STAGE_2_REVIEW'},s3:{},
  deliveryBinding:{tenantId:'tenant',taskId:'task',runId:'run',inputSnapshotVersion:'snapshot'}};
 const deliveryRules=[{directory:'output/exchange',type:'RESULT',formats:['json'],minCount:1,maxCount:1,htmlCompanions:{}}];
 const analysis={...grant,nativeTaskKind:'DATA_ANALYSIS',deliveryRules,analysisModelBudget:true};
 assert.equal(isNativeAnalysis(verifyContract(signContract(analysis,key),key)),true);
 const metadata={analysisMetadataPath:'input/data/0/data-supply-manifest.json'};
 const generic={...grant,...metadata,nativeTaskKind:'GENERIC'};
 assert.equal(isNativeAnalysis(verifyContract(signContract(generic,key),key)),false);
 assert.throws(()=>verifyContract(signContract({...generic,analysisModelBudget:true},key),key),/Invalid or expired/);
 assert.throws(()=>verifyContract(signContract({...analysis,deliveryRules:[]},key),key),/Invalid or expired/);
 assert.throws(()=>verifyContract(signContract({...analysis,nativeTaskKind:'GENERIC'},key),key),/Invalid or expired/);
 const legacy={...grant,...metadata,analysisDelivery:'visual',analysisModelBudget:true};
 assert.equal(isNativeAnalysis(verifyContract(signContract(legacy,key),key)),true);
});
