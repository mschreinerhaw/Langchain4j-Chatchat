import http from 'node:http';
import {readFileSync,writeFileSync,mkdirSync,readdirSync,existsSync,renameSync,chownSync,chmodSync,cpSync,unlinkSync,lstatSync} from 'node:fs';
import {join} from 'node:path';
import {randomUUID,randomBytes,timingSafeEqual} from 'node:crypto';
import {verifyContract,authorizeSession,contractHash,holdsCapacity} from './task-contract.mjs';
import {DockerEngine,workerContainerSpec} from './docker-engine.mjs';
import {Journal} from './journal.mjs';

const cfg=JSON.parse(readFileSync(process.env.CLINE_GOVERNANCE_CONFIG,'utf8'));
if(!cfg.signingKey||cfg.signingKey.length<32||!cfg.workerImage?.startsWith('sha256:'))throw Error('Pinned image and private task issuer key required');
const docker=new DockerEngine(cfg.dockerSocket),records=new Map(),brokers=new Map(),streams=new Map(),calls=new Map();
mkdirSync(join(cfg.stateRoot,'workers'),{recursive:true,mode:0o700});
const audit=new Journal(join(cfg.stateRoot,'audit.jsonl'));
const error=(message,status=400)=>Object.assign(Error(message),{status});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
function save(r){const dir=join(cfg.stateRoot,'workers',r.workerId);writeFileSync(join(dir,'registry.tmp'),JSON.stringify(r),{mode:0o600});renameSync(join(dir,'registry.tmp'),join(dir,'registry.json'));}
function event(r,type,detail={}){audit.append({type,sessionId:r?.contract.sessionId,tenantId:r?.contract.tenantId,workerId:r?.workerId,...detail});}
for(const id of readdirSync(join(cfg.stateRoot,'workers'))){const p=join(cfg.stateRoot,'workers',id,'registry.json');if(existsSync(p)){const r=JSON.parse(readFileSync(p));if(!['STOPPED','START_FAILED'].includes(r.workerState)){r.workerState='RECOVERABLE';r.taskState='UNKNOWN';r.revoked=true;save(r);event(r,'recovery.required',{reason:'No side-effect replay or operation reconciliation in stage 1'});}records.set(r.contract.sessionId,r);}}
for(const r of records.values())if(r.workerState==='RECOVERABLE'&&r.containerId){try{const info=await docker.inspect(r.containerId);if(info.Config.Labels['cline.workerId']!==r.workerId)throw Error('Container ownership mismatch');r.stopProof=await docker.stop(r.containerId);save(r);}catch{event(r,'recovery.stop_unconfirmed');}}
function templateFiles(path,visit){const stat=lstatSync(path);if(stat.isSymbolicLink())throw Error('Workspace templates must not contain symlinks');visit?.(path);if(stat.isDirectory())for(const name of readdirSync(path))templateFiles(join(path,name),visit);}
async function body(req){const chunks=[];let count=0;for await(const b of req){count+=b.length;if(count>1048576)throw error('Request too large',413);chunks.push(b);}try{return JSON.parse(Buffer.concat(chunks).toString()||'{}');}catch{throw error('Invalid JSON');}}
function send(res,status,value){res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(value));}
function granted(req){return verifyContract((req.headers.authorization??'').replace(/^Bearer /,''),cfg.signingKey);}
function live(r){return !r.revoked&&r.contract.expiresAt>Date.now()&&!['STOPPED','RECOVERABLE','START_FAILED'].includes(r.workerState);}
function workerRequest(r,path,method='GET',value,headers={}){return new Promise((resolve,reject)=>{
 const req=http.request({socketPath:join(cfg.stateRoot,'workers',r.workerId,'bridge/worker.sock'),path,method,headers:{authorization:'Bearer '+r.apiToken,'content-type':'application/json',...headers}},resolve);
 req.setTimeout(95000,()=>req.destroy(error('Native control timeout',504)));req.on('error',reject);req.end(value===undefined?undefined:JSON.stringify(value));
});}
async function workerJSON(r,path,method,value){const response=await workerRequest(r,path,method,value);const chunks=[];for await(const b of response)chunks.push(b);const data=JSON.parse(Buffer.concat(chunks));if(response.statusCode>=400)throw error(data.code??'Native request rejected',response.statusCode);return {code:response.statusCode,data};}
function publicState(r,native){return {sessionId:r.contract.sessionId,runtimeTaskId:r.contract.taskId,workerId:r.workerId,workerState:r.workerState,taskState:r.taskState,revoked:r.revoked,stopProof:r.stopProof??null,remoteStopConfirmed:false,native:native??null};}
async function stop(r,reason){
 if(['STOPPED','STOPPING'].includes(r.workerState))return;r.revoked=true;r.workerState='STOPPING';r.taskState='STOPPING';save(r);event(r,'worker.stopping',{reason});
 for(const response of streams.get(r.workerId)??[])response.end();for(const controller of calls.get(r.workerId)??[])controller.abort();
 try{if(r.nativeSessionId)await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/cancel','POST',{});}catch{}
 try{if(r.containerId)r.stopProof=await docker.stop(r.containerId);r.workerState='STOPPED';r.taskState='CANCELLED_LOCAL_ONLY';}
 catch{r.workerState='RECOVERABLE';r.taskState='UNKNOWN';}
 brokers.get(r.workerId)?.close();save(r);event(r,'worker.stopped',{workerState:r.workerState,stopProof:r.stopProof??null,remoteStopConfirmed:false});
}
async function broker(r,path){
 const server=http.createServer(async(req,res)=>{
  try{
   const provided=Buffer.from(req.headers.authorization??''),expected=Buffer.from('Bearer '+r.brokerToken);
   if(provided.length!==expected.length||!timingSafeEqual(provided,expected))throw error('Broker authorization rejected',401);
   if(!live(r))throw error('Task lease inactive',403);
   const tenant=cfg.tenants[r.contract.tenantId];let url,headers,requestBody;
   if(req.url==='/mcp'){
    if(!tenant.mcp)throw error('MCP not granted',403);
    if(req.method!=='POST')return send(res,405,{code:'STATELESS_MCP_POST_ONLY'});
    requestBody=await body(req);
    if(requestBody.method==='tools/call'){
     if(!tenant.mcp.tools.includes(requestBody.params?.name)||!r.contract.dataPermissions.includes(requestBody.params?.arguments?.datasetId))throw error('Dataset or tool not granted',403);
     event(r,'mcp.authorized',{tool:requestBody.params.name,datasetId:requestBody.params.arguments.datasetId});
    }else if(!['initialize','notifications/initialized','tools/list','ping'].includes(requestBody.method))throw error('MCP method not granted',403);
    url=tenant.mcp.url;headers={'Authorization':'Bearer '+tenant.mcp.token,'Accept':'application/json, text/event-stream','content-type':'application/json'};
    if(req.headers['mcp-protocol-version'])headers['mcp-protocol-version']=req.headers['mcp-protocol-version'];
   }else if(['/model/v1/chat/completions','/model/v1/responses'].includes(req.url)&&req.method==='POST'){
    requestBody=await body(req);const profile=cfg.modelProfiles[r.contract.modelProfile];
    if(requestBody.model!==profile.model)throw error('Model outside task profile',403);
    url=profile.baseUrl.replace(/\/$/,'')+req.url.slice('/model/v1'.length);headers={'Authorization':'Bearer '+profile.apiKey,'content-type':'application/json'};
    event(r,'model.forwarded',{model:profile.model});
   }else throw error('Broker route not found',404);
   if(!live(r))throw error('Task lease inactive',403);
   const controller=new AbortController();if(!calls.has(r.workerId))calls.set(r.workerId,new Set());calls.get(r.workerId).add(controller);
   try{
    const upstream=await fetch(url,{method:'POST',headers,body:JSON.stringify(requestBody),signal:controller.signal,redirect:'error'});
    res.writeHead(upstream.status,{'content-type':upstream.headers.get('content-type')??'application/json'});
    res.on('close',()=>controller.abort());for await(const chunk of upstream.body??[])if(!res.write(chunk))await new Promise(resolve=>res.once('drain',resolve));res.end();
   }finally{calls.get(r.workerId).delete(controller);}
  }catch(e){if(!res.headersSent)send(res,e.status??502,{code:e.status?'BROKER_DENIED':'UPSTREAM_UNAVAILABLE'});else res.destroy();}
 });
 if(existsSync(path))unlinkSync(path);await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(path,resolve);});chownSync(path,1000,1000);chmodSync(path,0o600);brokers.set(r.workerId,server);
}
async function allocate(r){
 const dir=join(cfg.stateRoot,'workers',r.workerId),tenant=cfg.tenants[r.contract.tenantId];
 try{
  r.workerState='STARTING';save(r);event(r,'worker.starting');
  for(const name of ['workspace','state','bridge']){mkdirSync(join(dir,name),{recursive:true,mode:0o700});chownSync(join(dir,name),1000,1000);}
  templateFiles(tenant.workspaces[r.contract.workspaceRef]);cpSync(tenant.workspaces[r.contract.workspaceRef],join(dir,'workspace'),{recursive:true,dereference:false});templateFiles(join(dir,'workspace'),p=>chownSync(p,1000,1000));
  // Template ownership is prepared by the deployment owner; no host path comes from a client.
  const profile=cfg.modelProfiles[r.contract.modelProfile];
  const privateConfig={sourceRoot:'/not-mounted/upstream',distDir:'/poc/runtime',stateDir:'/poc/state',socketPath:'/poc/bridge/worker.sock',apiToken:r.apiToken,apiKey:r.brokerToken,model:profile.model,baseUrl:'http://127.0.0.1:18329/model/v1',workspaces:{project:'/poc/project'},toolPermissions:r.contract.toolPermissions,
   ...(tenant.mcp?{mcpServers:{tenant_data:{type:'streamableHttp',url:'http://127.0.0.1:18329/mcp',headers:{Authorization:'Bearer '+r.brokerToken},disabled:false,autoApprove:[]}}}:{})};
  const configFile=join(dir,'config.json');writeFileSync(configFile,JSON.stringify(privateConfig),{mode:0o600});chownSync(configFile,1000,1000);
  await broker(r,join(dir,'bridge/broker.sock'));
  const index=r.slot;
  const spec=workerContainerSpec({image:cfg.workerImage,root:join(cfg.hostStateRoot,'workers',r.workerId),workerId:r.workerId,sessionId:r.contract.sessionId,tenantId:r.contract.tenantId,memoryBytes:cfg.memoryBytes??3221225472,cpuSet:cfg.cpuSets[index%cfg.cpuSets.length]});
  if(r.revoked)throw Error('Allocation revoked');r.provisioningAttempted=true;save(r);r.containerId=await docker.create('cline-p3-'+r.workerId,spec);save(r);if(r.revoked)throw Error('Allocation revoked');await docker.call('POST','/containers/'+r.containerId+'/start');
  for(let n=0;n<180;n++){if(r.revoked)throw Error('Allocation revoked');try{await workerJSON(r,'/health/ready');break;}catch{if(n===179)throw Error('Worker API startup deadline');await delay(500);}}
  const created=await workerJSON(r,'/v1/sessions','POST',{workspaceRef:'project',editTool:r.editTool});r.nativeSessionId=created.data.sessionId;save(r);
  for(let n=0;n<200;n++){if(r.revoked)throw Error('Allocation revoked');const state=(await workerJSON(r,'/v1/sessions/'+r.nativeSessionId+'/status')).data;if(state.status==='idle')break;if(state.status==='failed'||n===199)throw Error('Native startup failed');await delay(500);}
  if(r.revoked)throw Error('Allocation revoked');r.workerState='READY';r.taskState='READY';save(r);event(r,'worker.ready',{containerId:r.containerId});
 }catch(e){r.revoked=true;r.workerState='START_FAILED';r.taskState='FAILED';event(r,'worker.start_failed',{reason:'Worker provisioning or native startup failed'});if(r.containerId)try{r.stopProof=await docker.stop(r.containerId);}catch{r.workerState='RECOVERABLE';r.taskState='UNKNOWN';}save(r);brokers.get(r.workerId)?.close();}
}
const server=http.createServer(async(req,res)=>{
 try{
  const path=new URL(req.url,'http://control').pathname;
  if(path==='/health/live')return send(res,200,{live:true,stage:'P3.1',enterpriseAcceptance:false});
  const c=granted(req),tenant=Object.hasOwn(cfg.tenants,c.tenantId)?cfg.tenants[c.tenantId]:undefined;if(!tenant||!tenant.users.includes(c.userId)||!Object.hasOwn(tenant.workspaces,c.workspaceRef)||!tenant.models.includes(c.modelProfile)||!c.dataPermissions.every(x=>tenant.datasets.includes(x))||!c.toolPermissions.every(x=>tenant.tools.includes(x)))throw error('Task policy not granted',403);
  if(path==='/v1/sessions'&&req.method==='POST'){
   const b=await body(req),key=req.headers['idempotency-key'];if(!key||Object.keys(b).some(k=>k!=='editTool')||b.editTool&&!['editor','apply_patch'].includes(b.editTool))throw error('Invalid create request');
   if(c.expiresAt<=Date.now())throw error('Task identity expired',401);
   let r=records.get(c.sessionId);if(r){authorizeSession(c,r);if(r.createKey!==key||r.editTool!==(b.editTool??'editor'))throw error('Create conflict',409);return send(res,200,publicState(r));}
   const active=[...records.values()].filter(holdsCapacity);if(active.length>=cfg.maxWorkers||active.filter(x=>x.contract.tenantId===c.tenantId).length>=cfg.maxPerTenant)throw error('Worker capacity exhausted',429);
   const slot=Array.from({length:cfg.maxWorkers},(_,i)=>i).find(i=>!active.some(x=>x.slot===i));
   r={contract:c,contractHash:contractHash(c),workerId:randomUUID(),slot,workerState:'CREATED',taskState:'CREATED',revoked:false,createKey:key,editTool:b.editTool??'editor',apiToken:randomBytes(32).toString('hex'),brokerToken:randomBytes(32).toString('hex')};
   mkdirSync(join(cfg.stateRoot,'workers',r.workerId),{recursive:true,mode:0o700});records.set(c.sessionId,r);save(r);event(r,'worker.created');send(res,201,publicState(r));void allocate(r);return;
  }
  const parts=path.split('/').filter(Boolean);let r;
  if(parts[0]==='v1'&&parts[1]==='sessions'){if(parts[2]!==c.sessionId)throw error('Session not found',404);const observe=req.method==='GET'&&(parts.length===3||parts[3]==='status');r=authorizeSession(c,records.get(parts[2]),observe||req.method==='POST'&&['cancel','revoke'].includes(parts[3]));}
  else if(parts[0]==='v1'&&parts[1]==='permissions')r=authorizeSession(c,records.get(c.sessionId));else throw error('Route not found',404);
  if(parts[1]==='sessions'&&parts.length===3&&req.method==='GET')return send(res,200,publicState(r));
  if(parts[3]==='status'&&req.method==='GET'&&!r.nativeSessionId)return send(res,200,publicState(r));
  if(parts[3]==='status'&&req.method==='GET'&&r.revoked)return send(res,200,publicState(r));
  if(['cancel','revoke'].includes(parts[3])&&req.method==='POST'){send(res,202,{workerState:'STOPPING',remoteStopConfirmed:false});void stop(r,parts[3]);return;}
  if(!r.nativeSessionId||['RECOVERABLE','START_FAILED','STOPPED','STOPPING'].includes(r.workerState))throw error('Native worker unavailable',409);
  const route=parts[1]==='permissions'?path:'/v1/sessions/'+r.nativeSessionId+(parts[3]?'/'+parts[3]:'');
  if(parts[1]==='sessions'&&!['status','messages','events','diff','artifacts'].includes(parts[3]))throw error('Route not found',404);
  const value=req.method==='POST'?await body(req):undefined;
  if(!live(r))throw error('Task lease inactive',403);
  if(parts[3]==='messages'&&req.method==='POST'){r.workerState='RUNNING';r.taskState='RUNNING';save(r);}
  const reply=await workerRequest(r,route,req.method,value,{'accept':req.headers.accept??'application/json',...(req.headers['last-event-id']?{'last-event-id':req.headers['last-event-id']}:{}),...(req.headers['idempotency-key']?{'idempotency-key':req.headers['idempotency-key']}:{} )});
  if(parts[3]==='status'&&reply.statusCode===200){const chunks=[];for await(const b of reply)chunks.push(b);if(r.revoked)return send(res,200,publicState(r));const native=JSON.parse(Buffer.concat(chunks));r.workerState=native.status==='awaiting_permission'?'WAITING_APPROVAL':native.status==='turn_completed'?'COMPLETED':native.status==='failed'?'RECOVERABLE':native.status==='idle'?'READY':'RUNNING';r.taskState=native.status==='turn_completed'?'AGENT_TURN_COMPLETED':native.status==='failed'?'UNKNOWN':r.taskState;save(r);return send(res,200,publicState(r,native));}
  res.writeHead(reply.statusCode,{'content-type':reply.headers['content-type']??'application/json','cache-control':'no-store'});
  if(!streams.has(r.workerId))streams.set(r.workerId,new Set());streams.get(r.workerId).add(res);
  const expiry=setTimeout(()=>res.end(),Math.max(1,r.contract.expiresAt-Date.now()));expiry.unref();res.on('close',()=>{clearTimeout(expiry);streams.get(r.workerId).delete(res);reply.destroy();});reply.pipe(res);
 }catch(e){if(!res.headersSent)send(res,e.status??503,{code:e.status?'REQUEST_REJECTED':'CONTROL_UNAVAILABLE',message:e.status?e.message:'Worker control unavailable'});else res.destroy();}
});
const ticker=setInterval(()=>{for(const r of records.values())if(!['STOPPED','START_FAILED','RECOVERABLE','STOPPING'].includes(r.workerState)&&r.contract.expiresAt<=Date.now())void stop(r,'lease-expired');},500);ticker.unref();
server.listen(cfg.port??18328,cfg.host??'127.0.0.1',()=>console.log(JSON.stringify({event:'governance.ready',stage:'P3.1',recovery:'fail-closed; no operation replay',maxWorkers:cfg.maxWorkers})));
async function shutdown(){server.close();await Promise.all([...records.values()].filter(r=>!['STOPPED','START_FAILED','RECOVERABLE'].includes(r.workerState)).map(r=>stop(r,'control-shutdown')));process.exit(0);}
process.on('SIGTERM',shutdown);process.on('SIGINT',shutdown);
