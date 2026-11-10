// Missing provider prices and unavailable receipts must remain unknown.
export function executionMetrics({outcome,modelReceipts,executions,events,startedAt,endedAt,assets},now=Date.now()) {
 const known=x=>typeof x==='number'&&Number.isFinite(x)&&x>=0;
 const receipts=Array.isArray(modelReceipts)?modelReceipts:null;
 const parts=receipts?.map(r=>[r.usage?.inputTokens??r.inputTokens,r.usage?.outputTokens??r.outputTokens]);
 const tokens=parts?.length&&parts.every(p=>p.every(known))?parts.flat().reduce((a,b)=>a+b,0):null;
 const consumed=outcome?.usage?.consumed;
 const begin=Date.parse(outcome?.startedAt??outcome?.createdAt??startedAt),finish=Date.parse(outcome?.endedAt??endedAt);
 const elapsed=Number.isFinite(begin)?(Number.isFinite(finish)?finish:now)-begin:null;
 const repairs=Array.isArray(executions)?executions.map(x=>x.repair?.attemptsUsed).filter(known):[];
 return {modelRequests:known(consumed?.modelCalls)?consumed.modelCalls:receipts?.length??null,
  tokens:tokens??(outcome?.usage?.meteringComplete===true?consumed?.modelTokens:null),
  cost:outcome?.usage?.meteringComplete===true?consumed?.costAmount:null,currency:outcome?.usage?.costCurrency??null,
  toolCalls:known(consumed?.toolCalls)?consumed.toolCalls:Array.isArray(events)?events.filter(x=>x.type==='tool.started').length:null,
  automaticRepairs:repairs.length?Math.max(...repairs):null,elapsedMillis:known(elapsed)?elapsed:null,
  phase:events?.at(-1)?.type??outcome?.status??null,
  interruptionReason:outcome?.error?.message??outcome?.failureReason??null,
  archivedFiles:Array.isArray(assets)?assets.length:null,artifactValidation:outcome?.validationStatus??null,
  meteringComplete:outcome?.usage?.meteringComplete??null};
}
