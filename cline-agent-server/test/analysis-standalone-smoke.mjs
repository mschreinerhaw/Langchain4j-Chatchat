import assert from 'node:assert/strict';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {mkdtempSync,mkdirSync,readFileSync,rmSync,statSync,writeFileSync} from 'node:fs';
import {createHash,randomUUID} from 'node:crypto';
import {join} from 'node:path';
import {setTimeout as wait} from 'node:timers/promises';
const mode=process.argv[2];
assert.ok(['analysis','coding','python'].includes(mode),'Specify analysis, coding or python');
const analysis=mode!=='coding',python=mode==='python';
const toolName=python?'run_commands':'read_files';
const servicePath=process.env.CLINE_SMOKE_SERVICE_PATH??'/srv/app/src/server.mjs';
const runtimePath=process.env.CLINE_SMOKE_RUNTIME_PATH??'/opt/cline/runtime';
const root=mkdtempSync('/tmp/cline-smoke-'), workspace=join(root,'workspace');
const data=analysis?join(workspace,'input/data/0'):workspace;
mkdirSync(data,{recursive:true});
const metadata='input/data/0/data-supply-manifest.json';
if(analysis)writeFileSync(join(workspace,metadata),'{"datasets":[]}');
const marker='CLINE_READ_TAIL_4_1_27',file=join(data,'large.txt');
writeFileSync(file,Array.from({length:240},(_,i)=>"line "+i+" "+'x'.repeat(52)).join('\n')+'\n'+marker+'\n');
const coverage=join(workspace,'coverage.txt');
const callInput=python?{commands:[{command:'python3',args:['-c',
  'from pathlib import Path; import sys; data=Path(sys.argv[1]).read_bytes(); Path(sys.argv[2]).write_text(str(len(data))); print(data.decode())',file,coverage]}]}:{files:[{path:file}]};
let observed=null,offered=false;
const model=http.createServer(async(req,res)=>{
  if(req.method!=='POST'){
    res.writeHead(200,{'content-type':'application/json'});
    return res.end(JSON.stringify({object:'list',data:[{id:'isolated-smoke',object:'model'}]}));
  }
  let raw='';for await(const bytes of req)raw+=bytes;
  const input=JSON.parse(raw);
  const returned=input.messages.find(m=>m.role==='tool'&&m.tool_call_id==='call_read_smoke');
  const tool=input.tools?.find(t=>t.function?.name===toolName);
  if(tool)offered=true;
  if(returned)observed=typeof returned.content==='string'?returned.content:JSON.stringify(returned.content);
  const delta=returned?{role:'assistant',content:'Read completed.'}:{role:'assistant',tool_calls:[{index:0,id:'call_read_smoke',type:'function',function:{name:toolName,arguments:JSON.stringify(callInput)}}]};
  res.writeHead(200,{'content-type':'text/event-stream'});
  const chunk=(value,finish_reason)=>JSON.stringify({id:'chatcmpl-smoke',object:'chat.completion.chunk',created:1,model:'isolated-smoke',choices:[{index:0,delta:value,finish_reason}]});
  res.end('data: '+chunk(delta,null)+'\n\ndata: '+chunk({},returned?'stop':'tool_calls')+'\n\ndata: [DONE]\n\n');
});
await new Promise(resolve=>model.listen(18325,'127.0.0.1',resolve));
const token=randomUUID(), config=join(root,'config.json');
writeFileSync(config,JSON.stringify({sourceRoot:'/not-mounted/upstream',distDir:runtimePath,
  stateDir:join(root,'state'),apiToken:token,model:'isolated-smoke',baseUrl:'http://127.0.0.1:18325/v1',
  apiKey:'unused-isolated-smoke',workspaces:{smoke:workspace},host:'127.0.0.1',port:18326,operationContext:true,...(analysis?{analysisMetadataPath:metadata}:{}),toolPermissions:[toolName]}));
