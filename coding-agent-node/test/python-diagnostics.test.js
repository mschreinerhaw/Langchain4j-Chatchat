import test from 'node:test';
import assert from 'node:assert/strict';
import { repairLinks, sourceDiff } from '../dist/client/web/python-diagnostics.js';
import { createServer } from 'node:http';
import { MonitorStore, createMonitorHandler } from '../src/monitor.ts';
import { contract } from '../fixtures/monitor-server.mjs';
const execution=(hash,attempts,allowed,status='FAILED',resultAvailable=false)=>({executionId:hash,codeHash:hash,status,resultAvailable,
  repair:{attemptsUsed:attempts,baseCodeHash:hash,allowed}});
test('repair relationships require authoritative counters and an exact failed parent hash',()=>{
 const a=execution('a',0,true),b=execution('b',1,true),c=execution('c',2,false,'SUCCEEDED',true);
 assert.equal(repairLinks([a,b,c]).length,2);
 assert.equal(repairLinks([a,execution('b',0,false)]).length,0);
 assert.equal(repairLinks([a,execution('b',2,false)]).length,0);
 assert.equal(repairLinks([{...a,repair:{...a.repair,baseCodeHash:'other'}},b]).length,0);
 assert.equal(repairLinks([execution('a',0,true,'SUCCEEDED',true),b]).length,0);
 assert.equal(repairLinks([execution('a',0,true,'SUCCEEDED',false),b]).length,1);
});
test('diff shows actual minimal changes, unchanged files and bounded large changes',()=>{
 assert.equal(sourceDiff('same','same'),'内容未修改');
 const diff=sourceDiff("row['missing']\n", "row['amount']\n");assert.match(diff,/- row\['missing'\]/);assert.match(diff,/\+ row\['amount'\]/);
 assert.match(sourceDiff('',Array(1000).fill('x').join('\n')),/展示已截断/);
});
test('operator Python projection is read-only, retained-task-bound and never persisted in Node history',async()=>{
 const token='monitor-only-token-0123456789abcdef01234567',store=new MonitorStore();const id=store.start(contract,1,'NEW_SDK');let calls=[];
 const handler=createMonitorHandler({token,store,snapshot:()=>store.node(false,0,2,90000),python:async(task,operation,kind)=>{calls.push({task,operation,kind});return {source:'DURABLE_CODING_JOURNAL',privateMarker:'ephemeral-receipt'};}});
 const server=createServer((req,res)=>void handler(req,res));await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try {
  const url=`http://127.0.0.1:${server.address().port}/v1/monitor/tasks/${id}/python`,headers={authorization:'Bearer '+token};
  assert.equal((await fetch(url)).status,401);assert.equal(calls.length,0);
  assert.equal((await fetch(url,{method:'POST',headers})).status,405);assert.equal(calls.length,0);
  assert.equal((await fetch(url+'?tenantId=forged',{headers})).status,200);assert.equal(calls[0].task.tenantId,contract.scope.tenantId);
  assert(!JSON.stringify(store.detail(id)).includes('ephemeral-receipt'));
  assert.equal((await fetch(url.replace(id,'0'.repeat(64)),{headers})).status,404);
  const op='cline-'+'a'.repeat(64);assert.equal((await fetch(url+'/codes/'+op,{headers})).status,200);assert.equal(calls.at(-1).operation,op);
  assert.equal((await fetch(url+'/timing/'+op,{headers})).status,200);assert.equal(calls.at(-1).kind,'timing');
 }finally{await new Promise(r=>server.close(r));}
});
