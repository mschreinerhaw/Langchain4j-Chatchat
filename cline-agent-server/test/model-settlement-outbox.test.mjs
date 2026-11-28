import assert from 'node:assert/strict';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { ModelSettlementOutbox } from '../src/model-settlement-outbox.mjs';

test('after interruption, outbox distinguishes unsent, ambiguous and measured provider usage',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'native-settlement-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 const record={tenantId:'test-tenant',taskId:'task-a'};
 const requests=['a','b','c'].map(x=>x.repeat(36));
 let now=0,available=false;
 const attempts=[],pending=[];
 const context={
  now:()=>now,
  onPending:(_,id)=>pending.push(id),
  send:async(bound,payload)=>{
   assert.equal(bound,record);
   attempts.push(payload);
   if(!available)throw Error('Runtime settlement network error');
  }
 };
 const initial=new ModelSettlementOutbox(context);
 const admitting=initial.begin(record,dir,requests[0]);
 const dispatched=initial.begin(record,dir,requests[1]);
 initial.dispatch(dispatched);
 const measured=initial.begin(record,dir,requests[2]);
 initial.dispatch(measured);
 initial.complete(measured,{providerTokens:48,neverDispatched:false});
 await initial.flush();
 assert.deepEqual(attempts,[{requestId:requests[2],providerTokens:48,neverDispatched:false}]);
 assert.deepEqual(pending,[requests[2]]);
 await initial.flush();
 assert.equal(attempts.length,1);
 assert.equal(admitting.item.phase,'ADMITTING');

 const restarted=new ModelSettlementOutbox(context);
 restarted.restore(record,dir);
 available=true;
 now=1000;
 await restarted.flush();
 assert.deepEqual(attempts.slice(1),[
  {requestId:requests[0],providerTokens:null,neverDispatched:true},
  {requestId:requests[1],providerTokens:null,neverDispatched:false},
  {requestId:requests[2],providerTokens:48,neverDispatched:false}
 ]);
 assert.deepEqual(await readdir(dir),[]);
});