const service=spawn(process.execPath,[servicePath],{env:{...process.env,CLINE_SERVER_CONFIG:config},stdio:['ignore','inherit','inherit']});
const base='http://127.0.0.1:18326';
const request=(path,options={})=>fetch(base+path,{...options,headers:{authorization:'Bearer '+token,'content-type':'application/json',accept:'application/json',...options.headers},signal:AbortSignal.timeout(2000)});
async function until(action) {
  for (let i=0;i<120;i++) {
    assert.equal(service.exitCode,null,'Server exited before smoke passed');
    const result=await action().catch(err=>{if(err instanceof TypeError)return null;throw err;});
    if(result)return result;
    await wait(500);
  }
  throw Error('Timed out waiting for Native session');
}
try {
  const ready=await until(async()=>{const r=await request('/health/ready');return r.ok?await r.json():null;});
  assert.equal(ready.runtimeAssets.sourceCommit,'8075d2e35cf5cad67c24f180c80a05d311b74a3e');
  const live=await (await request('/health/live')).json();
  assert.match(live.version,/^4\.1\.27/);
  const created=await request('/v1/sessions',{method:'POST',body:JSON.stringify({workspaceRef:'smoke'})});
  assert.equal(created.status,201);
  const {sessionId}=await created.json(), path='/v1/sessions/'+sessionId;
  await until(async()=>{const x=await (await request(path+'/status')).json();if(x.status==='failed')throw Error('Native worker failed to start');return x.status==='idle';});
  const {events}=await (await request(path+'/events')).json();
  assert.ok(events.some(e=>e.eventType==='worker.ready'&&e.payload.native==='v4.1.27'));
  const task=await request(path+'/messages',{method:'POST',headers:{'idempotency-key':'read-smoke'},body:JSON.stringify({text:'Read large.txt and report its tail.'})});
  assert.equal(task.status,202);
  await until(async()=>{
    const x=await (await request(path+'/status')).json();
    if(x.status==='failed')throw Error('Native read turn failed');
    for(const pending of x.pendingPermissions) {
      assert.equal(pending.toolName,toolName);
      const ack=await request('/v1/permissions/'+pending.id+'/respond',{method:'POST',body:JSON.stringify({decision:'approve',reason:'Isolated read-only fixture'})});
      assert.ok(ack.ok);
    }
    return x.status==='turn_completed';
  });
  assert.ok(offered,`${toolName} was not offered to the model`);
  assert.ok(observed,'The model did not receive any tool result');
  let pythonInputBytes;
  if(python){
    pythonInputBytes=Number(readFileSync(coverage,'utf8'));
    assert.equal(pythonInputBytes,statSync(file).size);
    assert.match(observed,/REFERENCE_ONLY/);
    assert.match(observed,/AVAILABLE/);
    const evidencePath=observed.match(/\.runtime-tool-results\/[a-f0-9]{64}\.json/)?.[0];
    assert.ok(evidencePath,'The model did not receive a local evidence reference');
    const evidenceHash=createHash('sha256').update(readFileSync(join(workspace,evidencePath))).digest('hex');
    assert.equal(evidencePath,`.runtime-tool-results/${evidenceHash}.json`);
    assert.ok(!observed.includes(marker),'Python stdout reached the model');
  }else if(analysis){
    assert.ok(!observed.includes(marker),'Original data reached the model');
    assert.match(observed,/Read approved inventory|Use Python/);
  }else{
    assert.ok(observed.includes(marker),'The model did not receive the file tail');
    assert.ok(observed.length>8000,'Tool content was truncated before the model');
  }
  const stop=await request(path+'/cancel',{method:'POST',body:'{}'});
  assert.ok(stop.ok);
  const stopped=await until(async()=>{const x=await (await request(path+'/status')).json();return x.status==='cancelled'?x:null;});
  assert.equal(stopped.status,'cancelled');
  console.log(JSON.stringify({mode,version:live.version,workerReady:true,cancelled:true,toolResultCharacters:observed.length,tailMarkerReceived:observed.includes(marker),pythonInputBytes,runtimeAssets:ready.runtimeAssets}));
} finally {
  model.close();
  service.kill('SIGTERM');
  await Promise.race([new Promise(resolve=>service.once('exit',resolve)),wait(7500,{ref:false})]);
  if(service.exitCode===null&&service.signalCode===null)service.kill('SIGKILL');
  rmSync(root,{recursive:true,force:true});
}
