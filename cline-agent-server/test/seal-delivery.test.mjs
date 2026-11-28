import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { archiveNativeJournal, sealDelivery } from '../src/seal-delivery.mjs';

for(const failure of ['nativeSeal','nativeDelivery','workerStop','verify','archive','persistManifest']){
 test('seal recovery from '+failure+' keeps the approved version and confirmed progress',async()=>{
  let durable={workerState:'COMPLETED'},fail=true;
  let record=structuredClone(durable);
  const counted={},version='approved-version',manifest={deliveryVersion:version,artifacts:[{path:'output/report/account.md'}]};
  function hit(name){
   counted[name]=(counted[name]??0)+1;
   if(fail&&failure===name){fail=false;throw Error('injected '+name);}
  }
  const actions={
   checkpoint:(state,delta)=>{
    if(delta.delivery)hit('persistManifest');
    durable=structuredClone({...state,...delta});Object.assign(state,delta);
   },
   nativeStatus:async()=>{hit('nativeStatus');return 'turn_completed';},
   nativeSeal:async()=>{hit('nativeSeal');return 'sealed';},
   nativeDelivery:async actual=>{hit('nativeDelivery');assert.equal(actual,version);return manifest;},
   workerStop:async()=>{hit('workerStop');return {running:false,containerId:'owned-worker'};},
   verify:async(actual,proof)=>{hit('verify');assert.deepEqual(actual,manifest);assert.equal(proof.running,false);},
   archive:async()=>hit('archive'),
  };
  await assert.rejects(sealDelivery(record,version,actions),new RegExp('injected '+failure));
  record=structuredClone(durable);
  await assert.rejects(sealDelivery(record,'another-version',actions),/immutable/);
  assert.deepEqual(await sealDelivery(record,version,actions),manifest);
  assert.equal(record.sealProgress,null);
  assert.equal(record.workerState,'STOPPED');
  assert.equal(record.taskState,'SEALED_PENDING_UPLOAD');
  for(const phase of ['nativeStatus','nativeSeal','nativeDelivery','workerStop','verify','archive','persistManifest'])
   assert.equal(counted[phase],phase===failure?2:1,phase);
 });
}
test('sealed archive retries replace an incomplete envelope and preserve all original receipts',async t=>{
 const root=await mkdtemp(join(tmpdir(),'native-archive-test-'));
 t.after(()=>rm(root,{force:true,recursive:true}));
 const source=join(root,'events.jsonl'),output=join(root,'sealed-native-events.json');
 const event={eventId:'1',eventType:'tool.failed',payload:{message:'fixture failure'}};
 await writeFile(source,JSON.stringify(event)+'\n');
 await writeFile(output+'.next','{"events":[');
 await archiveNativeJournal(root,source);
 const first=await readFile(output);
 assert.deepEqual(JSON.parse(first),{events:[event]});
 await archiveNativeJournal(root,source);
 assert.deepEqual(await readFile(output),first);
});
