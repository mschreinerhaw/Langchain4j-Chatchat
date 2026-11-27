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
function textChars(content) {
 if (typeof content === 'string') return content.length;
 if (!Array.isArray(content)) return 0;
 return content.reduce((sum, part) => sum + (typeof part?.text === 'string' ? part.text.length : 0), 0);
}

function contextShape(body, request) {
 if (!body) return null;
 const messages = Array.isArray(body.messages) ? body.messages : Array.isArray(body.input) ? body.input : [];
 const lastUser = messages.findLastIndex(message => message?.role === 'user');
 let systemChars = textChars(body.instructions), userChars = textChars(body.input), historyChars = 0, toolResultChars = 0;
 for (const [index, message] of messages.entries()) {
  if (!message || typeof message !== 'object') continue;
  const content = textChars(message.content);
  if (message.role === 'system' || message.role === 'developer') systemChars += content;
  else if (index === lastUser) userChars += content;
  else if (message.role === 'tool' || message.type === 'function_call_output') {
   toolResultChars += content + textChars(message.output);
  } else {
   historyChars += content + textChars(message.name) + textChars(message.arguments);
   for (const call of Array.isArray(message.tool_calls) ? message.tool_calls : []) {
    historyChars += textChars(call?.function?.name) + textChars(call?.function?.arguments);
   }
  }
 }
 return {
  format: Array.isArray(body.messages) ? 'CHAT_COMPLETIONS' : 'RESPONSES',
  systemChars, userChars, historyChars, toolResultChars,
  toolsChars: Array.isArray(body.tools) ? JSON.stringify(body.tools).length : 0,
  skillsChars: null, evidenceChars: null,
  requestBytes: Buffer.byteLength(request)
 };
}

export function modelTransportErrorCode(error){
 if(error.code==='RUNTIME_DEADLINE_EXCEEDED'||error.code==='RUNTIME_TASK_STOPPED'||error.code==='CORE_DISCONNECTED')return error.code;
 if(error.code==='GATEWAY_REDIRECT')return 'GATEWAY_REDIRECT';
 if(error.name==='AbortError')return 'UPSTREAM_ABORTED';
 const causes=[error.code,error.cause?.code,...(error.cause?.errors??[]).map(item=>item.code)];
 if(error.name==='TimeoutError'||causes.some(code=>['UND_ERR_CONNECT_TIMEOUT','UND_ERR_HEADERS_TIMEOUT','UND_ERR_BODY_TIMEOUT','ETIMEDOUT'].includes(code)))return 'UPSTREAM_TIMEOUT';
 if(causes.some(code=>['ENOTFOUND','EAI_AGAIN'].includes(code)))return 'UPSTREAM_DNS_FAILURE';
 if(causes.some(code=>['ECONNREFUSED','ECONNRESET','UND_ERR_SOCKET','EHOSTUNREACH','ENETUNREACH'].includes(code)))return 'UPSTREAM_CONNECTION_FAILURE';
 if(causes.some(code=>['CERT_HAS_EXPIRED','ERR_TLS_CERT_ALTNAME_INVALID','UNABLE_TO_VERIFY_LEAF_SIGNATURE','SELF_SIGNED_CERT_IN_CHAIN','DEPTH_ZERO_SELF_SIGNED_CERT','UNABLE_TO_GET_ISSUER_CERT_LOCALLY','ERR_SSL_WRONG_VERSION_NUMBER'].includes(code)))return 'UPSTREAM_TLS_FAILURE';
 return 'UPSTREAM_TRANSPORT_FAILURE';
}

