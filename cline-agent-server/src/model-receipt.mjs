// Observation of real provider bytes only. No model policy, retries or estimates.
import {createHash,randomUUID} from 'node:crypto';
const count=value=>Number.isSafeInteger(value)&&value>=0?value:null;
export function providerUsage(raw){
 if(!raw||typeof raw!=='object')return null;
 const anthropic=Object.hasOwn(raw,'cache_read_input_tokens')||Object.hasOwn(raw,'cache_creation_input_tokens');
 const uncached=count(raw.prompt_tokens??raw.input_tokens),output=count(raw.completion_tokens??raw.output_tokens);
 const cached=count(raw.prompt_tokens_details?.cached_tokens??raw.input_tokens_details?.cached_tokens??raw.cache_read_input_tokens);
 const written=count(raw.cache_creation_input_tokens),reasoning=count(raw.completion_tokens_details?.reasoning_tokens??raw.output_tokens_details?.reasoning_tokens);
 const input=anthropic&&uncached!==null?uncached+(cached??0)+(written??0):uncached;
 const declared=count(raw.total_tokens);
 return {inputTokens:input,outputTokens:output,totalTokens:declared??(input!==null&&output!==null?input+output:null),
  cacheReadTokens:cached,cacheWriteTokens:written,reasoningTokens:reasoning,
  totalSource:declared!==null?'PROVIDER':input!==null&&output!==null?'SUM_OF_PROVIDER_COUNTS':'NOT_PROVIDED',
  cacheAccounting:anthropic?'NORMALIZED_INPUT_INCLUDES_CACHE':'CACHE_IS_INPUT_SUBSET',raw};
}
export function modelReceipt({model,request,emit,now=Date.now}){
 const requestId=randomUUID(),started=now(),responseHash=createHash('sha256'),decoder=new TextDecoder();
 const requestSha256=createHash('sha256').update(request).digest('hex');
 let pending='',jsonBody='',usage=null,providerModel=model,responseBytes=0,finished=false,observationTruncated=false;
 const MAX=4*1024*1024;
 function value(document){
  if(!document||typeof document!=='object')return;
  providerModel=document.model??document.message?.model??document.response?.model??providerModel;
  const observed=document.usage??document.message?.usage??document.response?.usage;
  if(observed){usage={...(usage??{}),...observed};}
 }
 function line(text){
  if(!text.startsWith('data:'))return;
  const data=text.slice(5).trim();if(!data||data==='[DONE]')return;
  try{value(JSON.parse(data));}catch{}
 }
 emit('model.request.started',{requestId,model,startedAt:new Date(started).toISOString(),requestSha256,source:'PROVIDER_HTTP_OBSERVATION'});
 return {requestId,observe(chunk){
  responseHash.update(chunk);responseBytes+=chunk.length;
  const text=decoder.decode(chunk,{stream:true});
  if(jsonBody.length<MAX)jsonBody+=text.slice(0,MAX-jsonBody.length);else observationTruncated=true;
  pending+=text;
  for(let newline;(newline=pending.indexOf('\n'))!==-1;){line(pending.slice(0,newline).trimEnd());pending=pending.slice(newline+1);}
  if(pending.length>MAX){pending='';observationTruncated=true;}
 },finish({httpStatus=null,providerRequestId=null,errorType=null}={}){
  if(finished)return;finished=true;
  pending+=decoder.decode();line(pending);
  if(!usage)try{value(JSON.parse(jsonBody));}catch{}
  const ended=now();emit('model.request.completed',{requestId,model,providerModel,providerRequestId,
   startedAt:new Date(started).toISOString(),finishedAt:new Date(ended).toISOString(),durationMs:Math.max(0,ended-started),
   httpStatus,status:errorType||httpStatus===null||httpStatus>=400?'FAILED':'SUCCEEDED',errorType,
   providerDispatched:httpStatus!==null?true:null,usage:providerUsage(usage),responseBytes,
   responseSha256:responseHash.digest('hex'),requestSha256,observationTruncated,source:'PROVIDER_HTTP_OBSERVATION'});
 }};
}
