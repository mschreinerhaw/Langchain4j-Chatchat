import {createHash} from 'node:crypto';
const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
export const inputHash=value=>createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
export function nativeContext(meta,run,name,args) {
 if(!meta||meta.nativeSessionId!==run.nativeAgentSessionId||meta.epoch!==run.epoch||typeof meta.nativeToolCallId!=='string'||!meta.nativeToolCallId)throw Error('Native operation context mismatch');
 const approval=run.approvedCalls.get(meta.nativeToolCallId);
 if(!approval||approval.toolName!=='runtime__'+name||approval.inputHash!==inputHash(args))throw Error('Native invocation has no matching authorized permission');
 return {sessionId:meta.nativeSessionId,toolCallId:meta.nativeToolCallId,epoch:meta.epoch};
}
export function observeNativeSession(run,event) {
 if(event.eventType==='native.event'&&event.payload?.type==='session_snapshot'){
  const id=event.payload.payload?.sessionId;
  if(typeof id==='string'&&id)run.nativeAgentSessionId=id;
 }
}
