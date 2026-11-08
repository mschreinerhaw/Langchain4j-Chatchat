import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createMonitor} from '../monitor/server.mjs';
import {Backend} from '../monitor/backend.mjs';
import {parseCursor,cursorId,projectEvent,safe,nativeModelObservations} from '../monitor/projection.mjs';

test('Native model observations keep repeated iteration numbers scoped to their real tasks and preserve missing usage',()=>{
 const event=(id,taskId,type,payload={},second=0)=>({id,taskId,type,payload,timestamp:'2026-11-09T00:00:'+String(second).padStart(2,'0')+'Z'});
 const calls=nativeModelObservations([
  event('native:1','turn-a','model.iteration.started',{iteration:1}),
  event('native:2','turn-a','model.usage',{inputTokens:31,outputTokens:7,totalInputTokens:999},2),
  event('native:3','turn-a','turn.completed',{},3),
  event('native:4','turn-b','model.iteration.started',{iteration:1},4),
  event('native:5','turn-a','model.usage',{inputTokens:800},5),
  event('native:6','turn-b','model.error',{message:'actual Core error'},6),
  event('native:7','turn-c','model.iteration.started',{iteration:1},7),
  event('native:8','turn-c','turn.completed',{},8),
  event('native:9','turn-c','model.usage',{inputTokens:900},9)
 ],'configured-model');
 assert.equal(calls.length,3);assert.equal(calls[0].observedDurationMs,2000);assert.deepEqual(calls[0].usage,{inputTokens:31,outputTokens:7});
 assert.equal(calls[1].taskId,'turn-b');assert.equal(calls[1].state,'ERROR_REPORTED');assert.equal(calls[1].error.message,'actual Core error');assert.equal(calls[1].usage,undefined);
 assert.equal(calls[2].state,'STARTED');assert.equal(calls[2].usage,undefined);assert.equal(calls[2].observedDurationMs,undefined);assert.equal(calls[0].providerModel,undefined);
});

test('Native projection preserves durable identities and rejects invalid cursors without inferring success',()=>{
 assert.deepEqual(parseCursor(cursorId({native:12,business:37})),{native:12,business:37});assert.throws(()=>parseCursor('n-1b0'));
 const e=projectEvent({eventId:'24',timestamp:'2026-11-09T00:00:00Z',type:'native.event',payload:{eventType:'tool.failed',payload:{toolName:'apply_patch',toolCallId:'call-1',input:{patch:'private-source'},output:'failed'}}},'business','runtime:worker');
 assert.equal(e.id,'business:24');assert.equal(e.type,'tool.failed');assert.equal(e.payload.toolCallId,'call-1');assert(!JSON.stringify(e).includes('private-source'));
 assert.deepEqual(safe({token:'Bearer abc',apiToken:'control',authorization:'secret',unknownUsage:null}),{token:'Bearer [REDACTED]',unknownUsage:null});
 assert.equal(safe({capabilities:{messages:true}}).capabilities.messages,true);
});

