import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync,readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {persistRun,restoreRun,runtimeDeliveryStatus} from '../src/business/run-state.mjs';
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