export function modelReceipt({model,request,body,reasoning={},emit,now=Date.now,requestId=randomUUID()}){
 const started=now(),responseHash=createHash('sha256'),decoder=new TextDecoder();
 const requestSha256=createHash('sha256').update(request).digest('hex');
 let requestContext = null;
 try { requestContext = contextShape(body, request); } catch { /* Usage observation still proceeds. */ }
 let pending='',jsonBody='',usage=null,providerModel=model,responseBytes=0,finished=false,observationTruncated=false,streamError;
 let frameLines=[],frameSize=0,discardFrame=false;
 let headersAt=null,firstBodyAt=null,lastBodyAt=null,firstDataAt=null,lastDataAt=null,firstModelDeltaAt=null;
 const MAX=4*1024*1024;
 function value(document){
  if(!document||typeof document!=='object')return;
  providerModel=document.model??document.message?.model??document.response?.model??providerModel;
  const observed=document.usage??document.message?.usage??document.response?.usage;
  if(observed){usage={...(usage??{}),...observed};}
 }
 function line(text){
  if(text.startsWith('data:')){
   const data=text.slice(5).replace(/^ /,'');
   frameSize+=data.length+1;
   if(frameSize>MAX){discardFrame=true;observationTruncated=true;frameLines=[];}
   if(!discardFrame)frameLines.push(data);
   return;
  }
  if(text!=='')return;
  const data=discardFrame?'':frameLines.join('\n').trim();
  frameLines=[];frameSize=0;discardFrame=false;
  if(!data||data==='[DONE]')return;
  try{
   const frame=JSON.parse(data);
   if(frame?.error&&typeof frame.error==='object')streamError=gatewayRequestId
    ?gatewayError(frame.error.code)??'UPSTREAM_STREAM_ERROR':'UPSTREAM_STREAM_ERROR';
   firstDataAt??=now();lastDataAt=now();
   if (frame && ((Array.isArray(frame.choices)&&frame.choices.some(choice=>
     choice?.delta?.content||choice?.delta?.reasoning_content||choice?.delta?.tool_calls?.length))
     ||(typeof frame.type==='string'&&frame.type.startsWith('response.')&&frame.type.endsWith('.delta')&&frame.delta)
     ||(frame.type==='content_block_delta'&&frame.delta))) firstModelDeltaAt??=lastDataAt;
   value(frame);
  }catch{}
 }
 emit('model.request.started',{...reasoning,requestId,model,startedAt:new Date(started).toISOString(),requestSha256,requestContext,source:'PROVIDER_HTTP_OBSERVATION'});
 let gatewayRequestId,gatewayHttpError;
 function gatewayError(code){
  return ['runtime_deadline_exceeded','gateway_draining','gateway_shutdown','upstream_protocol_error','upstream_error_event',
   'upstream_timeout','first_packet_timeout','upstream_failure','upstream_http_error','concurrency_limit']
   .includes(code)?'GATEWAY_'+code.toUpperCase():undefined;
 }
 return {requestId,headers(gatewayId,errorCode){
  headersAt??=now();
  if(/^[A-Za-z0-9_-]{1,128}$/.test(gatewayId??'')){gatewayRequestId=gatewayId;gatewayHttpError=gatewayError(errorCode);}
 },observe(chunk){
  if(chunk.length){firstBodyAt??=now();lastBodyAt=now();}
  responseHash.update(chunk);responseBytes+=chunk.length;
  const text=decoder.decode(chunk,{stream:true});
  if(jsonBody.length<MAX)jsonBody+=text.slice(0,MAX-jsonBody.length);else observationTruncated=true;
  pending+=text;
  for(let newline;(newline=pending.indexOf('\n'))!==-1;){line(pending.slice(0,newline).replace(/\r$/,''));pending=pending.slice(newline+1);}
  if(pending.length>MAX){pending='';discardFrame=true;observationTruncated=true;}
 },finish({httpStatus=null,providerRequestId=null,errorType=null,errorCode=null,providerDispatched=httpStatus!==null?true:null}={}){
  if(finished)return;finished=true;
  decoder.decode();
  if(!usage)try{value(JSON.parse(jsonBody));}catch{}
  if(streamError&&!errorType){errorType='UpstreamStreamError';errorCode=streamError;}
  errorCode??=gatewayHttpError;
  const ended=now();
  const phase=headersAt===null?'BEFORE_HEADERS':firstBodyAt===null?'WAITING_FOR_BODY':'RESPONSE_BODY';
  const relative=at=>at===null?null:Math.max(0,at-started);
  const code=errorCode==='UPSTREAM_CONNECTION_FAILURE'&&headersAt!==null
   ?'UPSTREAM_RESPONSE_STREAM_INTERRUPTED':errorCode;
  const measured=providerUsage(usage);
  const usageStatus=measured?.totalTokens!=null?'COMPLETE'
   :[measured?.inputTokens,measured?.outputTokens,measured?.cacheReadTokens,measured?.cacheWriteTokens,measured?.reasoningTokens].some(count=>count!=null)?'PARTIAL':'UNKNOWN';
  emit('model.request.completed',{...reasoning,requestId,model,providerModel,providerRequestId,usageStatus,
   startedAt:new Date(started).toISOString(),finishedAt:new Date(ended).toISOString(),durationMs:Math.max(0,ended-started),
   httpStatus,status:errorType||httpStatus===null||httpStatus>=400?'FAILED':'SUCCEEDED',errorType,
   ...(gatewayRequestId?{gatewayRequestId}:{}),
   ...(code?{errorCode:code,failurePhase:phase,transportErrorCode:errorCode}:{}),
   responseTiming:{headersMs:relative(headersAt),firstBodyMs:relative(firstBodyAt),lastBodyMs:relative(lastBodyAt),
    firstSseDataMs:relative(firstDataAt),lastSseDataMs:relative(lastDataAt),firstModelDeltaMs:relative(firstModelDeltaAt),
    idleAfterLastBodyMs:lastBodyAt===null?null:Math.max(0,ended-lastBodyAt)},
   providerDispatched,usage:measured,responseBytes,
   responseSha256:responseHash.digest('hex'),requestSha256,requestContext,observationTruncated,source:'PROVIDER_HTTP_OBSERVATION'});
 }};
}
