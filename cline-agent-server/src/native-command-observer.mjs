// Loaded before the pinned official Core. Observes existing child processes;
// never changes their arguments, streams, callbacks, exit handling or retries.
import childProcess from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {randomUUID} from 'node:crypto';
const observed=new WeakSet(),MAX=64*1024*1024;
const send=event=>{try{if(process.connected)process.send({kind:'host.command',corePid:process.pid,event},()=>{});}catch{}};
function attach(child,command,options){
 if(!child||observed.has(child))return child;observed.add(child);
 const recordId=randomUUID(),started=Date.now(),clock=process.hrtime.bigint();let complete=false;
 const capture={stdout:0,stderr:0},bytes={stdout:0,stderr:0};
 send({type:'started',recordId,pid:child.pid??null,command,cwd:options?.cwd?.toString()??process.cwd(),startedAt:new Date(started).toISOString()});
 for(const name of ['stdout','stderr'])if(child[name]){
  const stream=child[name],emit=stream.emit;
  stream.emit=function(type,...args){
   // Observe bytes the original stream emits; attaching a data listener would
   // change flowing/readable mode and can change official tool behavior.
   if(type==='data')try{
    const value=Buffer.isBuffer(args[0])?args[0]:Buffer.from(String(args[0]));bytes[name]+=value.length;
    const keep=Math.min(value.length,MAX-capture[name]);if(keep>0){send({type:'data',recordId,stream:name,data:value.subarray(0,keep)});capture[name]+=keep;}
   }catch{}
   return Reflect.apply(emit,this,[type,...args]);
  };
 }
 const emit=child.emit;
 child.emit=function(type,...args){
  if(type==='close'&&!complete){complete=true;send({type:'completed',recordId,pid:child.pid??null,exitCode:typeof args[0]==='number'?args[0]:null,signal:args[1]??null,
   finishedAt:new Date().toISOString(),durationMs:Number(process.hrtime.bigint()-clock)/1e6,stdoutBytes:bytes.stdout,stderrBytes:bytes.stderr,
   stdoutCapturedBytes:capture.stdout,stderrCapturedBytes:capture.stderr,truncated:bytes.stdout>capture.stdout||bytes.stderr>capture.stderr});}
  if(type==='error')send({type:'error',recordId,error:{name:args[0]?.name,code:args[0]?.code,message:args[0]?.message}});
  return Reflect.apply(emit,this,[type,...args]);
 };
 return child;
}
for(const name of ['spawn','exec','execFile','fork']){
 const original=childProcess[name];childProcess[name]=function(...args){
  let options={};for(const value of args)if(value&&typeof value==='object'&&!Array.isArray(value)&&!Buffer.isBuffer(value))options=value;
  const command={method:name,executable:String(args[0]),args:Array.isArray(args[1])?args[1].map(String):[]};
  return attach(Reflect.apply(original,this,args),command,options);
 };
}
syncBuiltinESMExports();
