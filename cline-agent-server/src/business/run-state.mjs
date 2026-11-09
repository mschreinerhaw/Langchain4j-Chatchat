import {readFileSync,writeFileSync,renameSync,openSync,fsyncSync,closeSync} from 'node:fs';
import {join} from 'node:path';
import {Journal} from '../journal.mjs';

// This private record contains control credentials. It is never a public API response.
export function persistRun(run,publicView) {
 const record={...publicView,version:2,contract:run.contract,scope:run.contract.scope,taskId:run.contract.taskId,
  workerContract:run.workerContract,workerId:run.workerId,workerDir:run.workerDir,containerId:run.containerId,
  apiToken:run.apiToken,brokerToken:run.brokerToken,epoch:run.epoch,nativeAgentSessionId:run.nativeAgentSessionId,
  activeOperations:[...run.activeOperations],lastOperation:run.lastOperation,lastReceipt:run.lastReceipt,
  stopProof:run.stopProof,remoteStopConfirmed:run.remoteStopConfirmed,codeLifecycle:run.codeLifecycle,
  nativeCompletion:run.nativeCompletion,cancelRequested:run.cancelRequested,eventOrdinal:run.eventOrdinal,
  runtimeStopReceipt:run.runtimeStopReceipt,recoveryControllable:run.recoveryControllable};
 const temporary=join(run.dir,'run.json.tmp');
 writeFileSync(temporary,JSON.stringify(record),{mode:0o600});
 const fd=openSync(temporary,'r+');try{fsyncSync(fd);}finally{closeSync(fd);}
 renameSync(temporary,join(run.dir,'run.json'));
}

export function restoreRun(dir,workersRoot,gatewayFactory) {
 const old=JSON.parse(readFileSync(join(dir,'run.json'),'utf8'));
 const contract=old.contract??{scope:old.scope,taskId:old.taskId};
 const run={...old,dir,contract,workerDir:join(workersRoot,old.workerId),
  status:['RUNNING','STARTING'].includes(old.status)?'UNKNOWN':old.status,recovered:true,
  journal:new Journal(join(dir,'events.jsonl')),abort:new AbortController(),
  activeOperations:new Set(old.activeOperations??[]),approvedCalls:new Map()};
 run.gateway=gatewayFactory(run);
 // Reconcile dispatch intents persisted in the journal before contacting Python.
 // No restart path calls executePython, create, start, or an SDK checkpoint.
 for(const event of run.journal.events) {
  const stopped=event.type==='cancel.reconciled'?event.payload?.localStopProof:null;
  if(stopped?.running===false&&stopped.containerId===run.containerId)run.stopProof=stopped;
  if(event.type==='runtime.event'&&event.payload?.tool==='python_execute') {
   if(event.payload.type==='TOOL_STARTED')run.activeOperations.add(event.payload.operationId);
   if(event.payload.type==='TOOL_FINISHED'&&event.payload.executorStopped===true)run.activeOperations.delete(event.payload.operationId);
  }
  if(event.type==='cancel.remote_receipt'&&event.payload?.receipt?.executorStopped===true
      &&['SUCCEEDED','FAILED','CANCELLED','TIMED_OUT'].includes(event.payload.receipt.status))run.activeOperations.delete(event.payload.operationId);
 }
 run.recoveryControllable=old.recoveryControllable!==false&&!!(old.contract&&old.workerContract&&old.apiToken);
 return run;
}

export function runtimeDeliveryStatus(receipt) {
 if(receipt?.status!=='COMPLETED')throw Error('Runtime did not accept complete business delivery: '+receipt?.status);
 if(!receipt.resultDatasetRef||!receipt.validationRecordRef||!receipt.evidenceBundleRef)
  throw Error('Runtime completion lacks verified delivery references');
 if(receipt.validationStatus!=='PASSED'||receipt.businessValidationPassed!==true)
  throw Error('Runtime completion lacks passed independent business validation');
 return receipt.status;
}

// Observe an existing terminal stop, without executing or inferring business success.
export async function reconcileStoppedRun(run) {
 if(run.status!=='UNKNOWN'||(run.recovered&&!run.recoveryControllable)||run.stopProof?.running!==false
     ||run.remoteStopConfirmed!==true)return false;
 const receipt=await run.gateway.monitorPython(undefined,'status');
 if(!['CANCELLED','FAILED','BUDGET_EXCEEDED','ABORTED'].includes(receipt.status))return false;
 run.status=receipt.status;
 run.runtimeStopReceipt=receipt;
 return true;
}
