import {createHash} from 'node:crypto';

// Projections contain observations, never reconstructed Agent state or reasoning.
const hidden=/^(authorization|password|apiKey|apiToken|brokerToken|secret|signingKey|workerContract|contract|configuration|runtimeContext|headers|reasoning|thinking|inputData|records|rows|reviewedArtifact|currentSource|validSource)$/i;
export function safe(value,secrets=[],depth=0){
 if(depth>12)return '[depth limited]';
 if(typeof value==='string'){
  let text=value;for(const s of secrets.filter(Boolean))text=text.split(s).join('[REDACTED]');
  return text.replace(/Bearer\s+[^\s"']+/gi,'Bearer [REDACTED]').replace(/\b(?:sk-|sk_)[a-zA-Z0-9_-]{16,}/g,'[REDACTED]').slice(0,32000);
 }
 if(Array.isArray(value))return value.slice(0,1000).map(v=>safe(v,secrets,depth+1));
 if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).filter(([k])=>!hidden.test(k)).map(([k,v])=>[k,safe(v,secrets,depth+1)]));
 return value??null;
}
export const hash=text=>createHash('sha256').update(text).digest('hex');
export function projectEvent(event,source,sessionId,secrets=[]){
 let type=event.eventType??event.type,p=event.payload??{},native=p;
 if(type==='native.event'&&p.eventType){type=p.eventType;p=p.payload??{};native=p;}
 if(type==='native.event'){
  if(p.type==='session_snapshot'||p.type==='chunk'&&p.payload?.stream==='agent')return null;
  const a=p.payload?.event;
  if(a?.contentType==='reasoning'||a?.contentType==='thinking')return null;
  if(a?.contentType==='tool')return null; // separately persisted tool.* events are the bounded public projection
  if(a)native={type:a.type,contentType:a.contentType,...a};
 }
 if(type==='model.request')p={modelCall:p.modelCall,model:p.model,requestHash:hash(JSON.stringify(p)),messageCount:p.messages?.length,source:'Runtime model broker'};
 else if(type==='model.receipt')p={modelInfo:p.modelInfo,usage:p.usage,usageStatus:p.usageStatus,error:p.error,finishReason:p.finishReason,providerRequestId:p.providerRequestId,modelCall:p.modelCall};
 else if(type==='task.input')p={message:'Runtime supplied the frozen instruction and authorized input inventory',instructionHash:hash(p.text??'')};
 else if(type==='delivery')p={files:(p.files??[]).map(f=>({fileId:f.fileId,fileName:f.fileName,nativePath:f.nativePath,bytes:f.bytes,sha256:f.sha256})),skills:(p.skills??[]).map(s=>({skillId:s.skillId,name:s.name,version:s.version,deliveredHash:s.deliveredHash,status:'STAGED'}))};
 else if(type==='mcp.receipt')p={name:p.name,nativeContext:p.nativeContext,receipt:receipt(p.receipt)};
 else if(type==='mcp.request')p={name:p.name,nativeContext:p.nativeContext,jsonRpcId:p.jsonRpcId,argumentsHash:hash(JSON.stringify(p.arguments??{}))};
 else p=native;
 // Tool input/output can contain complete financial tables. Explicit source viewing is separate.
 if(type?.startsWith('tool.'))p={toolName:p.toolName,toolCallId:p.toolCallId,inputHash:hash(JSON.stringify(p.input??null)),outputHash:hash(JSON.stringify(p.output??null)),status:p.status,durationMs:p.durationMs,error:p.error,errorMessage:p.errorMessage,message:p.message,
  command:sessionId.startsWith('native:')?p.input?.command??p.input?.commands:undefined,
  testOutput:sessionId.startsWith('native:')&&/execute|terminal|run_commands/.test(p.toolName??'')?p.output:undefined,
  failureSummary:type==='tool.failed'?String(p.output??'').slice(0,2000):undefined,
  outputSummary:typeof p.output==='string'?(/<command-name>([^<]+)<\/command-name>/.exec(p.output)?.[1]??'Result retained in private Native journal'):null,
  file:p.input?.path??p.input?.file_path??p.input?.filePath,patchAttempt:p.toolName==='apply_patch'};
 if(!type)return null;
 return {id:source+':'+event.eventId,source,sourceEventId:event.eventId,timestamp:event.timestamp,sessionId,type,payload:safe(p,secrets)};
}
export function receipt(r={}){
 if(!r)return null;
 return Object.fromEntries(['operationId','status','executorStopped','exitCode','startedAt','finishedAt','codeSha256','resultDatasetRef','validationRecordRef','evidenceBundleRef','validationStatus','businessValidationPassed','resultAvailable','error','errorMessage','executionFeedback','nativeContext'].filter(k=>r[k]!==undefined).map(k=>[k,r[k]]));
}
export function parseCursor(value=''){
 if(!value)return {native:0,business:0};
 const m=/^n(\d+)b(\d+)$/.exec(value);if(!m)throw Object.assign(Error('Invalid durable event cursor'),{status:400});
 const cursor={native:Number(m[1]),business:Number(m[2])};if(!Object.values(cursor).every(Number.isSafeInteger))throw Object.assign(Error('Invalid cursor range'),{status:400});return cursor;
}
export const cursorId=c=>'n'+c.native+'b'+c.business;
