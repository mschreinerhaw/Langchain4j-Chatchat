import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {readDeliverySelection} from '../src/delivery-manifest.mjs';

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
