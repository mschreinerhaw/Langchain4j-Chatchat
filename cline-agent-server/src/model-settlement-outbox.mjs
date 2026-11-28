import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

function persist(dir, requestId, value) {
 mkdirSync(dir,{recursive:true,mode:0o700});
 const file=join(dir,requestId+'.json'),temporary=file+'.next';
 writeFileSync(temporary,JSON.stringify(value),{mode:0o600,flush:true});
 renameSync(temporary,file);
 if(process.platform!=='win32'){
  const fd=openSync(dir,'r');
  try{fsyncSync(fd);}finally{closeSync(fd);}
 }
}

// A stopped broker cannot infer provider usage. It can prove a request was
// never dispatched only if that request never crossed the durable send fence.
export class ModelSettlementOutbox {
 #pending=new Map();
 #busy=false;
 constructor({send,onPending=()=>{},now=Date.now}) {
  this.send=send;
  this.onPending=onPending;
  this.now=now;
 }

 restore(record,dir){
  if(!existsSync(dir))return;
  for(const name of readdirSync(dir)){
   if(!/^[0-9a-f-]{36}\.json$/.test(name))continue;
   const item=JSON.parse(readFileSync(join(dir,name),'utf8'));
   if(name!==item.requestId+'.json'||!['ADMITTING','DISPATCHING','COMPLETE'].includes(item.phase))
    throw Error('Invalid model settlement checkpoint');
   const entry={record,dir,item};
   this.#pending.set(item.requestId,entry);
   this.abandon(entry);
   if(entry.item.phase==='COMPLETE')entry.due=this.now();
  }
 }

 begin(record,dir,requestId){
  if(!/^[0-9a-f-]{36}$/.test(requestId)||this.#pending.has(requestId))throw Error('Invalid model reservation identity');
  const item={requestId,phase:'ADMITTING'};
  persist(dir,requestId,item);
  const entry={record,dir,item};
  this.#pending.set(requestId,entry);
  return entry;
 }

 dispatch(entry){this.#update(entry,{phase:'DISPATCHING'});}

 forget(entry){
  rmSync(join(entry.dir,entry.item.requestId+'.json'),{force:true});
  this.#pending.delete(entry.item.requestId);
 }

 complete(entry,{providerTokens,neverDispatched}){
  this.#update(entry,{phase:'COMPLETE',providerTokens,neverDispatched});
  entry.due=this.now();
 }

 abandon(entry){
  if(entry.item.phase!=='COMPLETE')
   this.complete(entry,{providerTokens:null,neverDispatched:entry.item.phase==='ADMITTING'});
 }

 #update(entry,value){
  const item={...entry.item,...value};
  persist(entry.dir,item.requestId,item);
  entry.item=item;
 }

 async flush(){
  if(this.#busy)return;
  this.#busy=true;
  try{
  const ready=[];
  for(const entry of this.#pending.values()){
   if(entry.due===undefined||entry.due>this.now()||entry.sending)continue;
   entry.sending=true;
   ready.push(entry);
   if(ready.length===8)break;
  }
  await Promise.all(ready.map(async entry=>{
   const {requestId,providerTokens,neverDispatched}=entry.item;
   try{
    await this.send(entry.record,{requestId,providerTokens,neverDispatched});
    this.forget(entry);
   }catch{
    const first=!entry.attempts;
    entry.attempts=(entry.attempts??0)+1;
    entry.due=this.now()+Math.min(60000,1000*2**Math.min(entry.attempts-1,6));
    if(first)this.onPending(entry.record,requestId);
   }finally{entry.sending=false;}
  }));
  }finally{this.#busy=false;}
 }
}
