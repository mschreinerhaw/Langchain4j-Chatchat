// HTTP/Unix transport and fencing fixture with scripted upstream SSE responses.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {join,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {createHash,createHmac} from 'node:crypto';
import {runtimeModelProfile,signContract} from '../src/task-contract.mjs';
import {inputManifestHash,jsonHash} from '../src/s3-task-transport.mjs';

const pause=ms=>new Promise(r=>setTimeout(r,ms));
const listen=(server,address)=>new Promise(r=>server.listen(address,r));
const close=server=>new Promise(r=>{server.close(r);server.closeAllConnections?.();});
for(const scenario of ['delivery','cancel-upload','cancel-model','published-model','worker-exit','seal-restart','seal-early-restart','settlement-restart','admission-disconnect','silo-outage'])test('S3 gateway HTTP '+scenario+' preserves SSE and rejects cross-task access',{
 skip:process.platform==='win32'||process.getuid?.()!==0?'Linux/root Unix-socket integration fixture':false,
},async()=>{
 const cancelUpload=scenario==='cancel-upload';
 const published=['published-model','settlement-restart','admission-disconnect'].includes(scenario);
 const gatewayEntry=scenario==='published-model'?process.env.TEST_LOCAL_GATEWAY_ENTRY:undefined;
 const root=await mkdtemp(join(tmpdir(),'s3gw-')),workers=join(root,'w'),states=join(root,'s');
 await mkdir(workers);await mkdir(states);
 const hash=x=>createHash('sha256').update(x).digest('hex');
 let workerId,brokerToken,manifest,nativeState='idle',stopped=false,blockUploads=false,releaseUpload;
 let storageAvailable=scenario!=='silo-outage';
 const uploads=[];
 const s3=http.createServer(async(req,res)=>{
  if(req.method==='GET'){res.writeHead(206,{'content-range':'bytes 0-3/4'});res.end('a,b\n');return;}
  const chunks=[];for await(const b of req)chunks.push(b);
  if(!storageAvailable){res.writeHead(503);res.end();return;}
  uploads.push({path:req.url,bytes:Buffer.concat(chunks)});
  if(blockUploads)await new Promise(r=>{releaseUpload=r;});
  res.writeHead(200,{'x-amz-version-id':'immutable-'+uploads.length});res.end();
 });await listen(s3,{host:'127.0.0.1',port:0});
 const origin='http://127.0.0.1:'+s3.address().port;
 let activeProviderRequest,observedUpstream,gateway,gatewayBase,providerCalls=0,admitModel=true;
 const provider=http.createServer(async(req,res)=>{
  providerCalls++;
  const chunks=[];for await(const data of req)chunks.push(data);
  const body=JSON.parse(Buffer.concat(chunks)),mode=body.messages[0].content;
  observedUpstream={path:req.url,authorization:req.headers.authorization,model:body.model};
  if(gatewayEntry&&!body.stream){
   res.writeHead(200,{'content-type':'application/json'});
   return res.end(JSON.stringify({id:'offline',object:'chat.completion',model:body.model,created:1,
    choices:[{index:0,message:{role:'assistant',content:'fixture reply'},finish_reason:'stop'}]}));
  }
  if(gatewayEntry){
   res.writeHead(200,{'content-type':'text/event-stream'});
   for(const choice of [{index:0,delta:{role:'assistant',content:'fixture delta'}},{index:0,delta:{},finish_reason:'stop'}]){
    res.write('data: '+JSON.stringify({id:'offline',object:'chat.completion.chunk',created:1,model:body.model,choices:[choice]})+'\n\n');
   }
   return res.end('data: [DONE]\n\n');
  }
  res.on('close',()=>{if(activeProviderRequest?.mode===mode)activeProviderRequest.closed=true;});
  if(mode.startsWith('hold-'))activeProviderRequest={mode,closed:false};
  if(mode==='hold-headers')return;
  res.writeHead(200,{'content-type':'text/event-stream'});
  if(mode==='hold-no-delta'){res.write(': heartbeat\n\n');return;}
  res.write('data: '+JSON.stringify({choices:[{delta:{content:'fixture delta'}}]})+'\n\n');
  if(mode==='hold-idle')return;
  if(mode==='reset')activeProviderRequest={mode,interrupt:()=>res.destroy()};
  else res.end('data: [DONE]\n\n');
 });await listen(provider,{host:'127.0.0.1',port:0});
 let token,settlementAvailable=scenario!=='settlement-restart',failNativeDelivery=scenario==='seal-early-restart';
 const authorityPaths=[], modelAdmissions=[],modelSettlements=[];
 const authority=http.createServer(async(req,res)=>{
  if(req.headers.authorization!=='Bearer '+token){res.writeHead(403);res.end();return;}
  authorityPaths.push(req.url);
  if(req.url.endsWith('/model-admission')||req.url.endsWith('/model-settlement')){
   const chunks=[];for await(const chunk of req)chunks.push(chunk);
   const value=JSON.parse(Buffer.concat(chunks));
   if(req.url.endsWith('/model-settlement')){
    modelSettlements.push(value);res.writeHead(settlementAvailable?204:503);res.end();return;
   }
   modelAdmissions.push(value);
   if(scenario==='admission-disconnect'){res.destroy();return;}
   res.writeHead(admitModel?204:403);res.end();return;
  }
  res.setHeader('content-type','application/json');
  res.end(JSON.stringify(req.url.endsWith('/model')?{model:'qwen-3.6-plus',
   baseUrl:gatewayBase??('http://127.0.0.1:'+provider.address().port+'/v1'),apiKey:'gateway-fixture-key-long'}:{packages:[]}));
 });await listen(authority,{host:'127.0.0.1',port:0});
 const native=http.createServer(async(req,res)=>{
  const chunks=[];for await(const b of req)chunks.push(b);const b=JSON.parse(Buffer.concat(chunks).toString()||'{}');let value;
  if(req.url==='/health/ready')value={ready:true};
  else if(req.url==='/v1/sessions')value={sessionId:'native-session'};
  else if(req.url.endsWith('/status'))value={status:nativeState,pendingPermissions:[]};
  else if(req.url.endsWith('/message-receipt'))value={messageId:req.headers['idempotency-key'],
   receipt:nativeState==='idle'?null:{status:'accepted',taskId:'native-task'}};
  else if(req.url.endsWith('/messages')){
   nativeState='turn_completed';value={status:'accepted'};
   await writeFile(join(workers,workerId,'workspace','business.py'),'fixture');
  }else if(req.url.endsWith('/seal')){nativeState='sealed';value={status:nativeState};}
  else if(req.url.endsWith('/delivery')){
   if(failNativeDelivery){failNativeDelivery=false;res.writeHead(503);res.end('{}');return;}
   manifest={schemaVersion:'coding_node_delivery.v1',deliveryVersion:b.deliveryVersion,
    artifacts:[{path:'business.py',type:'SOURCE',size:7,sha256:hash('fixture')}]};value=manifest;
  }else if(req.url.endsWith('/cancel')){nativeState='cancelled';value={status:nativeState};}
  else{res.writeHead(404);res.end('{}');return;}
  res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(value));
 });
 const executor=http.createServer(async(req,res)=>{
  const chunks=[];for await(const b of req)chunks.push(b);const b=JSON.parse(Buffer.concat(chunks).toString()||'{}');let value;
  if(req.url==='/internal/workers'){
   workerId=b.workerId;brokerToken=b.brokerToken;
   for(const dir of ['workspace','state/native-session','bridge'])await mkdir(join(workers,workerId,dir),{recursive:true});
   await writeFile(join(workers,workerId,'state/native-session/events.jsonl'),JSON.stringify({eventId:'1',eventType:'tool.failed',payload:{stderr:'TypeError: fixture'}})+'\n'+JSON.stringify({eventId:'2',eventType:'delivery.sealed',payload:{localStopConfirmed:true}})+'\n');
   await listen(native,join(workers,workerId,'bridge/worker.sock'));
   value={Id:'1'.repeat(64),resources:{enforced:true}};
  }else if(req.url.endsWith('/stop')){stopped=true;value={containerId:'1'.repeat(64),running:false};}
  else value={State:{Running:!stopped,Status:stopped?'exited':'running'},Config:{Labels:{'cline.workerId':workerId}}};
  res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(value));
 });await listen(executor,join(root,'executor.sock'));
 const cfg={workerImage:'sha256:'+'1'.repeat(64),workerRuntime:'cline-python311',workerRoot:workers,stateRoot:states,
  signingKey:'k'.repeat(64),executor:{socketPath:join(root,'executor.sock'),token:'private'},host:'127.0.0.1',port:0,maxWorkers:1,maxPerTenant:1,
  tenants:{tenant:{users:['user'],workspaces:{project:'/unused'},models:['model'],datasets:[],tools:['run_commands']}},
  modelProfiles:{model:{model:'fixture-model',baseUrl:'http://127.0.0.1:'+provider.address().port,apiKey:'never-used'}},
  s3:{allowedOrigins:[origin],maxInputBytes:1024,maxOutputBytes:1024}};
 const portServer=http.createServer();await listen(portServer,{host:'127.0.0.1',port:0});cfg.port=portServer.address().port;await close(portServer);
 const issuer='i'.repeat(64);
 if(published){
  const authorityBase='http://127.0.0.1:'+authority.address().port+'/internal/runtime/native-coding';
  cfg.issuerKeys={tenant:issuer};
  Object.assign(cfg.tenants.tenant,{runtimeModelAuthority:authorityBase+'/model',
   mcp:{authMode:'native-runtime',url:authorityBase+'/mcp'}});
 }
 const configFile=join(root,'config.json');await writeFile(configFile,JSON.stringify(cfg));
 let logs='';
 function launch(){
  const broker=spawn(process.execPath,[fileURLToPath(new URL('../src/governance-server.mjs',import.meta.url))],{env:{...process.env,CLINE_GOVERNANCE_CONFIG:configFile},stdio:['ignore','pipe','pipe']});
  broker.stdout.on('data',b=>logs+=b);broker.stderr.on('data',b=>logs+=b);
  return broker;
 }
 let child=launch();
 const base='http://127.0.0.1:'+cfg.port;
 const inputs=[{path:'input/data.csv',size:4,sha256:hash('a,b\n'),objectVersion:'v1',url:origin+'/bucket/tenant/task/run/input/data.csv?versionId=v1'}];
 const contract={version:2,tenantId:'tenant',userId:'user',taskId:'task',sessionId:'session',workspaceRef:'project',modelProfile:'model',
  budgetPolicy:{status:'DEFERRED_STAGE_2_REVIEW'},toolPermissions:['run_commands'],dataPermissions:[],networkPolicy:'broker-only',expiresAt:Date.now()+60000,
  runtimeProfile:'native-python-silo-v1',deliveryBinding:{tenantId:'tenant',taskId:'task',runId:'run',inputSnapshotVersion:'fixed'},
  s3:{origin,inputPrefix:'/bucket/tenant/task/run/input/',outputPrefix:'/bucket/tenant/task/run/output/',inputManifestSha256:inputManifestHash(inputs)}};
 if(published){
  contract.runtimeModel={logicalName:'Site gateway',providerModel:'qwen-3.6-plus'};
  contract.modelProfile=runtimeModelProfile(contract.runtimeModel);
  // Runtime checks the issued token byte-for-byte. JSON formatting must not affect the binding.
  const encoded=Buffer.from(JSON.stringify(contract,null,1)).toString('base64url');
  token=encoded+'.'+createHmac('sha256',issuer).update(encoded).digest('base64url');
 }else token=signContract(contract,cfg.signingKey);
 async function request(path,body,authorization=token){
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+authorization,'content-type':'application/json','idempotency-key':'fixed'},body:body===undefined?undefined:JSON.stringify(body)});
  return {status:response.status,body:await response.json()};
 }
 function modelRequest(mode){
  const payload=JSON.stringify({model:published?'qwen-3.6-plus':'fixture-model',stream:true,messages:[{role:'user',content:mode}]});
  let client;
  const state={receivedResponse:false,text:''};
  const result=new Promise(resolve=>{
   client=http.request({socketPath:join(workers,workerId,'bridge/broker.sock'),path:'/model/v1/chat/completions',method:'POST',
    headers:{authorization:'Bearer '+brokerToken,'content-type':'application/json','content-length':Buffer.byteLength(payload)}},response=>{
     state.receivedResponse=true;
     response.on('error',()=>resolve(response.statusCode));response.on('end',()=>resolve(response.statusCode));
     response.on('data',chunk=>state.text+=chunk);
    });
   client.on('error',()=>resolve(null));client.setTimeout(3000,()=>client.destroy());client.end(payload);
  });
  return {client,result,state};
 }
 const receipts=async()=>{
  const path=join(states,'workers',workerId,'provider-events.jsonl');
  const lines=(await readFile(path,'utf8').catch(e=>{if(e.code==='ENOENT')return '';throw e;})).trim();
  return lines.split('\n').filter(Boolean).map(line=>JSON.parse(line)).filter(event=>event.eventType==='model.request.completed');
 };
 async function until(check){for(let i=0;i<150;i++){const value=await check();if(value)return value;await pause(20);}throw new Error('Timed out waiting for the Broker fixture');}
 async function restartBroker(){
  const exited=once(child,'exit');
  child.kill('SIGKILL');
  await exited;
  child=launch();
  await until(()=>fetch(base+'/health/live').then(r=>r.ok,()=>false));
 }
 try{
  if(gatewayEntry){
   const listeners=[http.createServer(),http.createServer()];
   await Promise.all(listeners.map(server=>listen(server,{host:'127.0.0.1',port:0})));
   const [apiPort,adminPort]=listeners.map(server=>server.address().port);
   await Promise.all(listeners.map(close));
   gatewayBase='http://127.0.0.1:'+apiPort+'/v1';
   const admin='http://127.0.0.1:'+adminPort+'/admin/';
   const providerOrigin='http://127.0.0.1:'+provider.address().port;
   gateway=spawn(process.execPath,[gatewayEntry],{env:{...process.env,GATEWAY_HTTP_ONLY:'true',
    HTTP_TEST_PORT:String(apiPort),ADMIN_PORT:String(adminPort),DATA_DIR:join(root,'gateway-data'),
    CONFIG_ENCRYPTION_KEY:'c'.repeat(64),ADMIN_PASSWORD:'fixture-only-admin-password',
    GATEWAY_API_KEY:'gateway-fixture-key-long',UPSTREAM_ALLOWED_ORIGINS:providerOrigin},
    stdio:['ignore','ignore','inherit']});
   await until(async()=>fetch(gatewayBase.replace(/\/v1$/,'/ready')).then(r=>r.ok,()=>false));
   const json={'content-type':'application/json'};
   const login=await fetch(admin+'login',{method:'POST',headers:json,body:JSON.stringify({password:'fixture-only-admin-password'})});
   assert.equal(login.status,200);
   const headers={...json,cookie:login.headers.get('set-cookie').split(';')[0]};
   const current=await (await fetch(admin+'config',{headers})).json();
   const saved=await fetch(admin+'config',{method:'PUT',headers,body:JSON.stringify({version:current.version,config:{
    services:[{id:'offline',name:'Offline upstream',origin:providerOrigin,apiKey:'fixture-upstream-auth'}],
    models:[{alias:'qwen-3.6-plus',service:'offline',upstream:'/models/Qwen3.6-35B-A3B'}]
   }})});
   assert.equal(saved.status,200,await saved.text());
   const direct=await fetch(gatewayBase+'/chat/completions',{method:'POST',headers:{...json,
    authorization:'Bearer gateway-fixture-key-long'},body:JSON.stringify({model:'qwen-3.6-plus',stream:false,
     messages:[{role:'user',content:'Direct path'}]})});
   assert.equal(direct.status,200);
   assert.equal((await direct.json()).choices[0].message.content,'fixture reply');
  }
  for(let i=0;i<100;i++){try{if((await fetch(base+'/health/live')).ok)break;}catch{}if(child.exitCode!==null)throw Error(logs);await pause(20);}
  assert.equal((await request('/v1/sessions',{inputs})).status,201);
  for(let i=0;i<100;i++){const r=await request('/v1/sessions/session/status');if(['READY','START_FAILED','RECOVERABLE'].includes(r.body.workerState))break;await pause(20);}
  const status=(await request('/v1/sessions/session/status')).body;
  assert.equal(status.workerState,'READY',JSON.stringify(status)+' '+logs);
  assert.equal(await readFile(join(workers,workerId,'workspace/input/data.csv'),'utf8'),'a,b\n');
  if(published){
   assert.deepEqual(authorityPaths,['/internal/runtime/native-coding/model','/internal/runtime/native-coding/skills']);
   const chat=modelRequest('published HTTP model');
   if(scenario==='admission-disconnect'){
    assert.equal(await chat.result,502);
    const recovered=await until(()=>modelSettlements[0]);
    assert.deepEqual(recovered,{requestId:modelAdmissions[0].requestId,providerTokens:null,neverDispatched:true});
    assert.equal(providerCalls,0);
    assert.deepEqual(await receipts(),[]);
    return;
   }
   assert.equal(await chat.result,200);
   assert.match(chat.state.text,/fixture delta/);
   assert.match(chat.state.text,/\[DONE\]/);
   assert.deepEqual(observedUpstream,{path:'/v1/chat/completions',
    authorization:gatewayEntry?'Bearer fixture-upstream-auth':'Bearer gateway-fixture-key-long',
    model:gatewayEntry?'/models/Qwen3.6-35B-A3B':'qwen-3.6-plus'});
   const receipt=await until(async()=>(await receipts())[0]);
   assert.equal(receipt.status,'SUCCEEDED');
   const settled=await until(()=>modelSettlements[0]);
   assert.equal(receipt.requestId,modelAdmissions[0].requestId);
   assert.deepEqual(settled,{requestId:receipt.requestId,providerTokens:null,neverDispatched:false});
   if(scenario==='settlement-restart'){
    settlementAvailable=true;
    const attempts=modelSettlements.length;
    await restartBroker();
    await until(()=>modelSettlements.length>attempts);
    assert.deepEqual(modelSettlements.at(-1),settled);
    const queue=join(states,'workers',workerId,'model-settlement',receipt.requestId+'.json');
    await until(()=>readFile(queue).then(()=>false,e=>e.code==='ENOENT'));
    assert.equal(providerCalls,1);
    return;
   }
   const callsBeforeExpiry=providerCalls;
   admitModel=false;
   assert.equal(await modelRequest('expired identity').result,403);
   assert.equal(providerCalls,callsBeforeExpiry);
   assert.ok(!JSON.stringify(status).includes(token)&&!JSON.stringify(status).includes('gateway-fixture-key'));
   return;
  }
  if(scenario==='worker-exit'){
   await close(native);
   assert.equal((await request('/v1/sessions/session/status')).status,503);
   stopped=true;
   const exit=await request('/v1/sessions/session/status');
   assert.equal(exit.status,200);
   assert.equal(exit.body.workerState,'STOPPED');
   assert.equal(exit.body.stopProof.running,false);
   assert.equal(exit.body.revoked,true);
   return;
  }
  if(scenario==='cancel-model'){
   const idle=modelRequest('hold-idle');
   await until(()=>idle.state.receivedResponse);
   assert.equal((await request('/v1/sessions/session/cancel',{})).status,202);
   await idle.result;await until(()=>activeProviderRequest.closed);
   const cancelled=await until(async()=>(await receipts())[0]);
   assert.equal(cancelled.errorCode,'RUNTIME_TASK_STOPPED');assert.notEqual(cancelled.responseTiming.firstModelDeltaMs,null);
   return;
  }
  if(!cancelUpload&&!['seal-restart','seal-early-restart','silo-outage'].includes(scenario)){
   assert.equal(await modelRequest('missing-usage').result,200);
   const success=await until(async()=>(await receipts())[0]);
   assert.equal(success.status,'SUCCEEDED');assert.equal(success.usage,null);
   const interrupted=modelRequest('reset');
   await until(()=>interrupted.state.receivedResponse);
   activeProviderRequest.interrupt();
   await interrupted.result;
   const reset=await until(async()=>(await receipts())[1]);
   assert.equal(reset.httpStatus,200);assert.equal(reset.errorCode,'UPSTREAM_RESPONSE_STREAM_INTERRUPTED');
   assert.equal(reset.failurePhase,'RESPONSE_BODY');assert.notEqual(reset.responseTiming.firstModelDeltaMs,null);
   const hold=modelRequest('hold-headers');
   await until(()=>activeProviderRequest?.mode==='hold-headers');
   hold.client.destroy();await hold.result;await until(()=>activeProviderRequest.closed);
   const aborted=await until(async()=>(await receipts())[2]);
   assert.equal(aborted.failurePhase,'BEFORE_HEADERS');assert.equal(aborted.errorCode,'CORE_DISCONNECTED');
   const noDelta=modelRequest('hold-no-delta');
   await until(()=>noDelta.state.receivedResponse);
   noDelta.client.destroy();await noDelta.result;await until(()=>activeProviderRequest.closed);
   const empty=await until(async()=>(await receipts())[3]);
   assert.equal(empty.httpStatus,200);assert.equal(empty.errorCode,'CORE_DISCONNECTED');
   assert.equal(empty.responseTiming.firstModelDeltaMs,null);
   await close(provider);
   assert.equal(await modelRequest('connect-failure').result,502);
   const refused=await until(async()=>(await receipts())[4]);
   assert.equal(refused.failurePhase,'BEFORE_HEADERS');assert.equal(refused.errorCode,'UPSTREAM_CONNECTION_FAILURE');
  }
  const other=signContract({...contract,taskId:'other',deliveryBinding:{...contract.deliveryBinding,taskId:'other'}},cfg.signingKey);
  assert.equal((await request('/v1/sessions/session/status',undefined,other)).status,404);
  assert.equal((await request('/v1/sessions/session/message-receipt',undefined,other)).status,404);
  assert.deepEqual((await request('/v1/sessions/session/message-receipt')).body,{messageId:'fixed',receipt:null});
  assert.equal((await request('/v1/sessions/session/messages',{text:'protocol fixture'})).status,200);
  assert.deepEqual((await request('/v1/sessions/session/message-receipt')).body,
   {messageId:'fixed',receipt:{status:'accepted',taskId:'native-task'}});
  if(scenario==='seal-restart'){
   const blocked=join(workers,workerId,'sealed-native-events.json');
   await mkdir(blocked);
   assert.equal((await request('/v1/sessions/session/delivery',{deliveryVersion:'v1'})).status,503);
   assert(stopped);
   await rm(blocked,{recursive:true});
   await close(native);
   await restartBroker();
  }
  if(scenario==='seal-early-restart'){
   assert.equal((await request('/v1/sessions/session/delivery',{deliveryVersion:'v1'})).status,503);
   assert.equal(nativeState,'sealed');
   assert.equal(stopped,false);
   await restartBroker();
   assert.equal((await request('/v1/sessions/session/status')).body.deliveryInProgress,true);
  }
  const delivered=await request('/v1/sessions/session/delivery',{deliveryVersion:'v1'});assert.equal(delivered.status,200,JSON.stringify(delivered));assert(stopped);
  assert.equal((await request('/v1/sessions/session/acceptance',{manifestSha256:jsonHash(manifest),status:'PASSED',validationRef:'unverified'})).status,409);
  const sse=await fetch(base+'/v1/sessions/session/events',{headers:{authorization:'Bearer '+token}});
  assert.match(await sse.text(),/TypeError: fixture/);
  const output=origin+contract.s3.outputPrefix;
  const plan={manifestSha256:jsonHash(manifest),artifacts:[{path:'business.py',parts:[{url:output+'source.part0'}]}],manifestUrl:output+'manifest.json',evidenceUrl:output+'events.json'};
  if(scenario==='silo-outage'){
   assert.equal((await request('/v1/sessions/session/upload',plan)).status,503);
   const checkpoint=(await request('/v1/sessions/session/status')).body;
   assert.equal(checkpoint.taskState,'SEALED_PENDING_UPLOAD');
   assert.equal(checkpoint.uploadReceipt,null);
   await restartBroker();
   storageAvailable=true;
  }
  if(!cancelUpload){
   assert.equal((await request('/v1/sessions/session/upload',plan)).body.acceptanceStatus,'PENDING_RUNTIME_VALIDATION');
   assert.equal((await request('/v1/sessions/session/status')).body.taskState,'DELIVERED_PENDING_RUNTIME_VALIDATION');
   assert.equal((await request('/v1/sessions/session/upload',plan)).status,200);assert.equal(uploads.length,3);
   const acceptance={manifestSha256:jsonHash(manifest),status:'PASSED',validationRef:'runtime-independent-verifier:fixture'};
   assert.equal((await request('/v1/sessions/session/acceptance',acceptance)).body.taskState,'RUNTIME_ACCEPTED');
   assert.equal((await request('/v1/sessions/session/acceptance',acceptance)).status,200);
   assert.equal((await request('/v1/sessions/session/acceptance',{...acceptance,status:'FAILED'})).status,409);
   assert.equal((await request('/v1/sessions/session/cancel',{})).status,409);
   return;
  }
  blockUploads=true;const pending=request('/v1/sessions/session/upload',plan);
  for(let i=0;i<100&&!releaseUpload;i++)await pause(10);
  assert.equal((await request('/v1/sessions/session/cancel',{})).status,202);releaseUpload?.();
  assert.notEqual((await pending).status,200);
  const state=await request('/v1/sessions/session/status');assert.equal(state.body.revoked,true);assert.equal(state.body.uploadReceipt,null);
  assert.equal((await request('/v1/sessions/session/upload',plan)).status,403);
  assert.equal((await request('/v1/sessions/session/acceptance',{manifestSha256:jsonHash(manifest),status:'PASSED',validationRef:'late'})).status,403);
 }finally{
  if(child.exitCode===null&&child.signalCode===null){
   const exited=new Promise(r=>child.once('exit',r));child.kill('SIGTERM');await exited;
  }
  if(gateway&&gateway.exitCode===null){gateway.kill('SIGTERM');await new Promise(r=>gateway.once('exit',r));}
  releaseUpload?.();await Promise.all([close(native),close(executor),close(s3),close(provider),close(authority)]);await rm(root,{recursive:true,force:true});
 }
});
