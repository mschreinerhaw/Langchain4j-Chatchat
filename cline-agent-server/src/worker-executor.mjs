import http from 'node:http';
import {readFileSync,writeFileSync,mkdirSync,readdirSync,cpSync,chmodSync,chownSync,existsSync,unlinkSync,openSync,closeSync,fsyncSync} from 'node:fs';
import {join} from 'node:path';
import {timingSafeEqual} from 'node:crypto';
import {DockerEngine} from './docker-engine.mjs';
import {verifyExecutorDaemon} from './executor-daemon-policy.mjs';
import {reject,exact,taskGrant,identity,validateCreate,safeTree,mountAnchor,assertAnchors,buildSpec,verifyOwned,uuid,workerRuntime} from './worker-executor-policy.mjs';

const cfg=JSON.parse(readFileSync(process.env.CLINE_EXECUTOR_CONFIG));
if(cfg.mcpServerName&&!['runtime','tenant_data'].includes(cfg.mcpServerName))throw Error('Unknown fixed MCP transport profile');
if(cfg.mcpServerName==='runtime'&&Object.values(cfg.tenants??{}).some(t=>!t.tools?.includes('submit_and_exit')))
 throw Error('Native Runtime Executor policy must permit the official submit_and_exit tool');
if(!/^sha256:[a-f0-9]{64}$/.test(cfg.workerImage)||!cfg.expectedDaemonId||!cfg.ownershipKey||!cfg.instanceId||!cfg.apiToken||!cfg.signingKey)throw Error('Pinned Executor policy required');
const docker=new DockerEngine(cfg.dockerSocket);
const daemon=await docker.call('GET','/info');
const daemonPolicy=verifyExecutorDaemon(cfg,daemon);
const resourceSupport=daemonPolicy.resourceSupport;
const runtime=workerRuntime(cfg);
mkdirSync(cfg.workersRoot,{recursive:true,mode:0o755});chmodSync(cfg.workersRoot,0o755);
mkdirSync(cfg.controlRoot,{recursive:true,mode:0o750});
const journal=join(cfg.controlRoot,'executor-audit.jsonl');
function audit(action,detail={}){const fd=openSync(journal,'a',0o600);try{writeFileSync(fd,JSON.stringify({at:new Date().toISOString(),action,...detail})+'\n');fsyncSync(fd);}finally{closeSync(fd);}}
let tail=Promise.resolve(),pending=0;
function serial(action){if(pending>=32)throw reject('Executor queue capacity',429);pending++;const current=tail.then(action).finally(()=>pending--);tail=current.catch(()=>{});return current;}
function tree(path,visit){const s=safeTree(path);if(s.isDirectory())for(const name of readdirSync(path))tree(join(path,name),visit);visit?.(path,s);}
function view(info,c,id){verifyOwned(info,c,id,cfg);return {Id:info.Id,workerId:id,Config:{Labels:info.Config.Labels},State:Object.fromEntries(['Status','Running','ExitCode','StartedAt','FinishedAt'].map(k=>[k,info.State[k]])),resources:{enforced:!!resourceSupport,requested:cfg.resources,configured:{memoryBytes:info.HostConfig.Memory,nanoCpus:info.HostConfig.NanoCpus,pidsLimit:info.HostConfig.PidsLimit},productionReady:!!resourceSupport}};}
async function owned(c,id){if(!uuid.test(id))throw reject('Invalid Worker ID');try{return verifyOwned(await docker.inspect(identity(c,cfg).name),c,id,cfg);}catch(e){if(e.dockerStatus===404)throw reject('Worker not found',404);throw e;}}
function closed(id){return join(cfg.workersRoot,id,'closed');}
function taskClosed(c){return join(cfg.controlRoot,'closed-'+identity(c,cfg).owner);}
function durableMark(path,parent){const fd=openSync(path,'a',0o600);try{writeFileSync(fd,'closed\n');fsyncSync(fd);}finally{closeSync(fd);}const d=openSync(parent,'r');fsyncSync(d);closeSync(d);}
function freeze(c,id){durableMark(closed(id),join(cfg.workersRoot,id));durableMark(taskClosed(c),cfg.controlRoot);}
function prepare(c,b){
 const root=mountAnchor(cfg.workersRoot,b.workerId);if(existsSync(root))throw reject('UNKNOWN: prepared anchor without confirmed container; manual reconciliation required',409);
 mkdirSync(root,{mode:0o755});
 for(const name of ['workspace','state','bridge'])mkdirSync(join(root,name),{mode:0o700});
 const template=cfg.tenants[c.tenantId].workspaces[c.workspaceRef];tree(template);cpSync(template,join(root,'workspace'),{recursive:true,dereference:false});
 if(runtime.python&&!existsSync(join(root,'workspace/input')))mkdirSync(join(root,'workspace/input'),{mode:0o700});
 tree(join(root,'workspace'),p=>chownSync(p,runtime.uid,runtime.uid));
 for(const name of ['state','bridge'])chownSync(join(root,name),runtime.supervisorUid??runtime.uid,runtime.supervisorUid??runtime.uid);
 const privateConfig={operationContext:!!cfg.operationContext,requireSubmitAndExit:cfg.mcpServerName==='runtime',sourceRoot:'/opt/runtime-os/cline/runtime',distDir:'/opt/runtime-os/cline/runtime',stateDir:'/var/lib/runtime-os/coding-node',socketPath:'/run/runtime-os/coding-node/bridge/worker.sock',apiToken:b.apiToken,apiKey:b.brokerToken,model:c.runtimeModel?.providerModel??cfg.modelNames[c.modelProfile],baseUrl:'http://127.0.0.1:18329/model/v1',workspaces:{project:'/workspace'},toolPermissions:c.toolPermissions,
  ...(cfg.tenants[c.tenantId].mcp?{mcpServers:{[cfg.mcpServerName??'tenant_data']:{type:'streamableHttp',url:'http://127.0.0.1:18329/mcp',headers:{Authorization:'Bearer '+b.brokerToken},disabled:false,autoApprove:[]}}}:{})};
 if(c.version===2){
  privateConfig.requireSubmitAndExit=false;
  privateConfig.deliveryBinding=c.deliveryBinding;
  privateConfig.allowDeliveryPreview=true;
  privateConfig.requireDeliverySelection=true;
  privateConfig.modelOptions=cfg.modelOptions??{};
 }
 if(runtime.python){privateConfig.coreUid=runtime.uid;privateConfig.coreGid=runtime.uid;privateConfig.observeCommands=true;}
 const p=join(root,'config.json'),mode=runtime.python?0o400:0o440;writeFileSync(p,JSON.stringify(privateConfig),{mode});chmodSync(p,mode);chownSync(p,0,runtime.python?0:runtime.uid);assertAnchors(cfg.workersRoot,b.workerId);
}
async function create(c,b){
 validateCreate(b);const built=buildSpec(c,b,cfg);let existing;
 try{existing=await docker.inspect(built.name);}catch(e){if(e.dockerStatus!==404)throw e;}
 if(existing){verifyOwned(existing,c,b.workerId,cfg);if(existing.Config.Labels['cline.request']!==built.spec.Labels['cline.request'])throw reject('Immutable Worker create conflict',409);assertAnchors(cfg.workersRoot,b.workerId);return view(existing,c,b.workerId);}
 if(existsSync(taskClosed(c)))throw reject('Task Worker closed; cleanup never authorizes recreation',409);
 const listed=await docker.call('GET','/containers/json?all=1&filters='+encodeURIComponent(JSON.stringify({label:['cline.executor='+cfg.instanceId],status:['created','running','paused','restarting']})));
 // A confirmed never-started container with an Executor-owned close marker cannot
 // run later. Running/restarting/paused containers always retain their reservation.
 const active=listed.filter(x=>!(x.State==='created'&&uuid.test(x.Labels['cline.workerId'])&&existsSync(closed(x.Labels['cline.workerId']))));
 if(active.length>=cfg.maxWorkers||active.filter(x=>x.Labels['cline.tenantId']===c.tenantId).length>=cfg.maxPerTenant)throw reject('Executor independent capacity limit',429);
 prepare(c,b);
 // Explicit review-only mode, never silently advertise unsupported cgroup limits.
 if(!resourceSupport){built.spec.HostConfig.Memory=0;built.spec.HostConfig.NanoCpus=0;built.spec.HostConfig.CpusetCpus='';built.spec.HostConfig.PidsLimit=0;built.spec.HostConfig.Ulimits=[{Name:'nofile',Soft:1024,Hard:1024},{Name:'nproc',Soft:256,Hard:256}];}
 audit('worker.create.intent',{workerId:b.workerId,owner:built.owner,resourceEnforced:!!resourceSupport});
 const id=await docker.create(built.name,built.spec);audit('worker.created',{workerId:b.workerId,containerId:id});return view(await docker.inspect(id),c,b.workerId);
}
async function route(req,b){
 const url=new URL(req.url,'http://executor');if(url.search||url.pathname!==req.url)throw reject('Canonical capability routes only',404);
 if(req.url==='/internal/tasks/revoke'&&req.method==='POST'){
  exact(b,[]);const c=taskGrant(req.headers['x-cline-task'],cfg,true,true);
  return serial(async()=>{
   // Same serial barrier as create/start: a late allocation cannot pass this
   // durable task fence, including when the Gateway died before its response.
   durableMark(taskClosed(c),cfg.controlRoot);
   let info;try{info=await docker.inspect(identity(c,cfg).name);}catch(e){if(e.dockerStatus!==404)throw e;}
   if(!info){audit('task.revoked.unallocated',{owner:identity(c,cfg).owner});return {running:false,neverAllocated:true,allocationFenced:true};}
   const id=info.Config.Labels['cline.workerId'];verifyOwned(info,c,id,cfg);freeze(c,id);
   try{await docker.call('POST','/containers/'+info.Id+'/stop?t=8');}catch(e){if(e.dockerStatus!==304)throw e;}
   const final=await docker.inspect(info.Id);if(final.State.Running)throw reject('UNKNOWN: Worker stop not confirmed',503);
   audit('task.revoked.stopped',{workerId:id,containerId:info.Id});
   return {containerId:info.Id,running:false,exitCode:final.State.ExitCode,finishedAt:final.State.FinishedAt,allocationFenced:true};
  });
 }
 const parts=url.pathname.split('/').filter(Boolean);if(parts[0]!=='internal'||parts[1]!=='workers')throw reject('No Docker/API forwarding capability',404);
 const action=parts[3];if(parts.length>4||action&&!['start','stop','kill','health','metrics'].includes(action))throw reject('Unknown Worker capability',404);
 const token=req.headers['x-cline-task'];const c=taskGrant(token,cfg,req.method==='GET'||['stop','kill'].includes(action)||req.method==='DELETE');
 if(parts.length===2&&req.method==='POST')return serial(()=>create(c,b));
 if(parts.length<3)throw reject('Route not found',404);
 const id=parts[2];
 if(req.method==='GET'&&(!action||action==='health')){exact(b,[]);return view(await owned(c,id),c,id);}
 if(req.method==='GET'&&action==='metrics'){
  exact(b,[]);const info=await owned(c,id);
  if(!info.State.Running)return {running:false,memoryBytes:0,cpuTimeNanos:0};
  const stats=await docker.call('GET','/containers/'+info.Id+'/stats?stream=false&one-shot=true');
  return {running:true,memoryBytes:stats.memory_stats?.usage??null,cpuTimeNanos:stats.cpu_stats?.cpu_usage?.total_usage??null};
 }
 if(req.method==='POST'&&['start','stop','kill'].includes(action)){
  exact(b,[]);return serial(async()=>{const info=await owned(c,id);assertAnchors(cfg.workersRoot,id);
   if(action==='start'){
    if(existsSync(closed(id)))throw reject('Worker closed; late start rejected',409);
    if(info.State.Running)return view(info,c,id);
    if(info.State.StartedAt&&!info.State.StartedAt.startsWith('0001-'))throw reject('Worker restart is not an admitted capability',409);
    await docker.call('POST','/containers/'+info.Id+'/start');audit('worker.started',{workerId:id,containerId:info.Id});return view(await docker.inspect(info.Id),c,id);
   }
   freeze(c,id);
   if(action==='kill'&&info.State.Running)await docker.call('POST','/containers/'+info.Id+'/kill?signal=SIGKILL');
   else if(action==='stop')try{await docker.call('POST','/containers/'+info.Id+'/stop?t=8');}catch(e){if(e.dockerStatus!==304)throw e;}
   const final=await docker.inspect(info.Id);if(final.State.Running)throw reject('UNKNOWN: Worker stop not confirmed',503);
   audit('worker.stopped',{workerId:id,containerId:info.Id,exitCode:final.State.ExitCode});return {containerId:info.Id,running:false,exitCode:final.State.ExitCode,finishedAt:final.State.FinishedAt};
  });
 }
 if(req.method==='DELETE'&&!action){exact(b,[]);return serial(async()=>{const info=await owned(c,id);if(info.State.Running||!existsSync(closed(id)))throw reject('Only confirmed closed Workers can be cleaned',409);freeze(c,id);await docker.call('DELETE','/containers/'+info.Id);audit('worker.removed',{workerId:id,containerId:info.Id});return {removed:true,workerId:id};});}
 throw reject('Method not granted',405);
}
const server=http.createServer(async(req,res)=>{let code=200,value;try{
 if(req.method==='GET'&&req.url==='/health/live'){value={live:true,daemon:daemonPolicy.mode,resourceEnforced:!!resourceSupport,productionReady:false};}
 else{
  const a=Buffer.from(req.headers.authorization??''),e=Buffer.from('Bearer '+cfg.apiToken);if(a.length!==e.length||!timingSafeEqual(a,e))throw reject('Executor service credential rejected',401);
  let size=0;const chunks=[];for await(const b of req){size+=b.length;if(size>32768)throw reject('Request too large',413);chunks.push(b);}let b;try{b=JSON.parse(Buffer.concat(chunks).toString()||'{}');}catch{throw reject('Invalid JSON',400);}value=await route(req,b);
 }
}catch(e){code=e.status??503;value={message:e.status?e.message:'Executor operation unknown; query before retry'};audit('request.rejected',{method:req.method,path:req.url,code,reason:value.message,nativeErrorCode:e.code??null,nativeFailure:String(e.dockerMessage??e.message).slice(0,512)});}
res.writeHead(code,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(value));});
server.requestTimeout=10000;server.headersTimeout=10000;
if(existsSync(cfg.socketPath))unlinkSync(cfg.socketPath);
await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(cfg.socketPath,resolve);});chownSync(cfg.socketPath,0,runtime.uid);chmodSync(cfg.socketPath,0o660);
console.log(JSON.stringify({event:'restricted-executor.ready',daemonId:daemon.ID,daemon:daemonPolicy.mode,rootless:daemonPolicy.rootless,resourceEnforced:!!resourceSupport,productionReady:false}));
