// Unmodified official Standalone control. No Agent/tool implementation or HTTP Server patches.
import {readFileSync,writeFileSync,mkdirSync,createWriteStream} from 'node:fs';
import {join} from 'node:path';
import {fork} from 'node:child_process';
import {createHash} from 'node:crypto';
import {loadNative,startHostBridge} from '../src/host-bridge.mjs';
const config=JSON.parse(readFileSync('/run/cline-poc.json','utf8'));
const dir='/poc/stock-state',runtime='/poc/stock-runtime',workspace='/poc/stock-project';
mkdirSync(join(dir,'data'),{recursive:true,mode:0o700});mkdirSync(join(dir,'home'),{recursive:true,mode:0o700});
const json=(name,data)=>writeFileSync(join(dir,name),JSON.stringify(data,null,2)+'\n');
const native=loadNative(config.sourceRoot,runtime);
const hostEvents=[];const bridge=await startHostBridge(native,workspace,(type,payload)=>{hostEvents.push({type,payload});json('host-events.json',hostEvents);});
// Official test-mode settings. This isolated reference run is not a production approval policy.
writeFileSync(join(dir,'data/globalState.json'),JSON.stringify({mode:'act',planModeApiProvider:'openai',actModeApiProvider:'openai',openAiBaseUrl:config.baseUrl,planModeOpenAiModelId:config.model,actModeOpenAiModelId:config.model,backgroundEditEnabled:true,vscodeTerminalExecutionMode:'backgroundExec',telemetrySetting:'disabled',autoApprovalSettings:{version:1,enabled:true,favorites:[],maxRequests:100,enableNotifications:false,actions:{readFiles:true,readFilesExternally:false,editFiles:true,editFilesExternally:false,executeSafeCommands:true,executeAllCommands:false,useBrowser:false,useMcp:false}}}),{mode:0o600});
writeFileSync(join(dir,'data/secrets.json'),JSON.stringify({openAiApiKey:config.apiKey}),{mode:0o600});
const log=createWriteStream(join(dir,'worker.log'),{mode:0o600});
const worker=fork(join(runtime,'cline-core.js'),['--config',dir],{cwd:runtime,env:{PATH:process.env.PATH,LANG:'C.UTF-8',HOME:join(dir,'home'),CLINE_DIR:dir,CLINE_DATA_DIR:join(dir,'data'),WORKSPACE_STORAGE_DIR:join(dir,'workspace-storage'),INSTALL_DIR:runtime,HOST_BRIDGE_ADDRESS:`127.0.0.1:${bridge.port}`,PROTOBUS_ADDRESS:'127.0.0.1:26340',CLINE_SESSION_BACKEND_MODE:'local',DO_NOT_TRACK:'1'},detached:true,stdio:['ignore','pipe','pipe','ipc']});
for(const stream of [worker.stdout,worker.stderr])stream.on('data',b=>{let text=b.toString();for(const secret of [config.apiKey,config.apiToken])text=text.split(secret).join('[REDACTED]');log.write(text);});
const task=new native.proto.cline.TaskService('127.0.0.1:26340',native.grpc.credentials.createInsecure());
const state=new native.proto.cline.StateService('127.0.0.1:26340',native.grpc.credentials.createInsecure());
const rpc=(client,method,message)=>new Promise((resolve,reject)=>client[method](message,{deadline:Date.now()+60000},(e,v)=>e?reject(e):resolve(v)));
let started=false;
try{
 await new Promise((resolve,reject)=>task.waitForReady(Date.now()+90000,e=>e?reject(e):resolve()));
 const prompt=readFileSync(new URL('./task.txt',import.meta.url),'utf8');
 json('task-manifest.json',{upstreamCommit:'a459f1d04dea030833b558be645011ab739c463b',model:config.model,prompt,promptSha256:createHash('sha256').update(prompt).digest('hex'),variant:'official unmodified Standalone, real Server HostBridge, stock default editor route',approvalDifference:'isolated reference auto-approval; HTTP server cases approve per request'});
 const result=await rpc(task,'newTask',{text:prompt,images:[],files:[]});if(!result.value)throw Error('Stock native newTask returned empty ID');started=true;json('submission.json',result);
 const start=Date.now();let snapshot;
 while(Date.now()-start<1000000){
  const value=await rpc(state,'getLatestState',{});const s=JSON.parse(value.stateJson);
  snapshot={nativeSessionId:result.value,turnState:s.turnState,messages:(s.clineMessages??[]).filter(m=>m.say!=='reasoning'),timestamp:new Date().toISOString()};json('final-state.json',snapshot);
  const phase=s.turnState?.phase;
  if(phase==='completed'||phase==='error'||(phase==='awaiting_followup'&&snapshot.messages.at(-1)?.ask!=='followup'))break;
  await new Promise(r=>setTimeout(r,1500));
 }
 json('summary.json',{nativeSessionId:result.value,phase:snapshot?.turnState?.phase,durationSeconds:(Date.now()-start)/1000,independentVerification:'PENDING',observer:'official StateService projection, not a lossless event stream',sourceModification:'NONE'});
 console.log(JSON.stringify({nativeSessionId:result.value,phase:snapshot?.turnState?.phase}));
}catch(error){json('failure.json',{message:error.message});process.exitCode=1;}
finally{
 if(started)await rpc(task,'cancelTask',{}).catch(()=>{});
 try{process.kill(-worker.pid,'SIGTERM');}catch{}
 await new Promise(r=>setTimeout(r,2000));try{process.kill(-worker.pid,'SIGKILL');}catch{}
 task.close();state.close();bridge.close();log.end();
}
