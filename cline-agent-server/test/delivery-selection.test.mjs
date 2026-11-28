import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,rename,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {buildDeliveryManifest,discoverAnalysisDelivery,readDeliverySelection} from '../src/delivery-manifest.mjs';

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
  await assert.rejects(discoverAnalysisDelivery(root,'visual'),/exactly one report/);
 }finally{await rm(root,{recursive:true,force:true});}
});
