/** Studio uses the shared model relay with its existing signed cumulative limits. */
export function admitStudioModel(record, body, responses) {
 const limit=record.contract.studioBudget;
 if(!limit)return;
 const usage=record.studioUsage??={calls:0,tokens:0,pending:false,unknown:false};
 const deny=message=>{throw Object.assign(Error(message),{status:429});};
 if(limit.modelCalls>=0&&usage.calls>=limit.modelCalls)deny('Studio model call budget exhausted');
 if(limit.modelTokens>=0){
  if(usage.pending||usage.unknown)deny('Studio model token usage requires reconciliation');
  const remaining=limit.modelTokens-usage.tokens;
  // Estimate an upper bound for text input before dispatch; settle from the actual provider receipt.
  const input=Buffer.byteLength(JSON.stringify(body));
  if(remaining<=input)deny('Studio model token budget exhausted');
  const field=responses?'max_output_tokens':body.max_completion_tokens!==undefined?'max_completion_tokens':'max_tokens';
  body[field]=Number.isSafeInteger(body[field])&&body[field]>0?Math.min(body[field],remaining-input):remaining-input;
  if(!responses&&body.stream)body.stream_options={...body.stream_options,include_usage:true};
  usage.pending=true;
 }
 usage.calls++;
}

export function settleStudioModel(record, receipt) {
 const limit=record.contract.studioBudget;
 if(!limit||limit.modelTokens<0)return;
 const usage=record.studioUsage;
 const actual=receipt.usage?.totalTokens;
 if(Number.isSafeInteger(actual)&&actual>=0)usage.tokens+=actual;
 else if(receipt.httpStatus===null||receipt.httpStatus<400)usage.unknown=true;
 usage.pending=false;
}
