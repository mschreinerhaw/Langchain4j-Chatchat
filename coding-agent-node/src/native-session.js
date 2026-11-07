/** Governance around SDK model transport/checkpoints; contains no agent loop. */
import {createHash,randomUUID} from 'node:crypto';
import {completeToolBoundary,boundCompactionCheckpoint} from './sdk-adapter.js';

export function createNativeSession(contract,gateway,options={}) {
  if(!/^[a-f0-9]{64}$/.test(options.contractHash??''))throw new TypeError('A bound Runtime contract hash is required');
  const saved=options.checkpoint;
  const sessionId=saved?.sdkSessionId??randomUUID();
  if(!/^[a-zA-Z0-9-]{1,64}$/.test(sessionId))throw new TypeError('Invalid native SDK session identity');
  if(saved && (saved.contractHash!==options.contractHash||saved.inputSnapshotRef!==contract.inputSnapshotRef
      ||saved.workspaceRef!==contract.workspaceRef||!Number.isSafeInteger(saved.modelOrdinal)||saved.modelOrdinal<0
      ||!Number.isSafeInteger(saved.checkpointOrdinal)||saved.checkpointOrdinal<1))throw new TypeError('Native checkpoint contract binding differs');
  let modelOrdinal=saved?.modelOrdinal??0,checkpointOrdinal=saved?.checkpointOrdinal??0,eventOrdinal=saved?.eventOrdinal??0;
  let replayPending=saved?.receiptReplayOperationId??null;
  let feedback=saved?.modelResponseFeedback??null,feedbackRepairs=saved?.modelResponseRepairs??0;
  const base=options.model;
  if(typeof base?.stream!=='function')throw new TypeError('A Runtime-authorized model is required');
  async function event(value) {
    const envelope={...value,source:'NODE_CLINE',attemptId:options.attemptId??contract.taskId+':native',
      sequence:++eventOrdinal,timestamp:new Date().toISOString()};
    try{options.observe?.(envelope);}catch{}
    try{await gateway.event?.(envelope);}catch{} // Never execution authority.
  }
  async function checkpoint(messages,extra={}) {
    if(replayPending)return checkpointOrdinal;
    if(!completeToolBoundary(messages))throw new TypeError('Checkpoint requires complete SDK tool boundaries');
    await gateway.checkpoint(contract,boundCompactionCheckpoint({
      ...extra,sdkSessionId:sessionId,nativeSdk:true,modelOrdinal,checkpointOrdinal:++checkpointOrdinal,eventOrdinal,
      modelResponseFeedback:feedback,modelResponseRepairs:feedbackRepairs,
      contractHash:options.contractHash,inputSnapshotRef:contract.inputSnapshotRef,workspaceRef:contract.workspaceRef,messages,
    },262144));
    return checkpointOrdinal;
  }
  const model={...base,async *stream(request){
    if(request.signal?.aborted)throw request.signal.reason;
    if(Date.now()>=Date.parse(contract.budget.deadlineAt))throw new Error('Runtime Coding deadline reached');
    if(modelOrdinal>=contract.budget.maxModelCalls)throw new Error('Runtime model call budget exhausted');
    const names=request.tools.map(t=>t.name),allowed=new Set(contract.authorization.allowedTools);
    if(new Set(names).size!==names.length||names.some(name=>!allowed.has(name)))throw new TypeError('Model tool disclosure is outside the task grant');
    if(replayPending&&replayPending!==`model-${modelOrdinal+1}`)throw new TypeError('Native SDK receipt replay ordinal differs');
    if(feedback&&!replayPending){
      if(feedbackRepairs>=feedback.maxRepairAttempts)throw new Error('MODEL_RESPONSE_REPAIR_LIMIT_REACHED');
      feedbackRepairs++;
    }
    const ordinal=++modelOrdinal;
    const replay=Boolean(replayPending);
    request={...request,runtimeCheckpointOrdinal:checkpointOrdinal,...(replay?{runtimeReceiptReplay:true}:{})};
    if(!replay)await gateway.budget?.(contract,Math.ceil(Buffer.byteLength(JSON.stringify({
      systemPrompt:request.systemPrompt??'',messages:request.messages,tools:request.tools}))/4),request.signal);
    if(!base.managesAdmissions&&!replay)await gateway.reserveModel(contract,{operationId:`model-${ordinal}`,modelCalls:1,
      maxTokens:Number(options.modelTokenReservation??2048),checkpointOrdinal});
    await event({type:'MODEL_STARTED',modelCall:ordinal,authorizedTools:[...allowed].sort(),disclosedTools:[...names].sort(),
      toolSchemaSha256:createHash('sha256').update(JSON.stringify(request.tools)).digest('hex')});
    let usage={},replayed=false,rejected=false;
    for await(const value of base.stream(request)) {
      if(value.type==='runtime-model-metadata'){replayed=value.metadata?.receiptReplayed===true;continue;}
      if(value.type==='runtime-model-feedback'){
        const next=value.feedback;
        if(next?.repairAllowed!==true || !Number.isInteger(next.maxRepairAttempts)||next.maxRepairAttempts<1||next.maxRepairAttempts>8)
          throw new Error('Unverified provider-response repair policy');
        feedback=next;rejected=true;await event({type:'MODEL_RESPONSE_REJECTED',modelCall:ordinal,diagnosticCode:next.diagnosticCode});continue;
      }
      if(value.type==='usage')usage={...usage,...value.usage};
      if(value.type==='finish'&&value.reason!=='error'){
        if(replay&&!replayed)throw new Error('Expected original model receipt, not a new inference');
        if(!base.managesAdmissions&&!replayed)await gateway.settleModel(contract,{operationId:`model-${ordinal}`,requestId:value.requestId??null,usage});
        if(replayed)replayPending=null;
        if(!rejected)feedback=null;
        await event({type:'MODEL_USAGE',modelCall:ordinal,usage,status:'SUCCEEDED',receiptReplayed:replayed});
      }
      yield value;
    }
  }};
  return {sessionId,model,event,checkpoint,
    state:()=>({modelOrdinal,checkpointOrdinal,eventOrdinal,receiptReplayOperationId:replayPending})};
}