test('Read-only journal replay survives a monitor restart and respects Runtime ownership',async()=>{
 const root=await mkdtemp(join(tmpdir(),'native-monitor-'));try{
  await mkdir(join(root,'worker'));await writeFile(join(root,'worker/run.json'),JSON.stringify({workerId:'worker',scope:{tenantId:'tenant',userId:'owner'},taskId:'task',status:'UNKNOWN',apiToken:'control'}));
  await writeFile(join(root,'worker/events.jsonl'),[1,2,3].map(eventId=>JSON.stringify({eventId:String(eventId),type:'runtime.event',payload:{type:'TOOL_FINISHED',status:'FAILED'}})+'\n').join(''));
  const cfg={businessState:root},user={userId:'owner',tenantId:'tenant'},b=new Backend(cfg);const first=await b.events(user,'runtime:worker',parseCursor());assert.equal(first.events.length,3);
  const restarted=new Backend(cfg);assert.equal((await restarted.events(user,'runtime:worker',first.cursor)).events.length,0);
  assert.equal((await restarted.sessions({...user,userId:'other'})).length,0);assert.equal((await b.sessions(user))[0].status,'UNKNOWN');
 }finally{await rm(root,{recursive:true,force:true});}
});
test('Files view includes retained execution artifacts while keeping the task owner boundary',async()=>{
 const root=await mkdtemp(join(tmpdir(),'native-monitor-assets-'));
 try{
  await mkdir(join(root,'worker'));await writeFile(join(root,'worker/run.json'),JSON.stringify({workerId:'worker',taskId:'task',scope:{tenantId:'tenant',userId:'owner'}}));
  const backend=new Backend({businessState:root});let reads=0;
  backend.runtime=async(user,path)=>{reads++;assert.equal(user.userId,'owner');assert(path.includes('personalOnly=false'));assert(path.includes('taskId=task'));return [{id:'actual-retained-source',kind:'CODE'}];};
  assert.equal((await backend.assets({userId:'owner',tenantId:'tenant'},'runtime:worker')).length,1);
  await assert.rejects(backend.assets({userId:'other',tenantId:'tenant'},'runtime:worker'),/Task not found/);assert.equal(reads,1);
 }finally{await rm(root,{recursive:true,force:true});}
});

test('Browser auth keeps credentials server-side and streams actual retained history with resumable cursor',async()=>{
 const root=await mkdtemp(join(tmpdir(),'native-monitor-http-'));const state=join(root,'native');await mkdir(join(state,'session'),{recursive:true});
 await writeFile(join(state,'session/session.json'),JSON.stringify({id:'session',status:'turn_completed',workspaceRef:'project'}));
 await writeFile(join(state,'session/events.jsonl'),Array.from({length:200},(_,i)=>JSON.stringify({eventId:String(i+1),eventType:'task.accepted',timestamp:'2026-11-09',payload:{text:'history'.repeat(1000),deliveryValidated:false}})+'\n').join(''));
 const originServer=http.createServer((req,res)=>{res.setHeader('content-type','application/json');if(req.url==='/api/v1/enterprise/auth/login')res.end(JSON.stringify({code:200,data:{token:'private-jwt',user:{id:'owner',tenantId:'tenant'}}}));else res.end(JSON.stringify({status:'turn_completed',pendingPermissions:[]}));});
 await new Promise(r=>originServer.listen(0,'127.0.0.1',r));const origin='http://127.0.0.1:'+originServer.address().port;
 const server=createMonitor({runtimeUrl:origin,operator:{userId:'owner',tenantId:'tenant'},native:{url:origin,token:'private-native-control',stateDir:state,workspaces:['project']},models:[]});await new Promise(r=>server.listen(0,'127.0.0.1',r));const url='http://127.0.0.1:'+server.address().port;
 try{
  assert.equal((await fetch(url+'/api/sessions')).status,401);
  const login=await fetch(url+'/api/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username:'test',password:'test'})});const cookie=login.headers.get('set-cookie').split(';')[0];assert(!JSON.stringify(await login.json()).includes('private-jwt'));
  const headers={cookie};const list=await (await fetch(url+'/api/sessions',{headers})).json();assert.equal(list.items[0].status,'turn_completed');assert(!JSON.stringify(list).includes('private-native-control'));
  const denied=await fetch(url+'/api/sessions',{method:'POST',headers:{...headers,origin:'http://other.invalid','content-type':'application/json'},body:'{}'});assert.equal(denied.status,403);
  const control=new AbortController();const sse=await fetch(url+'/api/sessions/native%3Asession/events',{headers,signal:control.signal});const reader=sse.body.getReader();let text='';while(!text.includes('event: cursor')){const next=await reader.read();assert(!next.done,'SSE replay ended before persisted cursor');text+=new TextDecoder().decode(next.value);}assert(text.includes('native:200'));assert(text.includes('id: n200b0'));control.abort();
  assert.equal((await fetch(url+'/api/sessions/native%3Asession/events?after=bad',{headers})).status,400);
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));originServer.closeAllConnections();await new Promise(r=>originServer.close(r));await rm(root,{recursive:true,force:true});}
});
