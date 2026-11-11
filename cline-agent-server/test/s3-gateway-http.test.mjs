// HTTP/Unix transport and fencing fixture, explicitly NOT a model/business test.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {join,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {signContract} from '../src/task-contract.mjs';
import {inputManifestHash,jsonHash} from '../src/s3-task-transport.mjs';

const pause=ms=>new Promise(r=>setTimeout(r,ms));
const listen=(server,address)=>new Promise(r=>server.listen(address,r));
const close=server=>new Promise(r=>{server.close(r);server.closeAllConnections?.();});
for(const cancelUpload of [false,true])test('S3 gateway HTTP '+(cancelUpload?'fences cancelled uploads':'records Runtime validation after immutable upload')+', preserves SSE and rejects cross-task access',{
 skip:process.platform==='win32'||process.getuid?.()!==0?'Linux/root Unix-socket integration fixture':false,
},async()=>{
 const root=await mkdtemp(join(tmpdir(),'s3gw-')),workers=join(root,'w'),states=join(root,'s');
 await mkdir(workers);await mkdir(states);
 const hash=x=>createHash('sha256').update(x).digest('hex');
 let workerId,manifest,nativeState='idle',stopped=false,blockUploads=false,releaseUpload;
 const uploads=[];
 const s3=http.createServer(async(req,res)=>{
  if(req.method==='GET'){res.writeHead(206,{'content-range':'bytes 0-3/4'});res.end('a,b\n');return;}
  const chunks=[];for await(const b of req)chunks.push(b);uploads.push({path:req.url,bytes:Buffer.concat(chunks)});
  if(blockUploads)await new Promise(r=>{releaseUpload=r;});
  res.writeHead(200,{'x-amz-version-id':'immutable-'+uploads.length});res.end();
 });await listen(s3,{host:'127.0.0.1',port:0});
 const origin='http://127.0.0.1:'+s3.address().port;
 const native=http.createServer(async(req,res)=>{
  const chunks=[];for await(const b of req)chunks.push(b);const b=JSON.parse(Buffer.concat(chunks).toString()||'{}');let value;
  if(req.url==='/health/ready')value={ready:true};
  else if(req.url==='/v1/sessions')value={sessionId:'native-session'};
  else if(req.url.endsWith('/status'))value={status:nativeState,pendingPermissions:[]};
  else if(req.url.endsWith('/messages')){
   nativeState='turn_completed';value={status:'accepted'};
   await writeFile(join(workers,workerId,'workspace','business.py'),'fixture');
  }else if(req.url.endsWith('/seal')){nativeState='sealed';value={status:nativeState};}
  else if(req.url.endsWith('/delivery')){
   manifest={schemaVersion:'coding_node_delivery.v1',deliveryVersion:b.deliveryVersion,
    artifacts:[{path:'business.py',type:'SOURCE',size:7,sha256:hash('fixture')}]};value=manifest;
  }else if(req.url.endsWith('/cancel')){nativeState='cancelled';value={status:nativeState};}
  else{res.writeHead(404);res.end('{}');return;}
  res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(value));
 });
 const executor=http.createServer(async(req,res)=>{
  const chunks=[];for await(const b of req)chunks.push(b);const b=JSON.parse(Buffer.concat(chunks).toString()||'{}');let value;
  if(req.url==='/internal/workers'){
   workerId=b.workerId;
   for(const dir of ['workspace','state/native-session','bridge'])await mkdir(join(workers,workerId,dir),{recursive:true});
   await writeFile(join(workers,workerId,'state/native-session/events.jsonl'),JSON.stringify({eventId:'1',eventType:'tool.failed',payload:{stderr:'TypeError: fixture'}})+'\n'+JSON.stringify({eventId:'2',eventType:'delivery.sealed',payload:{localStopConfirmed:true}})+'\n');
   await listen(native,join(workers,workerId,'bridge/worker.sock'));
   value={Id:'1'.repeat(64),resources:{enforced:true}};
  }else if(req.url.endsWith('/stop')){stopped=true;value={containerId:'1'.repeat(64),running:false};}
  else value={State:{Running:!stopped},Config:{Labels:{'cline.workerId':workerId}}};
  res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(value));
 });await listen(executor,join(root,'executor.sock'));
 const cfg={workerImage:'sha256:'+'1'.repeat(64),workerRuntime:'cline-python311',workerRoot:workers,stateRoot:states,
  signingKey:'k'.repeat(64),executor:{socketPath:join(root,'executor.sock'),token:'private'},host:'127.0.0.1',port:0,maxWorkers:1,maxPerTenant:1,
  tenants:{tenant:{users:['user'],workspaces:{project:'/unused'},models:['model'],datasets:[],tools:['run_commands']}},
  modelProfiles:{model:{model:'uninvoked-fixture-model',baseUrl:'http://127.0.0.1:1',apiKey:'never-used'}},
  s3:{allowedOrigins:[origin],maxInputBytes:1024,maxOutputBytes:1024}};
 const portServer=http.createServer();await listen(portServer,{host:'127.0.0.1',port:0});cfg.port=portServer.address().port;await close(portServer);
 const configFile=join(root,'config.json');await writeFile(configFile,JSON.stringify(cfg));
 const child=spawn(process.execPath,[fileURLToPath(new URL('../src/governance-server.mjs',import.meta.url))],{env:{...process.env,CLINE_GOVERNANCE_CONFIG:configFile},stdio:['ignore','pipe','pipe']});
 let logs='';child.stdout.on('data',b=>logs+=b);child.stderr.on('data',b=>logs+=b);
 const base='http://127.0.0.1:'+cfg.port;
 const inputs=[{path:'input/data.csv',size:4,sha256:hash('a,b\n'),objectVersion:'v1',url:origin+'/bucket/tenant/task/run/input/data.csv?versionId=v1'}];
 const contract={version:2,tenantId:'tenant',userId:'user',taskId:'task',sessionId:'session',workspaceRef:'project',modelProfile:'model',
  budgetPolicy:{status:'DEFERRED_STAGE_2_REVIEW'},toolPermissions:['run_commands'],dataPermissions:[],networkPolicy:'broker-only',expiresAt:Date.now()+60000,
  runtimeProfile:'native-python-silo-v1',deliveryBinding:{tenantId:'tenant',taskId:'task',runId:'run',inputSnapshotVersion:'fixed'},
  s3:{origin,inputPrefix:'/bucket/tenant/task/run/input/',outputPrefix:'/bucket/tenant/task/run/output/',inputManifestSha256:inputManifestHash(inputs)}};
 const token=signContract(contract,cfg.signingKey);
 async function request(path,body,authorization=token){
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+authorization,'content-type':'application/json','idempotency-key':'fixed'},body:body===undefined?undefined:JSON.stringify(body)});
  return {status:response.status,body:await response.json()};
 }
 try{
  for(let i=0;i<100;i++){try{if((await fetch(base+'/health/live')).ok)break;}catch{}if(child.exitCode!==null)throw Error(logs);await pause(20);}
  assert.equal((await request('/v1/sessions',{inputs})).status,201);
  for(let i=0;i<100;i++){const r=await request('/v1/sessions/session/status');if(r.body.workerState==='READY')break;await pause(20);}
  assert.equal((await request('/v1/sessions/session/status')).body.workerState,'READY',logs);
  assert.equal(await readFile(join(workers,workerId,'workspace/input/data.csv'),'utf8'),'a,b\n');
  const other=signContract({...contract,taskId:'other',deliveryBinding:{...contract.deliveryBinding,taskId:'other'}},cfg.signingKey);
  assert.equal((await request('/v1/sessions/session/status',undefined,other)).status,404);
  assert.equal((await request('/v1/sessions/session/messages',{text:'protocol fixture'})).status,200);
  const delivered=await request('/v1/sessions/session/delivery',{deliveryVersion:'v1'});assert.equal(delivered.status,200,JSON.stringify(delivered));assert(stopped);
  assert.equal((await request('/v1/sessions/session/acceptance',{manifestSha256:jsonHash(manifest),status:'PASSED',validationRef:'unverified'})).status,409);
  const sse=await fetch(base+'/v1/sessions/session/events',{headers:{authorization:'Bearer '+token}});
  assert.match(await sse.text(),/TypeError: fixture/);
  const output=origin+contract.s3.outputPrefix;
  const plan={manifestSha256:jsonHash(manifest),artifacts:[{path:'business.py',parts:[{url:output+'source.part0'}]}],manifestUrl:output+'manifest.json',evidenceUrl:output+'events.json'};
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
  child.kill('SIGTERM');await new Promise(r=>child.once('exit',r));
  releaseUpload?.();await Promise.all([close(native),close(executor),close(s3)]);await rm(root,{recursive:true,force:true});
 }
});
