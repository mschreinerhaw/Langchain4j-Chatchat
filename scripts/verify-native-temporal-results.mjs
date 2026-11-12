/** Independently validate deliveries, ownership and measured native intervals. */
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const origin=process.env.RUNTIME_TEST_ORIGIN;
assert.ok(origin,'Explicit candidate origin required');
const users=JSON.parse(await readFile(process.env.RUNTIME_TEST_USERS_FILE,'utf8'));
const directories=process.argv.slice(2).map(v=>resolve(v));
assert.ok(directories.length);
const tokens=[];
async function get(path,token){return fetch(origin+'/api/v1'+path,{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(30000)});}
async function json(path,token){const r=await get(path,token);assert.equal(r.status,200);const v=await r.json();assert.equal(v.code,200,v.message);return v.data;}
for(const u of users){const r=await fetch(origin+'/api/v1/enterprise/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u.username,password:u.password})});const v=await r.json();assert.equal(v.code,200);tokens.push(v.data.token);}
const summary={passed:false,batches:[]};
const p95=values=>[...values].sort((a,b)=>a-b)[Math.ceil(values.length*.95)-1];
const stats=values=>({mean:values.reduce((a,b)=>a+b,0)/values.length,p95:p95(values)});
try{
 for(const dir of directories){
  const run=JSON.parse(await readFile(join(dir,'verification.json'),'utf8'));
  assert.ok(run.passed,run.error);
  const batch={label:run.label,total:run.tasks.length,completed:run.completed,failed:run.tasks.length-run.completed,
    elapsedSeconds:run.elapsedSeconds,throughputPerMinute:run.throughputPerMinute,tasks:[],overlaps:[]};
  const queue=[],durations=[];
  for(const t of run.tasks){
   const token=tokens[t.index%tokens.length],base=`/coding/tasks/runs/${t.runId}/${t.taskId}`;
   const detail=await json(`/coding-monitor/tasks/${t.runId}/${t.taskId}`,token);
   assert.equal(detail.outcome.status,'COMPLETED');
   assert.equal(detail.workflow.workflowStatus,'WORKFLOW_EXECUTION_STATUS_COMPLETED');
   const d=await json(base+'/delivery',token);
   assert.ok(d.results.some(v=>v.count===4&&v.total===40&&v.mean===10));
   const frozen=JSON.parse(await readFile(join(dir,`delivery-${t.index}.json`),'utf8'));
   const identity=files=>files.map(f=>({id:f.id,sha256:f.sha256,size:f.size})).sort((a,b)=>a.id.localeCompare(b.id));
   assert.deepEqual(identity(d.artifacts),identity(frozen.artifacts),'Later reads/replayed callbacks must not replace accepted bytes');
   assert.deepEqual(d.datasetReference,frozen.datasetReference,'Exactly the same effective registered final dataset');
   const downloads=[];
   await mkdir(join(dir,'downloaded-'+t.index),{recursive:true});
   for(const f of d.artifacts){
    assert.ok(f.size<=64*1024*1024,'Bounded artifact download');
    const r=await get(`/coding-monitor/tasks/${t.runId}/${t.taskId}/artifacts/${f.id}/content?download=true`,token);
    assert.equal(r.status,200);const bytes=Buffer.from(await r.arrayBuffer());
    assert.equal(bytes.length,f.size);assert.equal(createHash('sha256').update(bytes).digest('hex'),f.sha256);
    await writeFile(join(dir,'downloaded-'+t.index,f.id),bytes);
    downloads.push({fileName:f.fileName,sha256:f.sha256,size:f.size,id:f.id});
   }
   assert.ok(downloads.some(f=>f.fileName.endsWith('.py')),'Actual delivered dynamic Python source');
   assert.ok(downloads.some(f=>/\.(md|html)$/.test(f.fileName)),'Actual report download');
   const other=await get(base,tokens[(t.index+1)%tokens.length]);assert.equal(other.status,403,'Another account must not read the task');
   const artifactOther=await get(`/coding-monitor/tasks/${t.runId}/${t.taskId}/artifacts/${d.artifacts[0].id}/content`,tokens[(t.index+1)%tokens.length]);assert.equal(artifactOther.status,403,'Another account must not download artifacts');
   const receipts=detail.modelReceipts;
   assert.ok(receipts.length&&detail.executions.length,'Real model and process receipts');
   assert.equal(new Set(receipts.map(v=>v.requestId)).size,receipts.length,'Provider requests counted once');
   const first=Math.min(...receipts.map(v=>Date.parse(v.startedAt)));
   const last=Math.max(...receipts.map(v=>Date.parse(v.finishedAt)),...detail.executions.map(v=>Date.parse(v.finishedAt||v.startedAt)));
   const usage={requests:receipts.length,inputTokens:0,outputTokens:0,unknownUsageRequests:0};
   for(const r of receipts){if(r.usage?.inputTokens==null||r.usage?.outputTokens==null)usage.unknownUsageRequests++;else{usage.inputTokens+=r.usage.inputTokens;usage.outputTokens+=r.usage.outputTokens;}}
   const o=detail.outcome;queue.push((Date.parse(o.startedAt)-Date.parse(o.createdAt))/1000);durations.push((Date.parse(o.endedAt)-Date.parse(o.startedAt))/1000);
   batch.tasks.push({taskId:t.taskId,runId:t.runId,userId:t.userId,nodeId:detail.nodeId,sessionId:detail.sessionId,
    executionEpoch:detail.executionEpoch,workflowId:detail.workflowId,historyEvents:detail.workflow.historyEvents,
    nativeStart:first,nativeEnd:last,usage,downloads,ownerIsolation:'PASS',numericValidation:'PASS',finalDeliveryUnchanged:'PASS'});
  }
  for(let i=0;i<batch.tasks.length;i++)for(let j=i+1;j<batch.tasks.length;j++){
   const a=batch.tasks[i],b=batch.tasks[j];if(a.nodeId!==b.nodeId&&a.userId!==b.userId){
    const seconds=(Math.min(a.nativeEnd,b.nativeEnd)-Math.max(a.nativeStart,b.nativeStart))/1000;
    if(seconds>0)batch.overlaps.push({taskA:a.taskId,taskB:b.taskId,seconds});
   }
  }
  batch.queueSeconds=stats(queue);batch.executionSeconds=stats(durations);
  batch.nodes=[...new Set(batch.tasks.map(t=>t.nodeId))];
  batch.resources=run.nodeSamples?.flatMap(s=>s.fleet.nodes.map(n=>({at:s.at,...n})))||[];
  summary.batches.push(batch);
 }
 if(summary.batches.length===2){
  const [one,two]=summary.batches;
  assert.equal(one.nodes.length,1);assert.equal(two.nodes.length,2);assert.ok(two.overlaps.length,'Different users must have overlapping real Native executions on different nodes');
  summary.speedup=two.throughputPerMinute/one.throughputPerMinute;
  assert.ok(summary.speedup>1,'Scaling must improve measured throughput');
 }
 summary.passed=true;
}catch(e){summary.error=e.message;process.exitCode=1;console.error(e.message);}
finally{await writeFile(resolve(process.env.RUNTIME_TEST_SUMMARY||'target/native-temporal-results.json'),JSON.stringify(summary,null,2));}
