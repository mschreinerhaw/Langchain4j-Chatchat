import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createMonitor} from '../monitor/server.mjs';
import {Backend} from '../monitor/backend.mjs';
import {parseCursor,cursorId,projectEvent,safe} from '../monitor/projection.mjs';

test('Native projection preserves durable identities and rejects invalid cursors without inferring success',()=>{
 assert.deepEqual(parseCursor(cursorId({native:12,business:37})),{native:12,business:37});assert.throws(()=>parseCursor('n-1b0'));
 const e=projectEvent({eventId:'24',timestamp:'2026-11-09T00:00:00Z',type:'native.event',payload:{eventType:'tool.failed',payload:{toolName:'apply_patch',toolCallId:'call-1',input:{patch:'private-source'},output:'failed'}}},'business','runtime:worker');
 assert.equal(e.id,'business:24');assert.equal(e.type,'tool.failed');assert.equal(e.payload.toolCallId,'call-1');assert(!JSON.stringify(e).includes('private-source'));
 assert.deepEqual(safe({token:'Bearer abc',apiToken:'control',authorization:'secret',unknownUsage:null}),{token:'Bearer [REDACTED]',unknownUsage:null});
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

test('Browser auth keeps credentials server-side and streams actual retained history with resumable cursor',async()=>{
 const root=await mkdtemp(join(tmpdir(),'native-monitor-http-'));const state=join(root,'native');await mkdir(join(state,'session'),{recursive:true});
 await writeFile(join(state,'session/session.json'),JSON.stringify({id:'session',status:'turn_completed',workspaceRef:'project'}));
 await writeFile(join(state,'session/events.jsonl'),JSON.stringify({eventId:'1',eventType:'turn.completed',timestamp:'2026-11-09',payload:{deliveryValidated:false}})+'\n');
 const originServer=http.createServer((req,res)=>{res.setHeader('content-type','application/json');if(req.url==='/api/v1/auth/login')res.end(JSON.stringify({code:200,data:{token:'private-jwt',user:{id:'owner',tenantId:'tenant'}}}));else res.end(JSON.stringify({status:'turn_completed',pendingPermissions:[]}));});
 await new Promise(r=>originServer.listen(0,'127.0.0.1',r));const origin='http://127.0.0.1:'+originServer.address().port;
 const server=createMonitor({runtimeUrl:origin,operator:{userId:'owner',tenantId:'tenant'},native:{url:origin,token:'private-native-control',stateDir:state,workspaces:['project']},models:[]});await new Promise(r=>server.listen(0,'127.0.0.1',r));const url='http://127.0.0.1:'+server.address().port;
 try{
  assert.equal((await fetch(url+'/api/sessions')).status,401);
  const login=await fetch(url+'/api/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username:'test',password:'test'})});const cookie=login.headers.get('set-cookie').split(';')[0];assert(!JSON.stringify(await login.json()).includes('private-jwt'));
  const headers={cookie};const list=await (await fetch(url+'/api/sessions',{headers})).json();assert.equal(list.items[0].status,'turn_completed');assert(!JSON.stringify(list).includes('private-native-control'));
  const denied=await fetch(url+'/api/sessions',{method:'POST',headers:{...headers,origin:'http://other.invalid','content-type':'application/json'},body:'{}'});assert.equal(denied.status,403);
  const control=new AbortController();const sse=await fetch(url+'/api/sessions/native%3Asession/events',{headers,signal:control.signal});const reader=sse.body.getReader();let text='';while(!text.includes('event: cursor')){const next=await reader.read();text+=new TextDecoder().decode(next.value);}assert(text.includes('native:1'));assert(text.includes('id: n1b0'));control.abort();
  assert.equal((await fetch(url+'/api/sessions/native%3Asession/events?after=bad',{headers})).status,400);
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));originServer.closeAllConnections();await new Promise(r=>originServer.close(r));await rm(root,{recursive:true,force:true});}
});
