import test from 'node:test';
import assert from 'node:assert/strict';
import {modelReceipt,modelTransportErrorCode,providerUsage} from '../src/model-receipt.mjs';
test('records a safe transport failure without claiming a provider HTTP response',()=>{
 const error=new TypeError('fetch failed',{cause:Object.assign(new Error('private endpoint'),{code:'UND_ERR_CONNECT_TIMEOUT'})});
 const events=[];
 modelReceipt({model:'deepseek-v4-flash',request:'{}',emit:(_type,payload)=>events.push(payload)})
  .finish({errorType:error.name,errorCode:modelTransportErrorCode(error)});
 assert.equal(events[1].errorCode,'UPSTREAM_TIMEOUT');
 assert.equal(events[1].httpStatus,null);
 assert.equal(events[1].providerDispatched,null);
 assert.equal(events[1].usage,null);
 assert.ok(!JSON.stringify(events).includes('private endpoint'));
 assert.equal(modelTransportErrorCode({cause:{code:'ENOTFOUND'}}),'UPSTREAM_DNS_FAILURE');
});
test('provider input/cache/reasoning totals retain their inclusive semantics',()=>{
 const usage=providerUsage({prompt_tokens:100,completion_tokens:40,total_tokens:140,prompt_tokens_details:{cached_tokens:80},completion_tokens_details:{reasoning_tokens:12}});
 assert.equal(usage.inputTokens,100);assert.equal(usage.outputTokens,40);assert.equal(usage.totalTokens,140);assert.equal(usage.cacheReadTokens,80);assert.equal(usage.reasoningTokens,12);
 const anthropic=providerUsage({input_tokens:10,cache_read_input_tokens:80,cache_creation_input_tokens:5,output_tokens:20});
 assert.equal(anthropic.inputTokens,95);assert.equal(anthropic.totalTokens,115);
});
test('classifies a reset after HTTP 200 and reports SSE timing independently of missing usage',()=>{
 let time=100;const events=[];
 const receipt=modelReceipt({model:'model',request:'{}',emit:(_t,p)=>events.push(p),now:()=>time});
 time=125;receipt.headers();
 time=148;receipt.observe(Buffer.from(': keepalive\n'));
 time=160;receipt.observe(Buffer.from('data: {"choices":[]}\n\n'));
 time=166;receipt.observe(Buffer.from('data: {"choices":[{"delta":{"content":"x"}}]}\n\n'));
 time=431;receipt.finish({httpStatus:200,errorType:'TypeError',errorCode:'UPSTREAM_CONNECTION_FAILURE'});
 const end=events.at(-1);
 assert.equal(end.status,'FAILED');
 assert.equal(end.errorCode,'UPSTREAM_RESPONSE_STREAM_INTERRUPTED');
 assert.equal(end.failurePhase,'RESPONSE_BODY');
 assert.equal(end.usage,null);
 assert.deepEqual(end.responseTiming,{headersMs:25,firstBodyMs:48,lastBodyMs:66,
  firstSseDataMs:60,lastSseDataMs:66,firstModelDeltaMs:66,idleAfterLastBodyMs:265});
});
test('split UTF-8/SSE frames record each actual attempt without counting text as tokens',()=>{
 const events=[],raw=Buffer.from('data: {"model":"real","choices":[{"delta":{"content":"真实内容"}}]}\n\ndata: {"usage":{"prompt_tokens":23,"completion_tokens":7,"total_tokens":30}}\n\ndata: [DONE]\n\n');
 const receipt=modelReceipt({model:'configured',request:'{}',emit:(type,payload)=>events.push({type,payload})});
 for(let n=0;n<raw.length;n+=3)receipt.observe(raw.subarray(n,n+3));receipt.finish({httpStatus:200,providerRequestId:'provider-id'});receipt.finish({httpStatus:200});
 assert.equal(events.length,2);assert.equal(events[1].payload.usage.totalTokens,30);assert.equal(events[1].payload.providerModel,'real');
 assert.equal(events[1].payload.providerRequestId,'provider-id');
 const retry=[];const next=modelReceipt({model:'configured',request:'{}',emit:(t,p)=>retry.push(p)});next.observe(Buffer.from('{"error":"provider failed"}'));next.finish({httpStatus:500});
 assert.notEqual(next.requestId,receipt.requestId);assert.equal(retry.at(-1).usage,null);assert.equal(retry.at(-1).status,'FAILED');
});
test('missing/invalid usage remains unknown, including failed requests',()=>{
 assert.equal(providerUsage(null),null);assert.equal(providerUsage({prompt_tokens:-1,completion_tokens:NaN}).totalTokens,null);
 const events=[];const receipt=modelReceipt({model:'real',request:'{}',emit:(_t,p)=>events.push(p)});receipt.observe(Buffer.from('{"choices":[{"message":{"content":"not a token count"}}]}'));receipt.finish({httpStatus:200});
 assert.equal(events.at(-1).usage,null);assert.equal(events.at(-1).status,'SUCCEEDED');
});

