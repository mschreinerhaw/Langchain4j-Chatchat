// Missing provider prices and unavailable receipts must remain unknown.
export function executionMetrics({outcome,modelReceipts,executions,events,startedAt,endedAt,assets},now=Date.now()) {
 const known=x=>typeof x==='number'&&Number.isFinite(x)&&x>=0;
 const receipts=Array.isArray(modelReceipts)?modelReceipts:null;
 const parts=receipts?.map(r=>[r.usage?.inputTokens??r.inputTokens,r.usage?.outputTokens??r.outputTokens]);
 const tokens=parts?.length&&parts.every(p=>p.every(known))?parts.flat().reduce((a,b)=>a+b,0):null;
 const consumed=outcome?.usage?.consumed;
 const first=events?.find(e=>e.type==='model.iteration.started')??events?.[0];
 const last=events?.findLast(e=>['turn.completed','task.failed'].includes(e.type));
 const begin=Date.parse(outcome?.startedAt??outcome?.createdAt??startedAt??first?.timestamp),finish=Date.parse(outcome?.endedAt??endedAt??last?.timestamp);
 const elapsed=Number.isFinite(begin)?(Number.isFinite(finish)?finish:now)-begin:null;
 const repairs=Array.isArray(executions)?executions.map(x=>x.repair?.attemptsUsed).filter(known):[];
 return {modelRequests:known(consumed?.modelCalls)?consumed.modelCalls:receipts?.length??null,
  tokens:tokens??(outcome?.usage?.meteringComplete===true?consumed?.modelTokens:null),
  cost:outcome?.usage?.meteringComplete===true?consumed?.costAmount:null,currency:outcome?.usage?.costCurrency??null,
  toolCalls:Array.isArray(events)?new Set(events.filter(x=>x.type==='tool.started').map((x,i)=>x.payload?.toolCallId??x.id??i)).size:known(consumed?.toolCalls)?consumed.toolCalls:null,
  managedToolCalls:known(consumed?.toolCalls)?consumed.toolCalls:null,
  automaticRepairs:repairs.length?Math.max(...repairs):outcome?0:null,elapsedMillis:known(elapsed)?elapsed:null,
  phase:outcome?.endedAt?outcome.status:executions?.at(-1)?.status==='RUNNING'?'PYTHON_RUNNING':receipts?.at(-1)?.state==='STARTED'?'MODEL_RUNNING':outcome?.status??events?.at(-1)?.type??null,
  interruptionReason:outcome?.endedAt&&outcome.reasonCode!=='NONE'?outcome.reasonCode:null,
  archivedFiles:Array.isArray(assets)?assets.length:null,
  artifacts:Array.isArray(assets)?assets.filter(a=>a.kind==='ARTIFACT').length:null,
  artifactValidation:outcome?.validationStatus??null,
  meteringComplete:outcome?.usage?.meteringComplete??null};
}
