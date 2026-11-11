import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,symlink,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {buildDeliveryManifest} from '../src/delivery-manifest.mjs';
const binding={taskId:'task-1',runId:'run-2',tenantId:'tenant-3',inputSnapshotVersion:'snapshot-4',deliveryVersion:'delivery-5'};
const session={id:'session',epoch:'epoch',status:'turn_completed',journal:{events:[{eventId:'1',epoch:'epoch',eventType:'tool.completed'},{eventId:'2',epoch:'epoch',eventType:'turn.completed'}]}};
test('dynamic business project paths and native receipts; no fixed analysis.py',async()=>{
 const root=await mkdtemp(join(tmpdir(),'delivery-'));
 try{await mkdir(join(root,'account'));await writeFile(join(root,'account','profit.py'),'print(42)');
 const result=await buildDeliveryManifest({workspace:root,binding,session,files:[{path:'account/profit.py',type:'SOURCE'}]});
 assert.equal(result.artifacts[0].size,9);assert.equal(result.artifacts[0].sha256.length,64);assert.equal(result.nativeReceipts.length,2);assert.equal(result.acceptanceStatus,'PENDING_RUNTIME_VALIDATION');
 }finally{await rm(root,{recursive:true,force:true});}
});
test('reject cancelled sessions, fabricated completion, traversal and input publication',async()=>{
 for(const status of ['cancelled','running','recoverable','failed'])await assert.rejects(buildDeliveryManifest({workspace:'.',binding,session:{...session,status},files:[]}),/not completed/);
 for(const path of ['../x','/tmp/x','input/source.parquet','logs/x','x/../a','x\\a'])await assert.rejects(buildDeliveryManifest({workspace:'.',binding,session,files:[{path,type:'SOURCE'}]}));
});
test('reject symlink artifacts even when the linked file exists',async t=>{
 const root=await mkdtemp(join(tmpdir(),'delivery-'));try{
 await writeFile(join(root,'real.py'),'ok');
 try{await symlink(join(root,'real.py'),join(root,'link.py'));}catch(e){if(e.code==='EPERM'){t.skip('Windows symlink privilege unavailable');return;}throw e;}
 await assert.rejects(buildDeliveryManifest({workspace:root,binding,session,files:[{path:'link.py',type:'SOURCE'}]}),/Symlink/);
 }finally{await rm(root,{recursive:true,force:true});}
});