test('multiline SSE counts complete provider frames once and ignores a truncated usage frame',()=>{
 const events=[];
 const receipt=modelReceipt({model:'test',request:'{}',emit:(_type,payload)=>events.push(payload)});
 const frame='data: {"usage": {\r\ndata: "prompt_tokens": 12, "completion_tokens": 7}}\r\n\r\n';
 for(const char of frame+frame+'data: {"usage":{"total_tokens":999}}')receipt.observe(Buffer.from(char));
 receipt.finish({httpStatus:200,errorType:'TypeError',errorCode:'UPSTREAM_CONNECTION_FAILURE'});
 receipt.finish({httpStatus:200});
 assert.equal(events.length,2);
 assert.equal(events[1].status,'FAILED');
 assert.equal(events[1].usageStatus,'COMPLETE');
 assert.equal(events[1].usage.totalTokens,19);
 const partial=modelReceipt({model:'test',request:'{}',emit:(_type,payload)=>events.push(payload)});
 partial.observe(Buffer.from('{"usage":{"prompt_tokens":0}}'));
 partial.finish({httpStatus:200});
 assert.equal(events.at(-1).usageStatus,'PARTIAL');
 assert.equal(events.at(-1).usage.inputTokens,0);
 assert.equal(events.at(-1).usage.totalTokens,null);
});

test('request context measures both supported provider formats without retaining content',()=>{
 const messages=[{role:'developer',content:'policy'},{role:'user',content:'old question'},
  {role:'assistant',content:'earlier answer'},{role:'tool',content:'result'},
  {role:'user',content:[{type:'text',text:'now'}]}];
 for (const body of [
  {messages,tools:[{type:'function',function:{name:'search'}}]},
  {instructions:'policy',input:messages.slice(1).map(item=>item.role==='tool'
   ?{type:'function_call_output',output:item.content}:item),tools:[{type:'function',name:'search'}]}
 ]) {
  const events=[],request=JSON.stringify(body);
  const receipt=modelReceipt({model:'test',request,body,emit:(_type,payload)=>events.push(payload)});
  receipt.finish({httpStatus:502});
  const shape=events.at(-1).requestContext;
  assert.deepEqual(shape,events[0].requestContext);
  assert.equal(shape.systemChars,6);
  assert.equal(shape.userChars,3);
  assert.equal(shape.historyChars,26);
  assert.equal(shape.toolResultChars,6);
  assert.equal(shape.toolsChars,JSON.stringify(body.tools).length);
  assert.equal(shape.skillsChars,null);
  assert.equal(shape.evidenceChars,null);
  assert.equal(shape.requestBytes,Buffer.byteLength(request));
  assert.doesNotMatch(JSON.stringify(events),/old question|earlier answer|policy/);
 }
});
