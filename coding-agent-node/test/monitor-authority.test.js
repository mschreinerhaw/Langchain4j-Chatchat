import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {MonitorStore,createMonitorHandler} from '../dist/monitor.js';

const contract={taskId:'same-task',scope:{tenantId:'tenant',userId:'owner',runId:'same-run'},objective:{instruction:'Fixed task'}};
const at='2026-11-06T04:00:00Z';
const projection=(status='COMPLETED',revision=20)=>({source:'DURABLE_CODING_JOURNAL',taskId:contract.taskId,...contract.scope,
 status,terminal:!['CREATED','RUNNING'].includes(status),revision,updatedAt:at,endedAt:!['CREATED','RUNNING'].includes(status)?at:null,
 reasonCode:status==='BUDGET_EXCEEDED'?'DEADLINE_EXCEEDED':null,summary:'Original task reason',limitations:['One declared missing deliverable']});

for(const status of ['COMPLETED','PARTIAL','FAILED','CANCELLED','BUDGET_EXCEEDED'])test(`Runtime ${status} remains authoritative after late progress and SDK finish`,()=>{
 const store=new MonitorStore(),id=store.start(contract,1,'NEW_SDK');
 store.event(id,{type:'MODEL_STARTED',modelCall:1});
 store.reconcileRuntime(id,projection(status));const original=store.detail(id);
 store.event(id,{type:'TOOL_STARTED',stage:'RUNNING'});store.finish(id,'FAILED','Late old attempt');
 store.reconcileRuntime(id,projection('RUNNING',2));store.reconcileRuntime(id,projection(status==='FAILED'?'COMPLETED':'FAILED',30));
 const after=store.detail(id);assert.equal(after.runtimeOutcome.status,status);assert.equal(after.finishedAt,at);
 assert.equal(after.status,original.status);assert.equal(after.authority,'RUNTIME_CONFIRMED');assert.equal(after.observedStatus,'FAILED');
 assert(after.events.length>original.events.length);assert.equal(after.models.length,1);
 assert.equal(store.start(contract,1,'NEW_SDK'),id);assert.equal(store.detail(id).runtimeOutcome.status,status);
});

test('Runtime projection survives actual monitor recreation without erasing observations',async()=>{
 const directory=await mkdtemp(join(tmpdir(),'phase3-monitor-'));
 try{
  const first=new MonitorStore({directory}),id=first.start(contract,1,'NEW_SDK');first.event(id,{type:'MODEL_STARTED',modelCall:1});
  first.reconcileRuntime(id,projection());await first.flush();
  const second=new MonitorStore({directory});const task=second.detail(id);
  assert.equal(task.status,'COMPLETED');assert.equal(task.observedStatus,'INTERRUPTED');assert.equal(task.finishedAt,at);
  assert.equal(task.models.length,1);assert(task.events.some(e=>e.type==='MODEL_STARTED'));await second.flush();
 }finally{await rm(directory,{recursive:true,force:true});}
});

test('wrong owner and malformed authority cannot turn an uncertain task into success',()=>{
 const store=new MonitorStore(),id=store.start(contract,1,'NEW_SDK');
 for(const change of [{userId:'other'},{runId:'other'},{taskId:'other'},{terminal:false},{revision:-1},{endedAt:null}])
  assert.throws(()=>store.reconcileRuntime(id,{...projection(),...change}),/Invalid Runtime/);
 store.runtimeUnavailable(id);assert.equal(store.detail(id).status,'RUNNING');assert.equal(store.detail(id).runtimeUnavailable,true);
 assert.equal(store.detail(id).runtimeOutcome,undefined);
});

test('authenticated Monitor API reads same-task Runtime status and never starts an SDK session',async()=>{
 const store=new MonitorStore(),id=store.start(contract,1,'NEW_SDK'),token='m'.repeat(32);let reads=0;
 const handler=createMonitorHandler({token,store,snapshot:()=>({}),runtimeAuthority:async task=>{
  reads++;assert.equal(task.taskId,contract.taskId);return projection('BUDGET_EXCEEDED');}});
 const server=createServer(async(req,res)=>{if(!await handler(req,res)){res.writeHead(404);res.end();}});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const base=`http://127.0.0.1:${server.address().port}/v1/monitor`;
 try{
  assert.equal((await fetch(base+'/tasks')).status,401);assert.equal(reads,0);
  const headers={Authorization:'Bearer '+token};const list=await(await fetch(base+'/tasks',{headers})).json();
  assert.equal(list.items[0].status,'FAILED');assert.equal(list.items[0].runtimeOutcome.status,'BUDGET_EXCEEDED');
  const detail=await(await fetch(base+'/tasks/'+id,{headers})).json();
  assert.equal(detail.task.taskId,contract.taskId);assert.equal(detail.task.finishedAt,at);assert.equal(reads,1);
  assert.match(detail.task.error,/DEADLINE_EXCEEDED/);
 }finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await store.flush();}
});
