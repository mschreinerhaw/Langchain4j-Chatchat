import {resolveRuntimeModel} from './runtime-model.mjs';
import {stageNativeSkills,stageNativeDownload,runtimeMcpReply,nativeSkillDiscoveryContext} from './native-capability-files.mjs';
import http from 'node:http';
import {readFileSync,writeFileSync,mkdirSync,readdirSync,existsSync,renameSync,chownSync,chmodSync,cpSync,unlinkSync,lstatSync} from 'node:fs';
import {join} from 'node:path';
import {randomUUID,randomBytes,timingSafeEqual} from 'node:crypto';
import {verifyContract,authorizeSession,contractHash,holdsCapacity,signContract} from './task-contract.mjs';
import {DockerEngine,workerContainerSpec} from './docker-engine.mjs';
import {WorkerExecutorClient} from './worker-executor-client.mjs';
import {Journal} from './journal.mjs';
import {workerRuntime} from './worker-executor-policy.mjs';
import {validateS3Inputs,prepareS3Inputs,verifyStoppedDelivery,uploadS3Delivery,jsonHash} from './s3-task-transport.mjs';
import {modelReceipt} from './model-receipt.mjs';
import {verifyIssuedContract,issuerKey} from './task-issuer.mjs';
import {executionRecords,executionContent} from './command-evidence.mjs';

