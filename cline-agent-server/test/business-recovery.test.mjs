import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync,readFileSync,writeFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {persistRun,restoreRun,runtimeDeliveryStatus,reconcileStoppedRun} from '../src/business/run-state.mjs';
import {Journal} from '../src/journal.mjs';

test('restart retains native controls and unresolved dispatches without replaying Python',()=>{
 const dir=mkdtempSync(join(tmpdir(),'native-recovery-'));
 try {
  const contract={scope:{tenantId:'t',userId:'u',runId:'r'},taskId:'task'};
  const original={dir,contract,workerContract:{sessionId:'worker'},workerId:'worker',apiToken:'private',
   brokerToken:'private-broker',epoch:'epoch',nativeAgentSessionId:'official-session',activeOperations:new Set(),
   journal:new Journal(join(dir,'events.jsonl'))};
  persistRun(original,{status:'RUNNING',nativeSessionId:'native-session',fencingToken:3,contractHash:'hash'});
  // Crash after the durable tool intent but before the next state snapshot.
  original.journal.append({type:'runtime.event',payload:{type:'TOOL_STARTED',tool:'python_execute',operationId:'actual-operation'}});
  const restored=restoreRun(dir,'/workers',()=>({getPython(){},cancelPython(){}}));
  assert.equal(restored.status,'UNKNOWN');assert.equal(restored.recoveryControllable,true);
  assert.equal(restored.nativeAgentSessionId,'official-session');assert.equal(restored.apiToken,'private');
  assert.deepEqual([...restored.activeOperations],['actual-operation']);
  assert.equal(restored.gateway.executePython,undefined);
  assert.equal(restored.contract.scope.userId,'u');
  assert.equal(JSON.parse(readFileSync(join(dir,'run.json'))).version,2);
 }finally{rmSync(dir,{recursive:true,force:true});}
});

test('legacy UNKNOWN keeps real Executor stop proof from its durable audit',()=>{
 const dir=mkdtempSync(join(tmpdir(),'native-legacy-'));
 try {
  const run={dir,contract:{scope:{tenantId:'t'},taskId:'task'},workerId:'worker',containerId:'container',activeOperations:new Set()};
  persistRun(run,{status:'UNKNOWN'});
  new Journal(join(dir,'events.jsonl')).append({type:'cancel.reconciled',payload:{localStopProof:{containerId:'container',running:false}}});
  const restored=restoreRun(dir,'/workers',()=>({}));
  assert.equal(restored.status,'UNKNOWN');assert.equal(restored.stopProof.running,false);
  assert.equal(restored.recoveryControllable,false);
 }finally{rmSync(dir,{recursive:true,force:true});}
});

test('partial delivery and completion missing Runtime evidence cannot become COMPLETED',()=>{
 assert.throws(()=>runtimeDeliveryStatus({status:'PARTIAL'}),/complete business delivery/);
 assert.throws(()=>runtimeDeliveryStatus({status:'BUDGET_EXCEEDED'}),/complete business delivery/);
 assert.throws(()=>runtimeDeliveryStatus({status:'COMPLETED'}),/references/);
 assert.equal(runtimeDeliveryStatus({status:'COMPLETED',resultDatasetRef:'r',validationRecordRef:'v',evidenceBundleRef:'e'}),'COMPLETED');
});

test('stopped Native recovery observes Runtime stop without promoting delivery or replaying',async()=>{
 let status='RUNNING',calls=0;
 const run={status:'UNKNOWN',recoveryControllable:true,stopProof:{running:false},remoteStopConfirmed:true,
  gateway:{monitorPython:async(id,kind)=>{assert.equal(id,undefined);assert.equal(kind,'status');calls++;return {status};}}};
 assert.equal(await reconcileStoppedRun(run),false);
 status='COMPLETED';assert.equal(await reconcileStoppedRun(run),false);
 status='CANCELLED';run.remoteStopConfirmed=false;assert.equal(await reconcileStoppedRun(run),false);
 assert.equal(calls,2);run.remoteStopConfirmed=true;
 assert.equal(await reconcileStoppedRun(run),true);assert.equal(run.status,'CANCELLED');
 assert.equal(run.runtimeStopReceipt.status,'CANCELLED');assert.equal(run.result,undefined);
 run.status='UNKNOWN';run.recovered=true;run.recoveryControllable=false;
 assert.equal(await reconcileStoppedRun(run),false);
 run.recovered=false;assert.equal(await reconcileStoppedRun(run),true);
});

test('Executor rejects a Runtime policy missing official completion before contacting Docker',()=>{
 const dir=mkdtempSync(join(tmpdir(),'native-executor-policy-'));
 try {
  const config=join(dir,'executor.json');writeFileSync(config,JSON.stringify({mcpServerName:'runtime',tenants:{tenant:{tools:['read_files','runtime__*']}}}));
  const child=spawnSync(process.execPath,[fileURLToPath(new URL('../src/worker-executor.mjs',import.meta.url))],{encoding:'utf8',env:{...process.env,CLINE_EXECUTOR_CONFIG:config}});
  assert.equal(child.status,1);assert.match(child.stderr,/must permit the official submit_and_exit/);
 }finally{rmSync(dir,{recursive:true,force:true});}
});
