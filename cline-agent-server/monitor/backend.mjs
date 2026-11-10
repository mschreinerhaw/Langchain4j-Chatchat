import {readdir,readFile,stat,realpath} from 'node:fs/promises';
import {join,resolve,sep} from 'node:path';
import {projectEvent,safe,receipt,hash,nativeModelObservations} from './projection.mjs';
import {randomUUID} from 'node:crypto';
import {executionMetrics} from './metrics.mjs';

const fail=(message,status=400)=>Object.assign(Error(message),{status});
const id=x=>typeof x==='string'&&/^[a-zA-Z0-9_.:-]{1,200}$/.test(x);
export class Backend {
 constructor(config){this.config=config;this.cache=new Map();this.dispatches=new Map();this.secrets=[config.native?.token];}
 async api(origin,path,token,body,options={}){
  const response=await fetch(origin+path,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+token,'content-type':'application/json',...options.headers},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(options.timeout??30000),redirect:'error'});
  const value=await response.json();if(!response.ok||value.code&&value.code!==200)throw fail(value.message??value.msg??value.code??'Backend rejected request',response.ok?400:response.status);
  return value.data??value;
 }
 runtime(user,path,body,options){return this.api(this.config.runtimeUrl,path,user.token,body,options);}
 native(path,body,options){if(!this.config.native)throw fail('Native direct mode is not configured',503);return this.api(this.config.native.url,path,this.config.native.token,body,options);}
 async json(file){try{return JSON.parse(await readFile(file,'utf8'));}catch(e){if(e.code==='ENOENT')return null;throw e;}}
 async records(root,file){if(!root)return [];const names=await readdir(root);const result=[];for(const name of names.filter(id)){const row=await this.json(join(root,name,file));if(row){const info=await stat(join(root,name,file));result.push({...row,lastStatePersistedAt:info.mtime.toISOString()});}}return result;}
 async runs(user){return (await this.records(this.config.businessState,'run.json')).filter(r=>r.scope?.tenantId===user.tenantId&&r.scope?.userId===user.userId);}
 async sessions(user){
  const runs=await this.runs(user),native=this.config.native?await this.records(this.config.native.stateDir,'session.json'):[];
  return [...native.map(s=>({id:'native:'+s.id,mode:'native',sessionId:s.id,nativeSessionId:s.nativeSessionId,status:s.status,workspaceRef:s.workspaceRef,authority:'NATIVE_SESSION',updatedAt:s.lastStatePersistedAt})),...runs.map(r=>({id:'runtime:'+r.workerId,mode:'runtime',sessionId:r.nativeSessionId,nativeSessionId:r.nativeAgentSessionId,taskId:r.taskId,runId:r.scope.runId,requestId:r.scope.requestId,status:r.status,authority:'NATIVE_OBSERVATION',runtimeStatus:null,updatedAt:r.lastStatePersistedAt}))];
 }
 async context(user,key){
  if(key.startsWith('native:')){const sid=key.slice(7);if(!id(sid))throw fail('Invalid session');const state=await this.json(join(this.config.native?.stateDir??'',sid,'session.json'));if(!state)throw fail('Session not found',404);return {mode:'native',state,sid};}
  if(key.startsWith('runtime:')){const r=(await this.runs(user)).find(r=>r.workerId===key.slice(8));if(!r)throw fail('Task not found',404);return {mode:'runtime',state:r,sid:r.nativeSessionId};}
  throw fail('Unknown session mode');
 }
 async lines(path){
  try{
   const info=await stat(path),cached=this.cache.get(path);if(cached?.mtime===info.mtimeMs&&cached?.size===info.size)return cached.lines;
   if(info.size>128*1024*1024)throw fail('Journal exceeds monitor read limit; inspect private evidence',413);
   const text=await readFile(path,'utf8');const rows=text.split('\n');rows.pop();const lines=rows.filter(Boolean).map(JSON.parse);this.cache.set(path,{mtime:info.mtimeMs,size:info.size,lines});if(this.cache.size>32)this.cache.delete(this.cache.keys().next().value);return lines;
  }catch(e){if(e.code==='ENOENT')return [];throw e;}
 }
 async events(user,key,cursor){
  const c=await this.context(user,key),s=c.state,events=[];
  const paths=c.mode==='native'?[['native',join(this.config.native.stateDir,s.id,'events.jsonl')]]:[['business',join(this.config.businessState,s.workerId,'events.jsonl')]];
  // Business journal already persists the official Native events under its own durable ID.
  const next={...cursor};for(const [source,path] of paths)for(const e of await this.lines(path)){
   if(Number(e.eventId)<=cursor[source])continue;next[source]=Math.max(next[source],Number(e.eventId));const p=projectEvent(e,source,key,[...this.secrets,user.token,s.apiToken,s.brokerToken]);if(p)events.push(p);
   if(events.length>=300)break;
  }
  return {events,cursor:next};
 }
 root(r){return '/api/v1/agent/runtime/runs/'+encodeURIComponent(r.scope.runId)+'/coding';}
 async detail(user,key){
  const c=await this.context(user,key),s=c.state;
  if(c.mode==='native'){
   let live;try{live=await this.native('/v1/sessions/'+s.id+'/status');}catch(e){live={status:s.status,observationError:e.message};}
   const events=(await this.lines(join(this.config.native.stateDir,s.id,'events.jsonl'))).map(e=>projectEvent(e,'native',key,this.secrets)).filter(Boolean);
   live.modelReceipts=nativeModelObservations(events,this.config.native.model);
   live.metrics=executionMetrics({modelReceipts:live.modelReceipts,events,startedAt:s.createdAt,endedAt:s.endedAt});
   return safe({id:key,mode:'native',...live,authority:'NATIVE_SESSION',deliveryValidated:false,capabilities:{messages:['idle','turn_completed','awaiting_input'].includes(live.status),cancel:!['cancelling','cancelled','failed','recoverable'].includes(live.status),permissions:true},model:{model:this.config.native.model,usageStatus:'NATIVE_OBSERVATION_NOT_RUNTIME_BILLING'},workspaceRef:s.workspaceRef},[...this.secrets,user.token]);
  }
  let item,error;
  try{try{item=await this.runtime(user,this.root(s)+'/'+s.taskId);}catch(e){if(e.status!==404)throw e;const list=await this.runtime(user,this.root(s));item=list.tasks.find(t=>t.taskId===s.taskId);}}catch(e){error=e.message;}
  const feedbacks=(item?.executions??[]);
  const lastPythonStopped=s.lastReceipt?.executorStopped??null;
  const journal=await this.lines(join(this.config.businessState,s.workerId,'events.jsonl'));
  const ended=journal.findLast(e=>e.type==='native.event'&&e.payload?.eventType==='turn.completed');
  let archive;try{const page=await this.assets(user,key);archive=Array.isArray(page)?page:page.items??page.files;}catch{/* Archive unavailable: keep unknown. */}
  const metrics=executionMetrics({outcome:item?.outcome,modelReceipts:item?.modelReceipts,executions:feedbacks,events:journal.map(e=>projectEvent(e,'business',key,this.secrets)).filter(Boolean),assets:archive});
  return safe({metrics,id:key,mode:'runtime',sessionId:s.nativeSessionId,nativeSessionId:s.nativeAgentSessionId,taskId:s.taskId,runId:s.scope.runId,status:s.status,nativeTurnStatus:ended?'TURN_COMPLETED':'NO_CONFIRMED_TURN_COMPLETION',nativeTurnEventId:ended?.payload?.eventId,runtimeStatus:item?.outcome?.status??'UNKNOWN',authority:item?'RUNTIME_CONFIRMED':'RUNTIME_UNAVAILABLE',outcome:item?.outcome,modelReceipts:item?.modelReceipts??[],executions:feedbacks,lastPythonStopped,error:error??s.error,activeOperations:s.activeOperations??[],workerStopped:s.stopProof?.running===false,remoteStopConfirmed:s.remoteStopConfirmed===true,codeLifecycle:s.codeLifecycle,nativeCompletion:s.nativeCompletion,capabilities:{messages:false,cancel:item?.canCancel===true,reconcile:item?.canResume===true,permissions:false},allowedTools:item?.allowedTools,objective:item?.objective},[...this.secrets,user.token,s.apiToken,s.brokerToken]);
 }
 async create(user,b){
  if(b.mode==='native'){
   if(!this.config.native.workspaces.includes(b.workspaceRef))throw fail('Unknown authorized workspace');
   const result=await this.native('/v1/sessions',{workspaceRef:b.workspaceRef,editTool:'apply_patch'});return {id:'native:'+result.sessionId,...result};
  }
  if(b.mode!=='runtime'||typeof b.instruction!=='string'||!b.instruction.trim()||b.instruction.length>8000)throw fail('Valid Runtime instruction required');
  if(!Array.isArray(b.fileIds)||!b.fileIds.length||b.fileIds.length>8||!b.fileIds.every(id))throw fail('Select 1–8 authorized input assets');
  if(!this.config.models.includes(b.model))throw fail('Select an authorized model');
  if(b.validationProfile==='csv-count-sum-v1'){
   if(b.fileIds.length!==1||typeof b.valueField!=='string'||!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(b.valueField))throw fail('Select one CSV and its exact numeric field');
   const requestId=b.commandId??randomUUID();if(!id(requestId))throw fail('Stable request ID required');
   const requestHash=hash(JSON.stringify({files:b.fileIds,instruction:b.instruction,model:b.model,profile:b.validationProfile,valueField:b.valueField}));
   const existing=this.dispatches.get(requestId);if(existing){if(existing.userId!==user.userId)throw fail('Dispatch not owned',403);if(existing.requestHash!==requestHash)throw fail('Changed content under an existing request ID; create a new request',409);return {requestId,status:existing.status};}
   const observation={userId:user.userId,requestHash,status:'REQUEST_SENT',startedAt:new Date().toISOString()};this.dispatches.set(requestId,observation);
   if(this.dispatches.size>128)this.dispatches.delete(this.dispatches.keys().next().value);
   this.runtime(user,'/api/v1/data-science/user-assets/analyses',{requestId,fileIds:b.fileIds,contextIds:[],instruction:b.instruction,modelName:b.model,validationProfile:b.validationProfile,valueField:b.valueField},{timeout:1800000})
    .then(r=>{observation.status='RESPONSE_RECEIVED';observation.runtimeStatus=r.status;observation.taskId=r.taskId;}).catch(e=>{observation.status='RESPONSE_ERROR';observation.message=e.message;});
   return {requestId,status:'REQUEST_SENT',authority:'EXISTING_RUNTIME_ANALYSIS_ENTRY'};
  }
  const created=await this.runtime(user,'/api/v1/data-science/explorations',{title:'Native Monitor '+new Date().toISOString()});
  const prepared=await this.runtime(user,'/api/v1/data-science/explorations/'+created.id+'/nodes',{version:created.version,parentId:'',fileIds:b.fileIds,question:b.instruction,modelName:b.model});
  const node=prepared.nodes.at(-1),path='/api/v1/data-science/explorations/'+created.id+'/nodes/'+node.id;
  // Existing Runtime persists dispatch intent. Page closure does not abort this request.
  this.runtime(user,path+'/execute',{}, {timeout:1800000}).catch(()=>{});
  return {workspaceId:created.id,nodeId:node.id,status:'DISPATCHED',authority:'RUNTIME_EXPLORATION',message:'Observe existing Runtime task; this response is not task completion'};
 }
 async control(user,key,action,b){
  const c=await this.context(user,key),s=c.state;
  if(c.mode==='native'){
   if(action==='messages')return this.native('/v1/sessions/'+s.id+'/messages',{text:b.text,...(b.interactionId?{interactionId:b.interactionId}:{})},{headers:{'idempotency-key':b.commandId}});
   if(action==='cancel')return this.native('/v1/sessions/'+s.id+'/cancel',{});
   if(action==='permission'){
    const live=await this.native('/v1/sessions/'+s.id+'/status');if(!live.pendingPermissions?.some(p=>p.id===b.permissionId))throw fail('Permission is not pending in this session',409);
    return this.native('/v1/permissions/'+encodeURIComponent(b.permissionId)+'/respond',{decision:b.decision,reason:'Authenticated Native Monitor operator'});
   }
  }else if(['cancel','reconcile'].includes(action))return this.runtime(user,this.root(s)+'/'+s.taskId+'/'+(action==='reconcile'?'resume':'cancel'),{});
  throw fail('Action not supported by this backend',409);
 }
 async assets(user,key){const c=await this.context(user,key);if(c.mode==='native')return {items:[],authority:'NATIVE_ONLY_NO_RUNTIME_ARCHIVE'};return this.runtime(user,'/api/v1/data-science/user-assets?limit=100&personalOnly=false&taskId='+encodeURIComponent(c.state.taskId));}
 async trace(user,key,operation,kind){
  const c=await this.context(user,key);if(c.mode!=='runtime'||!/^cline-[a-f0-9]{64}$/.test(operation)||!['code','stdout','stderr','timing'].includes(kind))throw fail('Invalid artifact binding');
  try{return await this.runtime(user,this.root(c.state)+'/'+c.state.taskId+'/'+operation+'/'+kind);}catch(e){if(e.status!==404)throw e;}
  // Direct asset-analysis tasks have no Agent parent. Their existing owner-scoped archive is authoritative.
  const page=await this.assets(user,key),files=Array.isArray(page)?page:page.items??page.files??[];
  const version=c.state.codeLifecycle?.versions?.find(v=>v.operationId===operation);
  const filename={stdout:'stdout.txt',stderr:'stderr.txt',timing:'receipt.json'}[kind];
  const file=files.find(f=>kind==='code'?version&&f.sha256===version.codeHash&&f.fileName?.endsWith('.py'):f.fileName===filename&&f.sourceRef?.includes(operation));
  if(!file)throw fail('No archived artifact bound to this operation',404);
  const response=await fetch(this.config.runtimeUrl+'/api/v1/data-science/user-assets/'+encodeURIComponent(file.id)+'/content',{headers:{authorization:'Bearer '+user.token},redirect:'error',signal:AbortSignal.timeout(30000)});
  if(!response.ok)throw fail('Runtime denied archived source/log read',response.status);
  const bytes=Buffer.from(await response.arrayBuffer());if(hash(bytes)!==file.sha256||bytes.length!==file.size)throw fail('Archived artifact integrity mismatch',409);
  const content=bytes.toString('utf8'),limit=kind==='code'?256000:8192;
  return {kind,operationId:operation,source:'RUNTIME_OWNER_ARCHIVE',fileId:file.id,sha256:file.sha256,totalBytes:bytes.length,content:safe(content,[user.token]).slice(0,limit),redacted:safe(content,[user.token])!==content,truncated:content.length>Math.min(limit,32000)};
 }
 async runtimeEvents(user,key,after=0){
  const c=await this.context(user,key);if(c.mode!=='runtime')return {events:[],nextSequence:0};
  if(!/^\d+$/.test(String(after)))throw fail('Invalid Runtime sequence');
  return safe(await this.runtime(user,this.root(c.state)+'/'+c.state.taskId+'/events?limit=128&afterSequence='+after),[user.token]);
 }
 async code(user,key){
  const c=await this.context(user,key);if(c.mode==='native')return this.native('/v1/sessions/'+c.sid+'/diff');
  const versions=c.state.codeLifecycle?.versions??[];
  // Only versions actually captured by Runtime are shown, not invented edits.
  const sources=[];for(const v of versions.slice(-2)){try{sources.push({version:v.version,codeHash:v.codeHash,operationId:v.operationId,...await this.trace(user,key,v.operationId,'code')});}catch(e){sources.push({version:v.version,codeHash:v.codeHash,error:e.message});}}
  return safe({versions,sources,evidence:'RUNTIME_CODE_LIFECYCLE_AND_OWNER_AUTHORIZED_ARTIFACTS',completeDiffAvailable:sources.length===2&&sources.every(s=>typeof s.content==='string'&&!s.truncated&&!s.redacted)},[...this.secrets,user.token]);
 }
}
