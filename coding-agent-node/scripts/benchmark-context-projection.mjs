/** Installed SDK payload comparison, NOT a provider token/latency benchmark. */
import { sdkPrepareTurn } from '../src/sdk-adapter.js';
import { measureRequestContext } from '../src/request-context.js';
import { createHash } from 'node:crypto';
const source = Array.from({length:1200},(_,i)=>`value_${i} = ${i} # retained exact program`).join('\n');
const codeHash = createHash('sha256').update(source).digest('hex');
const code = { source, version: { version: 1, codeHash }, lastValidatedHash: null };
const messages = [{role:'user',content:[{type:'text',text:'Repair only the diagnosed line using the authorized immutable input.'}]},
 {role:'assistant',content:[{type:'tool-call',toolCallId:'execute',toolName:'python_execute',input:{source}}]},
 {role:'tool',content:[{type:'tool-result',toolCallId:'execute',toolName:'python_execute',output:{status:'FAILED',codeHash,diagnosticCode:'SYNTAX_ERROR'}}]}];
const context={messages,tools:[{name:'code_read',inputSchema:{type:'object'}}],systemPrompt:'Use exact code versions; no added data authority.',
 model:{info:{maxInputTokens:128000}},signal:new AbortController().signal};
const measurements={};
for(const mode of ['before','after']){
 const prepare=sdkPrepareTurn('fixed-payload',()=>code,{codeReferenceEnabled:mode==='after',statefulCompactionEnabled:mode==='after'});
 const result=await prepare(context);
 const request={systemPrompt:context.systemPrompt,tools:context.tools,messages:result?.messages??messages};
 measurements[mode]=measureRequestContext(request);
 if(!request.messages.some(m=>m.content.some(p=>p.type==='tool-call'&&p.input?.source===source)))throw Error('Canonical historical source unexpectedly lost');
 if(mode==='after'&&JSON.stringify(request).split('Latest program binding').length!==2)throw Error('Version binding missing');
}
console.log(JSON.stringify({scope:'CONTROLLED_INSTALLED_SDK_PAYLOAD_FIXTURE',providerCalled:false,actualTokenSavings:null,
 note:'Identical synthetic canonical messages; latest exact source remains in original tool call. Only redundant protected full-source injection is replaced by an authorized code_read binding. No provider speed or financial correctness claim.',
 fixtureSha256:createHash('sha256').update(JSON.stringify(messages)).digest('hex'),measurements,
 requestByteReductionRatio:1-measurements.after.requestBytes/measurements.before.requestBytes},null,2));
