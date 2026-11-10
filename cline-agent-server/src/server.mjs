import http from 'node:http';
import {spawnSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync,existsSync,readdirSync,realpathSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {randomUUID,createHash,timingSafeEqual} from 'node:crypto';
import {Journal} from './journal.mjs';
import {loadNative,startHostBridge} from './host-bridge.mjs';
import {toolFailed} from './tool-outcome.mjs';
import {validateRuntime} from './runtime-assets.mjs';
import {nativeModelSettings} from './native-model-settings.mjs';
import {forkNativeWorker} from './native-process.mjs';

const config=JSON.parse(readFileSync(process.env.CLINE_SERVER_CONFIG,'utf8'));
const modelSettings=nativeModelSettings(config);
for(const k of ['sourceRoot','distDir','stateDir','apiToken','model','baseUrl','apiKey','workspaces'])if(!config[k])throw Error('Missing config '+k);
const runtimeAssets=validateRuntime(config.distDir);
const native=loadNative(resolve(config.sourceRoot),resolve(config.distDir)),sessions=new Map(),permissions=new Map();
const digest=x=>createHash('sha256').update(JSON.stringify(x)).digest('hex');
const redact=x=>JSON.parse(JSON.stringify(x??null,(_k,v)=>{
 if(v instanceof Error)return {name:v.name,message:v.message,stack:v.stack};
 if(typeof v==='string')for(const secret of [config.apiKey,config.apiToken])v=v.split(secret).join('[REDACTED]');
 return v;
}));
mkdirSync(config.stateDir,{recursive:true,mode:0o700});
function persist(s){writeFileSync(join(s.dir,'session.json'),JSON.stringify({id:s.id,workspaceRef:s.workspaceRef,editTool:s.editTool,status:s.status,taskId:s.taskId,nativeSessionId:s.nativeSessionId,epoch:s.epoch,revision:s.revision,commands:s.commands}),{mode:0o600});}
function emit(s,eventType,payload={}){
 const event=s.journal.append({sessionId:s.id,taskId:s.taskId??null,epoch:s.epoch,eventType,payload:redact(payload)});
 for(const response of s.subscribers){if(!response.write(`id: ${event.eventId}\nevent: ${eventType}\ndata: ${JSON.stringify(event)}\n\n`)){s.subscribers.delete(response);response.end();}}
 return event;
}
function status(s,state){s.status=state;s.revision++;persist(s);}
function failure(s,error){if(s.status==='cancelled'||s.status==='cancelling')return;status(s,'failed');emit(s,'task.failed',{message:String(error.message??error)});}
for(const name of readdirSync(config.stateDir)){
 const file=join(config.stateDir,name,'session.json');if(!existsSync(file))continue;
 const state=JSON.parse(readFileSync(file,'utf8'));
 const s={...state,dir:join(config.stateDir,name),journal:new Journal(join(config.stateDir,name,'events.jsonl')),subscribers:new Set(),acks:new Map(),workspace:config.workspaces[state.workspaceRef]};
 sessions.set(s.id,s);
 if(!['cancelled','failed'].includes(s.status)){
  s.status='recoverable';s.epoch=randomUUID();persist(s);
  emit(s,'session.recovery_required',{reason:'Worker ownership lost; automatic native/remote-operation replay is disabled in POC'});
 }
}
function rpc(client,method,message){return new Promise((res,rej)=>client[method](message,{deadline:Date.now()+60000},(e,v)=>e?rej(e):res(v)));}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function freePort(){const server=http.createServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));const p=server.address().port;await new Promise(r=>server.close(r));return p;}
function nativeEvent(s,event){
 // Reasoning is not needed for audit. Preserve tool/text/usage/error events.
 const a=event.payload?.event;
 if(a?.contentType==='reasoning')return;
 // Core also serializes AgentEvent into compatibility chunks; avoid duplicates and reasoning leakage.
 if(event.type==='chunk'&&event.payload?.stream==='agent')return;
 if(event.type==='session_snapshot'){
  const {messages,metadata,...snapshot}=event.payload.snapshot;
  event={...event,payload:{...event.payload,snapshot}};
 }
 emit(s,'native.event',event);
 if(event.payload?.sessionId&&!s.nativeSessionId){s.nativeSessionId=event.payload.sessionId;persist(s);}
 if(a?.type==='iteration_start')emit(s,'model.iteration.started',{iteration:a.iteration,source:'AgentEvent.iteration_start'});
 if(a?.type==='usage')emit(s,'model.usage',{...a,cost:null,totalCost:null,costStatus:'unverified-native-pricing'});
 if(a?.contentType==='tool'&&a.type==='content_start')emit(s,'tool.started',a);
 if(a?.contentType==='tool'&&a.type==='content_end')emit(s,toolFailed(a)?'tool.failed':'tool.completed',a);
 if(a?.type==='notice'&&String(a.reason).includes('compaction'))emit(s,'context.notice',a);
 if(a?.type==='error'){
  emit(s,'model.error',a);
  if(a.recoverable===false)failure(s,Error(a.error?.message??'Unrecoverable native model error'));
 }
 if(a?.type==='done'&&!['cancelling','cancelled','failed'].includes(s.status)){
  if(a.reason!=='completed'){failure(s,Error('Native Agent stopped: '+a.reason));return;}
  status(s,'turn_completed');emit(s,'turn.completed',{...a,deliveryValidated:false});
 }
}
async function startWorker(s){
 s.bridge=await startHostBridge(native,s.workspace,(t,p)=>emit(s,t,p));
 const data=join(s.dir,'cline','data');mkdirSync(data,{recursive:true,mode:0o700});
 const global={mode:'act',planModeApiProvider:'openai',actModeApiProvider:'openai',openAiBaseUrl:config.baseUrl,
  planModeOpenAiModelId:config.model,actModeOpenAiModelId:config.model,...modelSettings.global,
  backgroundEditEnabled:true,vscodeTerminalExecutionMode:'backgroundExec',telemetrySetting:'disabled',
  autoApprovalSettings:{version:1,enabled:true,favorites:[],maxRequests:100,enableNotifications:false,actions:{readFiles:false,readFilesExternally:false,editFiles:false,editFilesExternally:false,executeSafeCommands:false,executeAllCommands:false,useBrowser:false,useMcp:false}}};
 writeFileSync(join(data,'globalState.json'),JSON.stringify(global),{mode:0o600});
 writeFileSync(join(data,'secrets.json'),JSON.stringify({openAiApiKey:config.apiKey}),{mode:0o600});
 if(modelSettings.providers){const settings=join(data,'settings');mkdirSync(settings,{recursive:true,mode:0o700});writeFileSync(join(settings,'providers.json'),JSON.stringify(modelSettings.providers),{mode:0o600});}
 if(config.mcpServers){const settings=join(data,'settings');mkdirSync(settings,{recursive:true,mode:0o700});writeFileSync(join(settings,'cline_mcp_settings.json'),JSON.stringify({mcpServers:config.mcpServers}),{mode:0o600});}
 const port=await freePort();
 const env={PATH:process.env.PATH,LANG:'C.UTF-8',HOME:join(s.dir,'home'),CLINE_DIR:join(s.dir,'cline'),CLINE_DATA_DIR:data,
  WORKSPACE_STORAGE_DIR:join(s.dir,'workspace-storage'),INSTALL_DIR:resolve(config.distDir),
  HOST_BRIDGE_ADDRESS:`127.0.0.1:${s.bridge.port}`,PROTOBUS_ADDRESS:`127.0.0.1:${port}`,
  CLINE_SERVER_EPOCH:s.epoch,CLINE_SERVER_OPERATION_CONTEXT:config.operationContext?'1':'0',CLINE_SERVER_EDIT_TOOL:s.editTool??'editor',CLINE_SERVER_REQUIRE_SUBMIT:config.requireSubmitAndExit?'1':'0',...(config.requireSubmitAndExit?{CLINE_SERVER_BROKER_TOKEN:config.apiKey}:{}),CLINE_SESSION_BACKEND_MODE:'local',DO_NOT_TRACK:'1'};
 mkdirSync(env.HOME,{recursive:true,mode:0o700});
 s.worker=forkNativeWorker(join(config.distDir,'cline-core.js'),['--config',join(s.dir,'cline')],{cwd:resolve(config.distDir),env,detached:true,stdio:['ignore','pipe','pipe','ipc']});
 const ready=new Promise((res,rej)=>{
  const timer=setTimeout(()=>rej(Error('Native startup deadline exceeded; inspect worker.log and host.unsupported')),90000);
  s.worker.once('error',e=>{clearTimeout(timer);rej(e);});
  s.worker.once('exit',(code,signal)=>{clearTimeout(timer);if(!s.workerReady)rej(Error(`Native worker exited during startup: ${code}/${signal}`));else if(!['cancelling','cancelled'].includes(s.status))failure(s,Error(`Native worker exited: ${code}/${signal}`));});
  s.worker.on('message',message=>{
   if(message.kind==='native.ready'){s.workerReady=true;clearTimeout(timer);res();}
   if(message.kind==='native.event')nativeEvent(s,message.event);
   if(message.kind==='native.question'){s.question=message;status(s,'awaiting_input');emit(s,'interaction.requested',message);}
   if(message.kind==='native.permission'){
    const p={id:randomUUID(),sessionId:s.id,taskId:s.taskId,epoch:s.epoch,inputHash:message.inputHash,toolCallId:message.request.toolCallId,toolName:message.request.toolName,input:message.request.input,expiresAt:Date.now()+180000,state:'pending'};
    permissions.set(p.id,p);status(s,'awaiting_permission');emit(s,'permission.requested',p);
    if(config.toolPermissions&&!config.toolPermissions.some(t=>t===p.toolName||t.endsWith('*')&&p.toolName.startsWith(t.slice(0,-1)))){respond(s,p,'deny','Task tool policy denied').catch(e=>failure(s,e));return;}
    p.timer=setTimeout(()=>respond(s,p,'deny','Approval expired').catch(e=>failure(s,e)),180000);p.timer.unref();
   }
   if(message.kind==='permission.ack'){const ack=s.acks.get(message.commandId);if(ack){s.acks.delete(message.commandId);ack(message.applied);}}
  });
 });
 const log=new Journal(join(s.dir,'worker-log.jsonl'));
 for(const stream of [s.worker.stdout,s.worker.stderr])stream.on('data',b=>{
  const safe=redact(b.toString());log.append({text:safe});
 });
 await ready;
 s.taskClient=new native.proto.cline.TaskService(`127.0.0.1:${port}`,native.grpc.credentials.createInsecure());
 status(s,'idle');emit(s,'worker.ready',{native:'v4.1.26',sourceCommit:'a459f1d04dea030833b558be645011ab739c463b',hostCapabilities:{workspace:true,editorUI:false,diagnostics:false,backgroundShell:true}});
}
async function respond(s,p,decision,reason){
 if(p.state!=='pending'){if(p.decision===decision)return {id:p.id,state:p.state,decision};throw Object.assign(Error('Conflicting or stale permission response'),{status:409});}
 if(p.epoch!==s.epoch||['cancelling','cancelled','failed'].includes(s.status)||(!s.worker?.connected))throw Object.assign(Error('Permission no longer active'),{status:409});
 if(p.expiresAt<Date.now()&&decision==='approve')throw Object.assign(Error('Permission expired'),{status:410});
 p.state='resolving';p.decision=decision;clearTimeout(p.timer);emit(s,'permission.decision',{id:p.id,decision});
 const commandId=randomUUID();
 const applied=await new Promise((res,rej)=>{const timer=setTimeout(()=>{s.acks.delete(commandId);rej(Error('Native permission ACK timeout'));},10000);s.acks.set(commandId,x=>{clearTimeout(timer);res(x);});s.worker.send({kind:'permission.respond',epoch:p.epoch,toolCallId:p.toolCallId,inputHash:p.inputHash,decision,reason,commandId});});
 p.state=applied?'resolved':'stale';emit(s,'permission.resolved',{id:p.id,decision,applied});
 if(s.status==='awaiting_permission')status(s,'running');
 if(!applied)throw Object.assign(Error('Native resolver no longer matches'),{status:409});
 return {id:p.id,state:p.state,decision,applied};
}
function descendants(pid){
 const map=[];for(const p of readdirSync('/proc').filter(n=>/^\d+$/.test(n))){try{const stat=readFileSync(`/proc/${p}/stat`,'utf8').split(') ')[1].split(' ');map.push({pid:Number(p),ppid:Number(stat[1]),state:stat[0]});}catch{}}
 const found=new Set([pid]);for(let n=0;n<map.length;n++){let added=false;for(const p of map)if(found.has(p.ppid)&&!found.has(p.pid)){found.add(p.pid);added=true;}if(!added)break;}
 return map.filter(p=>found.has(p.pid)&&p.state!=='Z').map(p=>p.pid);
}
async function cancel(s){
 status(s,'cancelling');emit(s,'task.cancel.requested',{});
 for(const p of permissions.values())if(p.sessionId===s.id&&p.state==='pending'){clearTimeout(p.timer);p.state='cancelled';emit(s,'permission.resolved',{id:p.id,decision:'deny',reason:'cancelled'});}
 let pids=s.worker?.pid?descendants(s.worker.pid):[];
 if(s.taskClient)await Promise.race([rpc(s.taskClient,'cancelTask',{}).catch(e=>emit(s,'cancel.native_error',{message:e.message})),sleep(3000)]);
 if(s.worker?.pid){pids=[...new Set([...pids,...descendants(s.worker.pid)])];try{process.kill(-s.worker.pid,'SIGTERM');}catch{}for(const pid of pids)try{process.kill(pid,'SIGTERM');}catch{}}
 await sleep(750);
 for(const pid of pids)if(existsSync(`/proc/${pid}`))try{process.kill(pid,'SIGKILL');}catch{}
 await sleep(300);
 const alive=pids.filter(pid=>{try{return readFileSync(`/proc/${pid}/stat`,'utf8').split(') ')[1].split(' ')[0]!=='Z';}catch{return false;}});
 s.bridge?.close();s.taskClient?.close();s.workerReady=false;
 if(alive.length){emit(s,'task.stop_unconfirmed',{pids:alive});return;}
 status(s,'cancelled');emit(s,'task.cancelled',{localProcesses:pids,localStopConfirmed:true,externalOperations:config.mcpServers?'remote-stop-unverified':'none-configured-in-poc',...(config.mcpServers?{remoteStopConfirmed:false}:{})});
}
const send=(res,code,body)=>{res.writeHead(code,{'content-type':'application/json; charset=utf-8'});res.end(JSON.stringify(body));};
async function body(req){let s='';for await(const c of req){s+=c;if(s.length>1024*1024)throw Object.assign(Error('Request too large'),{status:413});}try{return JSON.parse(s||'{}');}catch{throw Object.assign(Error('Invalid JSON'),{status:400});}}
function authorize(req){const value=Buffer.from(req.headers.authorization??''),expected=Buffer.from('Bearer '+config.apiToken);if(value.length!==expected.length||!timingSafeEqual(value,expected))throw Object.assign(Error('Authentication required'),{status:401});}
const server=http.createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://localhost'),parts=url.pathname.split('/').filter(Boolean),method=req.method;
  if(url.pathname==='/health/live')return send(res,200,{live:true,version:'4.1.26-poc.1'});
  authorize(req);
  if(url.pathname==='/health/ready')return send(res,200,{ready:true,runtimeAssets,scope:'Runtime assets verified; native worker readiness is per session',productionReady:false});
  if(url.pathname==='/v1/sessions'&&method==='POST'){
   const b=await body(req);if(typeof b.workspaceRef!=='string'||!Object.hasOwn(config.workspaces,b.workspaceRef))return send(res,400,{code:'UNKNOWN_WORKSPACE'});
   if(b.editTool&&!['editor','apply_patch'].includes(b.editTool))return send(res,400,{code:'INVALID_NATIVE_EDIT_PROFILE'});
   if([...sessions.values()].some(s=>(s.worker?.exitCode===null&&s.status!=='cancelled')||['starting','running','awaiting_permission'].includes(s.status)))return send(res,409,{code:'POC_SINGLE_WORKER_LIMIT',message:'Cancel existing worker before allocating another; multi-tenant isolation is not claimed'});
   const id=randomUUID(),dir=join(config.stateDir,id);mkdirSync(dir,{recursive:true,mode:0o700});
   const s={id,dir,workspaceRef:b.workspaceRef,editTool:b.editTool??'editor',workspace:realpathSync(config.workspaces[b.workspaceRef]),status:'starting',epoch:randomUUID(),revision:0,commands:Object.create(null),journal:new Journal(join(dir,'events.jsonl')),subscribers:new Set(),acks:new Map()};
   sessions.set(id,s);persist(s);emit(s,'session.created',{workspaceRef:b.workspaceRef});startWorker(s).catch(e=>failure(s,e));
   return send(res,201,{sessionId:id,status:s.status,epoch:s.epoch});
  }
  if(parts[0]==='v1'&&parts[1]==='permissions'&&parts[3]==='respond'&&method==='POST'){
   const p=permissions.get(parts[2]);if(!p)return send(res,404,{code:'NOT_FOUND'});const b=await body(req);if(!['approve','deny'].includes(b.decision))return send(res,400,{code:'INVALID_DECISION'});
   return send(res,200,await respond(sessions.get(p.sessionId),p,b.decision,b.reason));
  }
  if(parts[0]!=='v1'||parts[1]!=='sessions')return send(res,404,{code:'NOT_FOUND'});
  const s=sessions.get(parts[2]);if(!s)return send(res,404,{code:'NOT_FOUND'});
  if(method==='GET'&&(parts.length===3||parts[3]==='status'))return send(res,200,{sessionId:s.id,status:s.status,taskId:s.taskId,nativeSessionId:s.nativeSessionId,epoch:s.epoch,revision:s.revision,lastEventId:String(s.journal.events.length),pendingPermissions:[...permissions.values()].filter(p=>p.sessionId===s.id&&p.state==='pending').map(({timer,...p})=>redact(p))});
  if(parts[3]==='events'&&method==='GET'){
   const last=req.headers['last-event-id']??url.searchParams.get('after')??'0';const events=s.journal.after(last);
   if(req.headers.accept==='application/json')return send(res,200,{events});
   res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache','connection':'keep-alive'});
   for(const e of events)res.write(`id: ${e.eventId}\nevent: ${e.eventType}\ndata: ${JSON.stringify(e)}\n\n`);
   s.subscribers.add(res);const heartbeat=setInterval(()=>res.write(': heartbeat\n\n'),15000);req.on('close',()=>{clearInterval(heartbeat);s.subscribers.delete(res);});return;
  }
  if(parts[3]==='messages'&&method==='GET')return send(res,200,{events:s.journal.events.filter(e=>e.eventType==='native.event'&&e.payload.type==='agent_event')});
  if(parts[3]==='diff'&&method==='GET'){
   const r=spawnSync('git',['diff','--no-ext-diff','HEAD','--'],{cwd:s.workspace,encoding:'utf8',maxBuffer:8*1024*1024});if(r.status!==0)throw Error(r.stderr);return send(res,200,{patch:r.stdout,sha256:digest(r.stdout)});
  }
  if(parts[3]==='artifacts'&&method==='GET')return send(res,200,{artifacts:[],deliveryValidated:false});
  if(parts[3]==='messages'&&method==='POST'){
   const b=await body(req),key=req.headers['idempotency-key'];if(!key||typeof b.text!=='string'||!b.text.trim())return send(res,400,{code:'TEXT_AND_IDEMPOTENCY_KEY_REQUIRED'});
   if(s.commands[key])return send(res,s.commands[key].hash===digest(b)?202:409,s.commands[key].hash===digest(b)?s.commands[key].response:{code:'IDEMPOTENCY_CONFLICT'});
   if(s.status==='awaiting_input'){
    if(b.interactionId!==s.question?.questionId)return send(res,409,{code:'INTERACTION_MISMATCH'});
    const response={taskId:s.taskId,status:'accepted'};s.commands[key]={hash:digest(b),response};s.worker.send({kind:'question.respond',epoch:s.epoch,questionId:s.question.questionId,text:b.text});s.question=null;status(s,'running');emit(s,'interaction.resolved',{});return send(res,202,response);
   }
   if(!s.workerReady||!['idle','turn_completed'].includes(s.status))return send(res,409,{code:'SESSION_BUSY_OR_UNAVAILABLE',status:s.status});
   s.taskId=randomUUID();const response={taskId:s.taskId,status:'accepted'};s.commands[key]={hash:digest(b),response};status(s,'running');emit(s,'task.accepted',{text:b.text});send(res,202,response);
   const isNew=!s.nativeSessionId;
   const operation=isNew?rpc(s.taskClient,'newTask',{text:b.text,images:[],files:[]}):rpc(s.taskClient,'askResponse',{responseType:'messageResponse',text:b.text,images:[],files:[]});
   operation.then(result=>{if(isNew&&!result.value)throw Error('Native newTask returned empty ID');if(result.value)s.nativeSessionId=result.value;persist(s);emit(s,'task.started',{nativeSessionId:s.nativeSessionId});}).catch(e=>failure(s,e));return;
  }
  if(parts[3]==='cancel'&&method==='POST'){
   if(s.status==='cancelled')return send(res,200,{status:'cancelled'});if(s.status==='cancelling')return send(res,202,{status:'cancelling'});
   if(!s.worker)return send(res,409,{code:'NO_OWNED_WORKER'});send(res,202,{status:'cancelling'});cancel(s).catch(e=>failure(s,e));return;
  }
  send(res,404,{code:'NOT_FOUND'});
 }catch(error){if(!res.headersSent)send(res,error.status??500,{code:'REQUEST_FAILED',message:redact(error.message)});else res.end();}
});
server.listen(config.socketPath?{path:config.socketPath}:{port:config.port??18326,host:config.host??'127.0.0.1'},()=>console.log(JSON.stringify({event:'listening',transport:config.socketPath?'private-unix-socket':'http',port:config.port??18326,upstream:'v4.1.26',mode:'single-worker'})));
async function shutdown(){server.close();for(const s of sessions.values())if(s.worker?.exitCode===null&&s.status!=='cancelled')await cancel(s);process.exit(0);}
process.on('SIGTERM',shutdown);process.on('SIGINT',shutdown);