const cfg=JSON.parse(readFileSync(process.env.CLINE_GOVERNANCE_CONFIG,'utf8'));
if(!cfg.signingKey||cfg.signingKey.length<32||!cfg.workerImage?.startsWith('sha256:'))throw Error('Pinned image and private task issuer key required');
if(cfg.executor&&cfg.dockerSocket)throw Error('Executor Gateway must not configure a Docker Socket');
const executor=cfg.executor?new WorkerExecutorClient(cfg.executor):null;
if(cfg.s3&&(!executor||cfg.workerRuntime!=='cline-python311'))throw Error('S3 transport requires the restricted Python Executor');
const docker=executor?null:new DockerEngine(cfg.dockerSocket),records=new Map(),brokers=new Map(),streams=new Map(),calls=new Map();
const bridgeRoot=r=>executor?join(cfg.workerRoot,r.workerId,'bridge'):join(cfg.stateRoot,'workers',r.workerId,'bridge');
const grant=r=>signContract(r.contract,cfg.signingKey);
const inspectWorker=r=>executor?executor.inspect(grant(r),r.workerId):docker.inspect(r.containerId);
const stopWorker=r=>executor?executor.stop(grant(r),r.workerId):docker.stop(r.containerId);
mkdirSync(join(cfg.stateRoot,'workers'),{recursive:true,mode:0o700});
const audit=new Journal(join(cfg.stateRoot,'audit.jsonl'));
const providerJournals=new Map();
function providerJournal(r){
 if(!providerJournals.has(r.workerId))providerJournals.set(r.workerId,new Journal(join(cfg.stateRoot,'workers',r.workerId,'provider-events.jsonl')));
 return providerJournals.get(r.workerId);
}
const error=(message,status=400)=>Object.assign(Error(message),{status});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
function save(r){const dir=join(cfg.stateRoot,'workers',r.workerId);const {s3Busy,approvalBusy,...durable}=r;writeFileSync(join(dir,'registry.tmp'),JSON.stringify(durable),{mode:0o600});renameSync(join(dir,'registry.tmp'),join(dir,'registry.json'));}
function event(r,type,detail={}){
 const recorded=audit.append({type,sessionId:r?.contract.sessionId,tenantId:r?.contract.tenantId,workerId:r?.workerId,...detail});
 if(r&&(type.startsWith('model.request.')||type.startsWith('skills.')||type.startsWith('internet.')||type.startsWith('runtime.tool.')))providerJournal(r).append({eventType:type,taskId:r.contract.taskId,
  sessionId:r.contract.sessionId,tenantId:r.contract.tenantId,sourceEventId:recorded.eventId,...detail});
}
for(const id of readdirSync(join(cfg.stateRoot,'workers'))){const p=join(cfg.stateRoot,'workers',id,'registry.json');if(existsSync(p)){const r=JSON.parse(readFileSync(p));if(!['STOPPED','START_FAILED'].includes(r.workerState)){r.workerState='RECOVERABLE';r.taskState='UNKNOWN';r.revoked=true;save(r);event(r,'recovery.required',{reason:'No side-effect replay or operation reconciliation in stage 1'});}records.set(r.contract.sessionId,r);}}
for(const r of records.values())if(r.workerState==='RECOVERABLE'&&(r.containerId||executor&&r.provisioningAttempted)){try{const info=await inspectWorker(r);if(info.Config.Labels['cline.workerId']!==r.workerId)throw Error('Container ownership mismatch');r.stopProof=await stopWorker(r);save(r);}catch{event(r,'recovery.stop_unconfirmed');}}
function templateFiles(path,visit){const stat=lstatSync(path);if(stat.isSymbolicLink())throw Error('Workspace templates must not contain symlinks');visit?.(path);if(stat.isDirectory())for(const name of readdirSync(path))templateFiles(join(path,name),visit);}
async function body(req){const chunks=[];let count=0;for await(const b of req){count+=b.length;if(count>1048576)throw error('Request too large',413);chunks.push(b);}try{return JSON.parse(Buffer.concat(chunks).toString()||'{}');}catch{throw error('Invalid JSON');}}
function send(res,status,value){res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(value));}
function granted(req){
 const path=new URL(req.url,'http://control').pathname;
 const allowedExpired=/^\/v1\/sessions\/[A-Za-z0-9_-]+\/(?:status|events|observations|receipts|executions)$/.test(path)&&req.method==='GET'
  ||/^\/v1\/sessions\/[A-Za-z0-9_-]+\/(?:cancel|revoke|execution-content)$/.test(path)&&req.method==='POST';
 return verifyIssuedContract((req.headers.authorization??'').replace(/^Bearer /,''),cfg,{allowExpired:allowedExpired});
}
function live(r){return !r.revoked&&!existsSync(join(cfg.stateRoot,'revocations',r.contract.sessionId+'.json'))&&r.contract.expiresAt>Date.now()&&!['STOPPED','RECOVERABLE','START_FAILED'].includes(r.workerState);}
function refreshRevocation(r){
 const marker=join(cfg.stateRoot,'revocations',r.contract.sessionId+'.json');if(!existsSync(marker))return;
 const revoked=JSON.parse(readFileSync(marker,'utf8'));
 if(revoked.sessionId!==r.contract.sessionId||revoked.taskId!==r.contract.taskId)throw error('Recovery marker identity differs',409);
 if(revoked.stopProof?.allocationFenced===true&&revoked.stopProof.running===false){r.revoked=true;r.workerState='STOPPED';r.taskState='CANCELLED';r.stopProof=revoked.stopProof;save(r);}
}
function workerRequest(r,path,method='GET',value,headers={}){return new Promise((resolve,reject)=>{
 const req=http.request({socketPath:join(bridgeRoot(r),'worker.sock'),path,method,headers:{authorization:'Bearer '+r.apiToken,'content-type':'application/json',...headers}},resolve);
 req.setTimeout(95000,()=>req.destroy(error('Native control timeout',504)));req.on('error',reject);req.end(value===undefined?undefined:JSON.stringify(value));
});}
async function workerJSON(r,path,method,value){const response=await workerRequest(r,path,method,value);const chunks=[];for await(const b of response)chunks.push(b);const data=JSON.parse(Buffer.concat(chunks));if(response.statusCode>=400)throw error(data.code??'Native request rejected',response.statusCode);return {code:response.statusCode,data};}
function publicState(r,native){return {sessionId:r.contract.sessionId,runtimeTaskId:r.contract.taskId,workerId:r.workerId,workerState:r.workerState,taskState:r.taskState,revoked:r.revoked,stopProof:r.stopProof??null,remoteStopConfirmed:r.remoteStopConfirmed===true,native:native??null,...(r.contract.s3?{delivery:r.delivery??null,uploadReceipt:r.uploadReceipt??null,acceptance:r.acceptance??null}:{}),...(executor?{resourceEnforced:r.resourceEnforced??false,productionReady:false}:{})};}
async function operation(r,path,value={}){
 if(!cfg.operations)throw error('Operation authority unavailable',503);
 const response=await fetch(cfg.operations.url+path,{method:'POST',headers:{authorization:'Bearer '+cfg.operations.secret,'content-type':'application/json'},body:JSON.stringify({identity:r.contract,...value})});
 const result=await response.json();if(!response.ok)throw error(result.message??'Operation authority rejected',response.status===409?409:503);return result;
}
async function reconcileRemote(r,cancel=false){
 if(!cfg.operations||!r.operationSession)return false;
 if(cancel)await operation(r,'/invalidate');
 let operations=await operation(r,'/list');
 if(cancel)for(const o of operations)if(!['COMPLETED','FAILED','CANCELLED'].includes(o.state))await operation(r,'/cancel',{operationId:o.operationId});
 operations=await operation(r,'/list');r.remoteStopConfirmed=operations.every(o=>['COMPLETED','FAILED','CANCELLED'].includes(o.state)&&o.receipt?.stopConfirmed&&o.stopProofRef);r.operations=operations.map(o=>({operationId:o.operationId,state:o.state,stopProofRef:o.stopProofRef??null}));if(cancel&&r.remoteStopConfirmed&&r.stopProof)r.operationOutcome=await operation(r,'/finish',{localStopProof:r.stopProof});save(r);return r.remoteStopConfirmed;
}
async function stop(r,reason){
 if(r.workerState==='STOPPED'&&r.contract.s3){
  r.revoked=true;r.taskState='CANCELLED';for(const controller of calls.get(r.workerId)??[])controller.abort();save(r);event(r,'task.revoked',{reason});return;
 }
 if(['STOPPED','STOPPING'].includes(r.workerState))return;r.revoked=true;r.workerState='STOPPING';r.taskState='STOPPING';save(r);event(r,'worker.stopping',{reason});
 for(const response of streams.get(r.workerId)??[])response.end();for(const controller of calls.get(r.workerId)??[])controller.abort();
 try{if(r.nativeSessionId)await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/cancel','POST',{});}catch{}
 try{if(r.containerId||executor&&r.provisioningAttempted)r.stopProof=await stopWorker(r);r.workerState='STOPPED';r.taskState='CANCELLED_LOCAL_ONLY';}
 catch{r.workerState='RECOVERABLE';r.taskState='UNKNOWN';}
 if(cfg.operations){try{await reconcileRemote(r,true);r.taskState=r.remoteStopConfirmed?'CANCELLED_REMOTE_CONFIRMED':'UNKNOWN';}catch{r.remoteStopConfirmed=false;r.taskState='UNKNOWN';}if(!r.remoteStopConfirmed)r.workerState='RECOVERABLE';}
 brokers.get(r.workerId)?.close();save(r);event(r,'worker.stopped',{workerState:r.workerState,stopProof:r.stopProof??null,remoteStopConfirmed:r.remoteStopConfirmed===true});
}
async function broker(r,path){
 const server=http.createServer(async(req,res)=>{
  let observation,requestBody;
  try{
   const provided=Buffer.from(req.headers.authorization??''),expected=Buffer.from('Bearer '+r.brokerToken);
   if(provided.length!==expected.length||!timingSafeEqual(provided,expected))throw error('Broker authorization rejected',401);
   if(!live(r))throw error('Task lease inactive',403);
   const tenant=cfg.tenants[r.contract.tenantId];let url,headers,modelProfile;
   if(req.url==='/mcp'){
    if(!tenant.mcp)throw error('MCP not granted',403);
    if(req.method!=='POST')return send(res,405,{code:'STATELESS_MCP_POST_ONLY'});
    requestBody=await body(req);
    if(requestBody.method==='tools/call'){
     if(!tenant.mcp.tools.includes(requestBody.params?.name)||(tenant.mcp.authMode==='native-runtime'?!r.contract.runtimeTools?.includes(requestBody.params?.name):!r.contract.dataPermissions.includes(requestBody.params?.arguments?.datasetId)))throw error('Dataset or tool not granted',403);
     event(r,'mcp.authorized',{tool:requestBody.params.name,datasetId:requestBody.params.arguments.datasetId});
     if(cfg.operations){
      const context=requestBody.params?._meta?.runtimeNative;
      if(!context||context.epoch!==r.nativeEpoch)throw error('Native operation context required',409);
      const binding=await operation(r,'/resolve',{...context,toolName:requestBody.params.name,arguments:requestBody.params.arguments});
      const result=await operation(r,'/dispatch',{operationId:binding.operationId,epoch:context.epoch,rpc:requestBody});
      const actual={...result.mcpResponse,id:requestBody.id};return send(res,200,actual);
     }
    }else if(!['initialize','notifications/initialized','tools/list','ping'].includes(requestBody.method))throw error('MCP method not granted',403);
    url=tenant.mcp.url;headers={'Authorization':'Bearer '+(['task-grant','native-runtime'].includes(tenant.mcp.authMode)?signContract(r.contract,issuerKey(cfg,r.contract.tenantId)):tenant.mcp.token),'Accept':'application/json, text/event-stream','content-type':'application/json'};
    if(req.headers['mcp-protocol-version'])headers['mcp-protocol-version']=req.headers['mcp-protocol-version'];
   }else if(['/model/v1/chat/completions','/model/v1/responses'].includes(req.url)&&req.method==='POST'){
    requestBody=await body(req);const profile=(r.modelConnection??cfg.modelProfiles[r.contract.modelProfile]);modelProfile=profile;
    if(requestBody.model!==profile.model)throw error('Model outside task profile',403);
    url=profile.baseUrl.replace('{sessionId}',r.contract.sessionId).replace(/\/$/,'')+req.url.slice('/model/v1'.length);headers={'Authorization':'Bearer '+profile.apiKey,'content-type':'application/json'};
    event(r,'model.forwarded',{model:profile.model});
   }else throw error('Broker route not found',404);
   if(!live(r))throw error('Task lease inactive',403);
   const controller=new AbortController();if(!calls.has(r.workerId))calls.set(r.workerId,new Set());calls.get(r.workerId).add(controller);
   try{
    const request=JSON.stringify(requestBody);
    if(modelProfile)observation=modelReceipt({model:modelProfile.model,request,emit:(type,payload)=>event(r,type,payload)});
    const upstream=await fetch(url,{method:'POST',headers,body:request,signal:controller.signal,redirect:'error'});
    if(req.url==='/mcp'&&tenant.mcp.authMode==='native-runtime'){
     const chunks=[];let size=0;for await(const chunk of upstream.body??[]){size+=chunk.length;if(size>2*1024*1024){controller.abort();throw Error('Runtime MCP response byte budget exceeded');}chunks.push(chunk);}const bytes=Buffer.concat(chunks);if(!bytes.length){res.writeHead(upstream.status);return res.end();}if(!live(r))throw error('Task lease inactive',403);
     const value=runtimeMcpReply(bytes,upstream.status);
     if(requestBody.method==='tools/call'&&requestBody.params.name==='web_download'&&upstream.ok&&value.result&&!value.result.isError){
      const result=JSON.parse(value.result.content[0].text);value.result.content[0].text=JSON.stringify(await stageNativeDownload(join(cfg.workerRoot,r.workerId,'workspace'),result,{uid:workerRuntime(cfg).uid}));event(r,'internet.download.completed',JSON.parse(value.result.content[0].text));
     }
     if(requestBody.method==='tools/call')event(r,'runtime.tool.completed',{tool:requestBody.params.name,requestId:requestBody.id,isError:value.result?.isError??upstream.status>=400});
     return send(res,upstream.status,value);
    }
    res.writeHead(upstream.status,{'content-type':upstream.headers.get('content-type')??'application/json'});
    res.on('close',()=>controller.abort());
    try{
     for await(const chunk of upstream.body??[]){observation?.observe(chunk);if(!res.write(chunk))await new Promise(resolve=>res.once('drain',resolve));}
     observation?.finish({httpStatus:upstream.status,providerRequestId:upstream.headers.get('x-request-id')??upstream.headers.get('request-id')});res.end();
    }catch(e){observation?.finish({httpStatus:upstream.status,errorType:e.name??'UpstreamError'});throw e;}
   }finally{calls.get(r.workerId).delete(controller);}
  }catch(e){
   observation?.finish({errorType:e.name??'UpstreamError'});
   if(!res.headersSent&&req.url==='/mcp'&&requestBody?.method==='tools/call'){
    const code=[401,403,409].includes(e.status)?'RUNTIME_AUTHORIZATION_REJECTED':/integrity|hash/i.test(e.message)?'DOWNLOAD_INTEGRITY_MISMATCH':/symlink|path changed/i.test(e.message)?'DOWNLOAD_PATH_REJECTED':/byte budget/i.test(e.message)?'DOWNLOAD_RESOURCE_LIMIT':'RUNTIME_TOOL_UNAVAILABLE';
    event(r,'runtime.tool.failed',{tool:requestBody.params?.name,requestId:requestBody.id,code});
    send(res,e.status??200,{jsonrpc:'2.0',id:requestBody.id,result:{isError:true,content:[{type:'text',text:JSON.stringify({status:'ERROR',errorCode:code,guidance:'No verified file was delivered by this call. Inspect authorization or resource limits and choose a currently authorized alternative.'})}]}});
   }else if(!res.headersSent)send(res,e.status??502,{code:e.status?'BROKER_DENIED':'UPSTREAM_UNAVAILABLE'});else res.destroy();
  }
 });
 if(existsSync(path))unlinkSync(path);await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(path,resolve);});const runtime=workerRuntime(cfg),uid=runtime.supervisorUid??runtime.uid;chownSync(path,uid,uid);chmodSync(path,0o600);brokers.set(r.workerId,server);
}
async function allocate(r){
 const dir=join(cfg.stateRoot,'workers',r.workerId),tenant=cfg.tenants[r.contract.tenantId];
 try{
  r.workerState='STARTING';save(r);event(r,'worker.starting');
  if(r.contract.runtimeModel){r.modelConnection=await resolveRuntimeModel(r.contract,tenant.runtimeModelAuthority,signContract(r.contract,issuerKey(cfg,r.contract.tenantId)));if(r.revoked)throw Error('Allocation revoked');save(r);event(r,'model.bound',{model:r.contract.runtimeModel.logicalName,providerModel:r.modelConnection.model});}
  if(executor){
   if(r.revoked)throw Error('Allocation revoked');r.provisioningAttempted=true;save(r);
   const created=await executor.create(grant(r),{workerId:r.workerId,resourceProfile:'standard',apiToken:r.apiToken,brokerToken:r.brokerToken,editTool:r.editTool});
   r.containerId=created.Id;r.resourceEnforced=created.resources.enforced;save(r);
   if(r.contract.s3){
    const controller=new AbortController();if(!calls.has(r.workerId))calls.set(r.workerId,new Set());calls.get(r.workerId).add(controller);
    try{await prepareS3Inputs(join(cfg.workerRoot,r.workerId,'workspace'),r.contract,r.s3Inputs,cfg.s3,{signal:AbortSignal.any([controller.signal,AbortSignal.timeout(Math.max(1,r.contract.expiresAt-Date.now()))]),uid:workerRuntime(cfg).uid});}
    finally{calls.get(r.workerId).delete(controller);}
    delete r.s3Inputs;save(r);event(r,'s3.inputs.verified',{snapshotVersion:r.contract.deliveryBinding.inputSnapshotVersion});
   }
   if(workerRuntime(cfg).python){
    let packages=[];
    if(tenant.mcp?.authMode==='native-runtime'){
     const response=await fetch(tenant.mcp.url.replace(/\/mcp$/,'/skills'),{method:'POST',headers:{Authorization:'Bearer '+signContract(r.contract,issuerKey(cfg,r.contract.tenantId))},signal:AbortSignal.timeout(60000),redirect:'error'});
     if(!response.ok)throw Error('Authorized Skill package delivery failed');packages=(await response.json()).packages;
    }
    r.nativeSkillIndex=await stageNativeSkills(join(cfg.workerRoot,r.workerId,'workspace'),packages,(type,payload)=>event(r,type,payload));save(r);
   }
   await broker(r,join(bridgeRoot(r),'broker.sock'));
   if(r.revoked)throw Error('Allocation revoked');await executor.start(grant(r),r.workerId);
  }else{
  for(const name of ['workspace','state','bridge']){mkdirSync(join(dir,name),{recursive:true,mode:0o700});chownSync(join(dir,name),1000,1000);}
  templateFiles(tenant.workspaces[r.contract.workspaceRef]);cpSync(tenant.workspaces[r.contract.workspaceRef],join(dir,'workspace'),{recursive:true,dereference:false});templateFiles(join(dir,'workspace'),p=>chownSync(p,1000,1000));
  // Template ownership is prepared by the deployment owner; no host path comes from a client.
  const profile=(r.modelConnection??cfg.modelProfiles[r.contract.modelProfile]);
  const privateConfig={operationContext:!!cfg.operations,sourceRoot:'/opt/runtime-os/cline/runtime',distDir:'/opt/runtime-os/cline/runtime',stateDir:'/var/lib/runtime-os/coding-node',socketPath:'/run/runtime-os/coding-node/bridge/worker.sock',apiToken:r.apiToken,apiKey:r.brokerToken,model:profile.model,baseUrl:'http://127.0.0.1:18329/model/v1',workspaces:{project:'/workspace'},toolPermissions:r.contract.toolPermissions,
   ...(tenant.mcp?{mcpServers:{tenant_data:{type:'streamableHttp',url:'http://127.0.0.1:18329/mcp',headers:{Authorization:'Bearer '+r.brokerToken},disabled:false,autoApprove:[]}}}:{})};
  const configFile=join(dir,'config.json');writeFileSync(configFile,JSON.stringify(privateConfig),{mode:0o600});chownSync(configFile,1000,1000);
  await broker(r,join(dir,'bridge/broker.sock'));
  const index=r.slot;
  const spec=workerContainerSpec({image:cfg.workerImage,root:join(cfg.hostStateRoot,'workers',r.workerId),workerId:r.workerId,sessionId:r.contract.sessionId,tenantId:r.contract.tenantId,memoryBytes:cfg.memoryBytes??3221225472,cpuSet:cfg.cpuSets[index%cfg.cpuSets.length]});
  if(r.revoked)throw Error('Allocation revoked');r.provisioningAttempted=true;save(r);r.containerId=await docker.create('cline-p3-'+r.workerId,spec);save(r);if(r.revoked)throw Error('Allocation revoked');await docker.call('POST','/containers/'+r.containerId+'/start');
  }
  for(let n=0;n<180;n++){if(r.revoked)throw Error('Allocation revoked');try{await workerJSON(r,'/health/ready');break;}catch{if(n===179)throw Error('Worker API startup deadline');await delay(500);}}
  const created=await workerJSON(r,'/v1/sessions','POST',{workspaceRef:'project',editTool:r.editTool});r.nativeSessionId=created.data.sessionId;save(r);
  for(let n=0;n<200;n++){if(r.revoked)throw Error('Allocation revoked');const state=(await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/status')).data;if(state.status==='idle')break;if(state.status==='failed'||n===199)throw Error('Native startup failed');await delay(500);}
  if(cfg.operations){const native=(await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/status')).data;r.nativeEpoch=native.epoch;await operation(r,'/session',{epoch:native.epoch});r.operationSession=true;}
  if(r.revoked)throw Error('Allocation revoked');r.workerState='READY';r.taskState='READY';save(r);event(r,'worker.ready',{containerId:r.containerId});
 }catch(e){r.revoked=true;r.workerState='START_FAILED';r.taskState='FAILED';event(r,'worker.start_failed',{reason:'Worker provisioning or native startup failed'});if(r.containerId||executor&&r.provisioningAttempted)try{r.stopProof=await stopWorker(r);}catch{r.workerState='RECOVERABLE';r.taskState='UNKNOWN';}save(r);brokers.get(r.workerId)?.close();}
}
const server=http.createServer(async(req,res)=>{
 try{
  const path=new URL(req.url,'http://control').pathname;
  if(path==='/health/live'){
   for(const r of records.values())refreshRevocation(r);const active=[...records.values()].filter(holdsCapacity);
   let memoryBytes=0,cpuTimeNanos=0,available=!!executor;
   if(executor)for(const r of active)try{const m=await executor.metrics(grant(r),r.workerId);if(!Number.isSafeInteger(m.memoryBytes)||!Number.isSafeInteger(m.cpuTimeNanos)){available=false;continue;}memoryBytes+=m.memoryBytes;cpuTimeNanos+=m.cpuTimeNanos;}catch{available=false;}
   return send(res,200,{live:true,engine:'cline-standalone',version:'4.1.26',legacySdkWorker:false,releaseVersion:'1.0.0',enterpriseAcceptance:false,capacity:{maxWorkers:cfg.maxWorkers,activeWorkers:active.length},gatewayMemoryBytes:process.memoryUsage().rss,workerMemoryBytes:available?memoryBytes:null,workerCpuTimeNanos:available?cpuTimeNanos:null});
  }
  const c=granted(req),tenant=Object.hasOwn(cfg.tenants,c.tenantId)?cfg.tenants[c.tenantId]:undefined;if(!tenant||!(tenant.users.includes(c.userId)||c.version===2&&tenant.dynamicUsers===true)||!Object.hasOwn(tenant.workspaces,c.workspaceRef)||!(c.runtimeModel?typeof tenant.runtimeModelAuthority==='string':tenant.models.includes(c.modelProfile))||!c.dataPermissions.every(x=>tenant.datasets.includes(x))||!c.toolPermissions.every(x=>tenant.tools.includes(x)))throw error('Task policy not granted',403);
  if(path==='/v1/sessions'&&req.method==='POST'){
   const b=await body(req),key=req.headers['idempotency-key'];if(!key||Object.keys(b).some(k=>!['editTool',...(c.s3?['inputs']:[])].includes(k))||b.editTool&&!['editor','apply_patch'].includes(b.editTool))throw error('Invalid create request');
   if(c.s3)validateS3Inputs(c,b.inputs,cfg.s3);
   if(c.expiresAt<=Date.now())throw error('Task identity expired',401);
   let r=records.get(c.sessionId);if(r){authorizeSession(c,r);if(r.createKey!==key||r.editTool!==(b.editTool??'editor'))throw error('Create conflict',409);return send(res,200,publicState(r));}
   for(const r of records.values())refreshRevocation(r);
   const active=[...records.values()].filter(holdsCapacity);if(active.length>=cfg.maxWorkers||active.filter(x=>x.contract.tenantId===c.tenantId).length>=cfg.maxPerTenant)throw error('Worker capacity exhausted',429);
   const slot=Array.from({length:cfg.maxWorkers},(_,i)=>i).find(i=>!active.some(x=>x.slot===i));
   r={contract:c,contractHash:contractHash(c),workerId:randomUUID(),slot,workerState:'CREATED',taskState:'CREATED',revoked:false,createKey:key,editTool:b.editTool??'editor',apiToken:randomBytes(32).toString('hex'),brokerToken:randomBytes(32).toString('hex'),...(c.s3?{s3Inputs:b.inputs}: {})};
   mkdirSync(join(cfg.stateRoot,'workers',r.workerId),{recursive:true,mode:0o700});records.set(c.sessionId,r);save(r);event(r,'worker.created');send(res,201,publicState(r));void allocate(r);return;
  }
  const parts=path.split('/').filter(Boolean);let r;
  if(parts[0]==='v1'&&parts[1]==='sessions'){if(parts[2]!==c.sessionId)throw error('Session not found',404);const observe=req.method==='GET'&&(parts.length===3||['status','operations',...(c.s3?['events','artifacts','receipts','executions','observations']:[])].includes(parts[3]));r=authorizeSession(c,records.get(parts[2]),observe||req.method==='POST'&&['cancel','revoke','reconcile',...(c.s3?['execution-content']:[])].includes(parts[3]));}
  else if(parts[0]==='v1'&&parts[1]==='permissions')r=authorizeSession(c,records.get(c.sessionId));else throw error('Route not found',404);
  if(r?.contract.s3&&parts[3]==='observations'&&req.method==='GET'){
   const url=new URL(req.url,'http://control'),after=url.searchParams.get('afterEventId')??'0',limit=Number(url.searchParams.get('limit')??128);
   if(!/^\d+$/.test(after)||!Number.isSafeInteger(limit)||limit<1||limit>256)throw error('Invalid event cursor');
   const available=r.nativeSessionId?new Journal(join(cfg.workerRoot,r.workerId,'state',r.nativeSessionId,'events.jsonl')).after(after):[],events=available.slice(0,limit);
   return send(res,200,{events,nextEventId:events.at(-1)?.eventId??after,hasMore:available.length>events.length});
  }
  if(r?.contract.s3&&r.nativeSessionId&&parts[3]==='executions'&&req.method==='GET'){
   return send(res,200,{executions:executionRecords({dir:join(cfg.workerRoot,r.workerId,'state',r.nativeSessionId)})});
  }
  if(r?.contract.s3&&r.nativeSessionId&&parts[3]==='execution-content'&&req.method==='POST'){
   const request=await body(req);if(Object.keys(request).some(key=>!['recordId','stream','path','offset','limit'].includes(key)))throw error('Invalid execution content request');
   try{return send(res,200,executionContent({dir:join(cfg.workerRoot,r.workerId,'state',r.nativeSessionId)},request));}
   catch{throw error('Execution evidence unavailable or outside the task',404);}
  }
  if(r?.contract.s3&&parts[3]==='receipts'&&req.method==='GET'){
   const url=new URL(req.url,'http://control'),after=url.searchParams.get('afterEventId')??'0',limit=Number(url.searchParams.get('limit')??128);
   if(!/^\d+$/.test(after)||!Number.isSafeInteger(limit)||limit<1||limit>256)throw error('Invalid receipt cursor');
   const available=providerJournal(r).after(after),events=available.slice(0,limit);
   return send(res,200,{events,nextEventId:events.at(-1)?.eventId??after,hasMore:available.length>events.length});
  }
  if(parts[1]==='sessions'&&parts.length===3&&req.method==='GET')return send(res,200,publicState(r));
  if(parts[3]==='operations'&&req.method==='GET')return send(res,200,await operation(r,'/list'));
  if(parts[3]==='reconcile'&&req.method==='POST'){await reconcileRemote(r,r.revoked);return send(res,200,publicState(r));}
  if(parts[3]==='status'&&req.method==='GET'&&!r.nativeSessionId)return send(res,200,publicState(r));
  if(r.contract.s3&&r.workerState==='STOPPED'&&req.method==='GET'){
   if(parts[3]==='status'||parts.length===3)return send(res,200,publicState(r));
   if(parts[3]==='artifacts')return send(res,200,{artifacts:r.delivery?.artifacts??[],acceptance:r.acceptance??null});
   if(parts[3]==='events'){
    const journal=new Journal(join(cfg.workerRoot,r.workerId,'state',r.nativeSessionId,'events.jsonl'));
    res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-store'});
    for(const e of journal.after(req.headers['last-event-id']??'0'))res.write(`id: ${e.eventId}\nevent: ${e.eventType}\ndata: ${JSON.stringify(e)}\n\n`);res.end();return;
   }
  }
  if(parts[3]==='status'&&req.method==='GET'&&r.revoked)return send(res,200,publicState(r));
  if(['cancel','revoke'].includes(parts[3])&&req.method==='POST'){if(r.acceptance)throw error('Runtime validation is already final',409);send(res,202,{workerState:'STOPPING',remoteStopConfirmed:false});void stop(r,parts[3]);return;}
  if(r.contract.s3&&['delivery','upload','acceptance'].includes(parts[3])&&req.method==='POST'){
   if(!live(r)&&!(r.workerState==='STOPPED'&&!r.revoked&&r.contract.expiresAt>Date.now()))throw error('Task lease inactive',403);
   if(r.s3Busy)throw error('Delivery operation in progress',409);
   const b=await body(req);r.s3Busy=true;
   try{
    if(parts[3]==='delivery'){
     if(r.delivery){if(b.deliveryVersion!==r.delivery.deliveryVersion)throw error('Delivery version immutable',409);return send(res,200,r.delivery);}
     if(Object.keys(b).some(k=>k!=='deliveryVersion')||!r.nativeSessionId||typeof b.deliveryVersion!=='string'||!(/^[A-Za-z0-9_-]{1,96}$/).test(b.deliveryVersion))throw error('Invalid delivery request');
     const state=(await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/status')).data;
     if(state.status!=='turn_completed')throw error('Native turn must complete before sealing',409);
     await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/seal','POST',{});
     const manifest=(await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/delivery','POST',b)).data;
     r.stopProof=await stopWorker(r);r.workerState='STOPPED';save(r);
     const workspace=join(cfg.workerRoot,r.workerId,'workspace');
     await verifyStoppedDelivery(workspace,manifest,r.stopProof,cfg.s3);
     // Preserve the original journal, including failed tool calls. Outside Core.
     const journal=readFileSync(join(cfg.workerRoot,r.workerId,'state',r.nativeSessionId,'events.jsonl'),'utf8');
     writeFileSync(join(cfg.workerRoot,r.workerId,'sealed-native-events.json'),JSON.stringify({events:journal.split('\n').filter(Boolean).map(JSON.parse)}),{mode:0o600,flag:'wx'});
     if(r.revoked)throw error('Task revoked while sealing',409);
     r.delivery=manifest;r.taskState='SEALED_PENDING_UPLOAD';save(r);brokers.get(r.workerId)?.close();event(r,'delivery.sealed',{manifestSha256:jsonHash(manifest)});return send(res,200,manifest);
    }
    if(!r.delivery||r.stopProof?.running!==false)throw error('Sealed delivery required',409);
    if(parts[3]==='upload'){
     if(b.manifestSha256!==jsonHash(r.delivery))throw error('Sealed manifest hash differs',409);
     if(r.uploadReceipt)return send(res,200,r.uploadReceipt);
     const controller=new AbortController();if(!calls.has(r.workerId))calls.set(r.workerId,new Set());calls.get(r.workerId).add(controller);
     try{
      r.uploadReceipt=await uploadS3Delivery(join(cfg.workerRoot,r.workerId,'workspace'),r.contract,r.delivery,b,cfg.s3,{signal:AbortSignal.any([controller.signal,AbortSignal.timeout(Math.max(1,r.contract.expiresAt-Date.now()))])});
      if(r.revoked)throw error('Cancelled upload cannot deliver',409);
      r.taskState='DELIVERED_PENDING_RUNTIME_VALIDATION';save(r);event(r,'s3.delivery.uploaded',{manifestSha256:jsonHash(r.delivery)});return send(res,200,r.uploadReceipt);
     }finally{calls.get(r.workerId).delete(controller);}
    }
    if(!r.uploadReceipt||Object.keys(b).some(k=>!['manifestSha256','status','validationRef'].includes(k))||b.manifestSha256!==jsonHash(r.delivery)||!['PASSED','FAILED'].includes(b.status)||typeof b.validationRef!=='string'||!b.validationRef.trim()||b.validationRef.length>1024)throw error('Runtime validation receipt required',409);
    if(r.acceptance&&jsonHash(r.acceptance)!==jsonHash(b))throw error('Runtime validation immutable',409);
    r.acceptance=b;r.taskState=b.status==='PASSED'?'RUNTIME_ACCEPTED':'RUNTIME_REJECTED';save(r);event(r,'runtime.validation.recorded',{status:b.status,manifestSha256:b.manifestSha256});return send(res,200,publicState(r));
   }finally{r.s3Busy=false;}
  }
  if(!r.nativeSessionId||['RECOVERABLE','START_FAILED','STOPPED','STOPPING'].includes(r.workerState))throw error('Native worker unavailable',409);
  if(cfg.operations&&!r.operationSession&&(parts[3]==='messages'||parts[1]==='permissions'))throw error('Durable operation session not ready',409);
  const route=parts[1]==='permissions'?path:'/v1/sessions/'+r.nativeSessionId+(parts[3]?'/'+parts[3]:'');
  if(parts[1]==='sessions'&&!['status','messages','events','diff','artifacts',...(r.contract.s3?['delivery-preview','delivery-content']:[])].includes(parts[3]))throw error('Route not found',404);
  const value=req.method==='POST'?await body(req):undefined;
  if(cfg.operations&&parts[1]==='permissions'&&req.method==='POST'&&value.decision==='approve'){
   const native=(await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/status')).data;
   const pending=native.pendingPermissions.find(p=>p.id===parts[2]);
   if(pending&&pending.toolName.startsWith('tenant_data__'))await operation(r,'/bind',{epoch:pending.epoch,nativeSessionId:native.nativeSessionId,nativeToolCallId:pending.toolCallId,toolName:pending.toolName.slice('tenant_data__'.length),arguments:pending.input});
  }
  if(!live(r))throw error('Task lease inactive',403);
  if(parts[3]==='messages'&&req.method==='POST'){
   if(typeof value.text==='string')value.text+=nativeSkillDiscoveryContext(r.nativeSkillIndex);
   r.workerState='RUNNING';r.taskState='RUNNING';save(r);
  }
  const reply=await workerRequest(r,route,req.method,value,{'accept':req.headers.accept??'application/json',...(req.headers['last-event-id']?{'last-event-id':req.headers['last-event-id']}:{}),...(req.headers['idempotency-key']?{'idempotency-key':req.headers['idempotency-key']}:{} )});
  if(parts[3]==='status'&&reply.statusCode===200){const chunks=[];for await(const b of reply)chunks.push(b);if(r.revoked)return send(res,200,publicState(r));const native=JSON.parse(Buffer.concat(chunks));if(r.workerState==='STARTING')return send(res,200,publicState(r,native));r.workerState=native.status==='awaiting_permission'?'WAITING_APPROVAL':native.status==='turn_completed'?'COMPLETED':native.status==='failed'?'RECOVERABLE':native.status==='idle'?'READY':'RUNNING';r.taskState=native.status==='turn_completed'?'AGENT_TURN_COMPLETED':native.status==='failed'?'UNKNOWN':r.taskState;save(r);if(r.contract.s3&&['failed','recoverable','cancelled'].includes(native.status))void stop(r,'native-core-unavailable');return send(res,200,publicState(r,native));}
  res.writeHead(reply.statusCode,{'content-type':reply.headers['content-type']??'application/json','cache-control':'no-store'});
  if(!streams.has(r.workerId))streams.set(r.workerId,new Set());streams.get(r.workerId).add(res);
  const expiry=setTimeout(()=>res.end(),Math.max(1,r.contract.expiresAt-Date.now()));expiry.unref();res.on('close',()=>{clearTimeout(expiry);streams.get(r.workerId).delete(res);reply.destroy();});reply.pipe(res);
 }catch(e){if(!res.headersSent)send(res,e.status??503,{code:e.status?'REQUEST_REJECTED':'CONTROL_UNAVAILABLE',message:e.status?e.message:'Worker control unavailable'});else res.destroy();}
});
const ticker=setInterval(()=>{for(const r of records.values())if(r.contract.expiresAt<=Date.now()&&(r.contract.s3?!r.revoked&&!r.acceptance&&r.workerState!=='STOPPING':!['STOPPED','START_FAILED','RECOVERABLE','STOPPING'].includes(r.workerState)))void stop(r,'lease-expired');},500);ticker.unref();
if(cfg.s3?.autoApproveAuthorizedTools){
 const approvalMonitor=setInterval(()=>{for(const r of records.values()){
  if(!r.contract.s3||!live(r)||!r.nativeSessionId||r.approvalBusy||r.s3Busy)continue;
  r.approvalBusy=true;
  (async()=>{try{
   const state=(await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/status')).data;
   for(const p of state.pendingPermissions??[]){
    if(!live(r))break;
    const allowed=r.contract.toolPermissions.some(t=>t===p.toolName||t.endsWith('*')&&p.toolName.startsWith(t.slice(0,-1)));
    await workerJSON(r,'/v1/permissions/'+p.id+'/respond','POST',{decision:allowed?'approve':'deny',reason:'Frozen Runtime task tool authorization'});
   }
  }catch(e){event(r,'permission.control_error',{status:e.status??503});}finally{r.approvalBusy=false;}})();
 }},500);approvalMonitor.unref();
}
if(cfg.operations){
 for(const r of records.values())if(r.operationSession&&r.revoked)try{await reconcileRemote(r,true);}catch{event(r,'recovery.remote_unconfirmed');}
 const monitor=setInterval(()=>{for(const r of records.values())if(r.operationSession&&!r.revoked&&!r.monitorBusy){r.monitorBusy=true;(async()=>{try{const info=await inspectWorker(r);if(!info.State.Running){await stop(r,'worker-lost');return;}const native=(await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/status')).data;if(['failed','recoverable','cancelled'].includes(native.status)){await stop(r,'native-core-lost');return;}await operation(r,'/heartbeat',{epoch:r.nativeEpoch});}catch{await stop(r,'operation-control-lost');}finally{r.monitorBusy=false;}})();}},3000);monitor.unref();
}
server.listen(cfg.port??18328,cfg.host??'127.0.0.1',()=>console.log(JSON.stringify({event:'governance.ready',stage:'P3.1',recovery:'fail-closed; no operation replay',maxWorkers:cfg.maxWorkers})));
async function shutdown(){server.close();await Promise.all([...records.values()].filter(r=>!['STOPPED','START_FAILED','RECOVERABLE'].includes(r.workerState)).map(r=>stop(r,'control-shutdown')));process.exit(0);}
process.on('SIGTERM',shutdown);process.on('SIGINT',shutdown);
