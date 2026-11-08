// Isolated, real MCP execution target. This is NOT an Agent or an operation authority.
// Admission is exclusively a signed binding already committed in the Java Runtime journal.
import http from 'node:http';
import {readFileSync,writeFileSync,openSync,closeSync,fsyncSync,renameSync,mkdirSync,existsSync,readdirSync} from 'node:fs';
import {join} from 'node:path';
import {spawn} from 'node:child_process';
import {createHash,createHmac,timingSafeEqual} from 'node:crypto';
const cfg=JSON.parse(readFileSync(process.env.CLINE_REMOTE_CONFIG));mkdirSync(cfg.stateRoot,{recursive:true,mode:0o700});
const processes=new Map(),delay=ms=>new Promise(r=>setTimeout(r,ms));
const canonical=x=>JSON.stringify(x&&typeof x==='object'?Array.isArray(x)?x.map(v=>JSON.parse(canonical(v))):Object.fromEntries(Object.keys(x).sort().map(k=>[k,JSON.parse(canonical(x[k]))])):x);
const hash=x=>createHash('sha256').update(x).digest('hex');
function equal(a,b){const x=Buffer.from(a??''),y=Buffer.from(b??'');return x.length===y.length&&timingSafeEqual(x,y);}
function path(op){if(!/^cline-[a-f0-9]{64}$/.test(op))throw Error('Invalid operation');return join(cfg.stateRoot,op+'.json');}
function get(op){const p=path(op);return existsSync(p)?JSON.parse(readFileSync(p)):null;}
function save(v){const p=path(v.binding.operationId),tmp=p+'.tmp';writeFileSync(tmp,JSON.stringify(v),{mode:0o600});let fd=openSync(tmp,'r');fsyncSync(fd);closeSync(fd);renameSync(tmp,p);fd=openSync(cfg.stateRoot,'r');fsyncSync(fd);closeSync(fd);}
function scoped(v,identity){if(!v||['tenantId','userId','sessionId','taskId'].some(k=>v.binding.identity[k]!==identity[k]))throw Error('Operation scope rejected');return v;}
function fence(binding,token){let highest=0;for(const name of readdirSync(cfg.stateRoot).filter(n=>/^cline-[a-f0-9]{64}\.json$/.test(n))){const v=get(name.slice(0,-5));if(['tenantId','userId','sessionId','taskId'].every(k=>v.binding.identity[k]===binding.identity[k]))highest=Math.max(highest,v.controlFence??v.binding.fencingToken);}if(Number(token)<highest)throw Error('Stale remote fencing token');}
function receipt(v,state,detail={}){if(v.receipt)return v;v.state=state;v.executorStopped=true;v.receipt={operationId:v.binding.operationId,identity:v.binding.identity,argumentsHash:v.binding.argumentsHash,fencingToken:v.binding.fencingToken,epoch:v.binding.epoch,toolName:v.binding.toolName,startedAt:v.startedAt??null,finishedAt:new Date().toISOString(),stopConfirmed:true,cancelRequested:!!v.cancelRequested,...detail};save(v);return v;}
async function java(method,v){const r=await fetch(cfg.authorityUrl+'/python/'+method,{method:'POST',headers:{authorization:'Bearer '+cfg.mcpSecret,'content-type':'application/json'},body:JSON.stringify({identity:v.binding.identity,operationId:v.binding.operationId})});const body=await r.json();if(!r.ok)throw Error('Managed Python '+r.status+': '+JSON.stringify(body));return body;}
function ticks(pid){try{return readFileSync('/proc/'+pid+'/stat','utf8').split(') ')[1].split(' ')[19];}catch{return null;}}
function alive(v){return v.pid&&ticks(v.pid)===v.startTicks;}
async function refresh(v){
 if(v.receipt)return v;
 if(v.binding.toolName==='python_execute'){
  let p;try{p=await java('get',v);}catch{return v;}
  v.python=p;if(p.executorStopped&&p.receipt){return receipt(v,p.cancelRequested?'CANCELLED':p.exitCode===0?'COMPLETED':'FAILED',{childOperationId:v.binding.childOperationId,python:p.receipt,pythonReceiptSha256:p.receiptSha256});}save(v);
 }else if(v.pid&&!alive(v)){
  const resultFile=join(cfg.stateRoot,v.binding.operationId+'.result');
  if(existsSync(resultFile))return receipt(v,'COMPLETED',{pid:v.pid,startTicks:v.startTicks,resultSha256:hash(readFileSync(resultFile)),exitCode:0});
  if(v.cancelRequested)return receipt(v,'CANCELLED',{pid:v.pid,startTicks:v.startTicks,processAbsent:true});
  // A vanished process without a persisted result is ambiguous, never successful.
  v.state='UNKNOWN';save(v);
 }
 return v;
}
async function cancel(v){if(v.receipt)return v;v.cancelRequested=true;v.state='CANCEL_REQUESTED';save(v);
 if(v.binding.toolName==='python_execute'){v.python=await java('cancel',v);save(v);for(let n=0;n<60;n++){v=await refresh(get(v.binding.operationId));if(v.receipt)return v;await delay(200);}return v;}
 if(!v.pid){if(v.cancelTombstone)return receipt(v,'CANCELLED',{noStart:true,dispatchPrevented:true});v.state='UNKNOWN';save(v);return v;}
 if(alive(v)){try{process.kill(-v.pid,'SIGTERM');}catch{}for(let n=0;n<30&&alive(v);n++)await delay(100);if(alive(v))try{process.kill(-v.pid,'SIGKILL');}catch{}}
 for(let n=0;n<30&&alive(v);n++)await delay(100);
 if(alive(v))return v;
 return refresh(get(v.binding.operationId));
}
async function execute(binding,args){let v=get(binding.operationId);
 fence(binding,binding.fencingToken);
 if(v){scoped(v,binding.identity);if(v.binding.argumentsHash!==binding.argumentsHash)throw Error('Immutable operation conflict');return wait(v);}
 // Own execution-node idempotency index; authoritative intent is already in Java.
 v={binding,state:'PENDING',executorStopped:false,admittedAt:new Date().toISOString()};save(v);
 if(binding.toolName==='python_execute'){const p=await java('start',v);v=get(binding.operationId);v.python=p;v.state='RUNNING';v.startedAt=new Date().toISOString();save(v);}
 else{
  const ms=Math.min(120000,Math.max(1000,Number(args.durationMs)||30000));
  const target=join(cfg.stateRoot,binding.operationId+'.result');
  // Actual asynchronous CPU/I/O process, independent of the Cline Worker lifetime.
  const source=`const fs=require('node:fs'); let n=0;const i=setInterval(()=>{n++;fs.appendFileSync(${JSON.stringify(target+'.progress')},n+'\\n')},100);setTimeout(()=>{clearInterval(i);fs.writeFileSync(${JSON.stringify(target)},JSON.stringify({ticks:n,completedAt:Date.now()}));},${ms});`;
  const child=spawn(process.execPath,['-e',source],{detached:true,stdio:'ignore'});processes.set(binding.operationId,child);v.pid=child.pid;v.startTicks=ticks(child.pid);v.state='RUNNING';v.startedAt=new Date().toISOString();save(v);child.on('exit',()=>processes.delete(binding.operationId));
 }
 return wait(v);
}
async function wait(v){for(let n=0;n<1500;n++){v=await refresh(get(v.binding.operationId));if(v.receipt)return {content:[{type:'text',text:JSON.stringify(v.receipt)}],isError:v.state==='FAILED'};await delay(100);}throw Error('Remote operation unresolved; query status');}
function verify(meta,args){const b=meta?.binding;if(!b||!equal(meta.signature,createHmac('sha256',cfg.dispatchSecret).update(canonical(b)).digest('hex'))||hash(canonical(args))!==b.argumentsHash)throw Error('Trusted durable binding required');path(b.operationId);return b;}
async function body(req){let b='';for await(const chunk of req){b+=chunk;if(b.length>1048576)throw Error('Body limit');}return JSON.parse(b||'{}');}
const tools=[{name:'long_mcp',description:'Execute a real long-running remote process. Returns immutable execution receipt.',inputSchema:{type:'object',properties:{datasetId:{type:'string'},durationMs:{type:'integer'}},required:['datasetId','durationMs']}},{name:'python_execute',description:'Run real Python source in the existing Managed Python Docker Sandbox. Source may sleep for a cancellation test.',inputSchema:{type:'object',properties:{datasetId:{type:'string'},source:{type:'string'}},required:['datasetId','source']}}];
const server=http.createServer(async(req,res)=>{const send=(s,b)=>{res.writeHead(s,{'content-type':'application/json'});res.end(JSON.stringify(b));};try{
 if(req.url==='/health/live'&&req.method==='GET')return send(200,{live:true});
 const control=req.url.startsWith('/control/');if(!equal(req.headers.authorization,'Bearer '+(control?cfg.remoteControlSecret:cfg.mcpToken)))return send(401,{error:'credential rejected'});
 const q=await body(req);
 if(control){let v=get(q.operationId);if(req.url==='/control/cancel'&&!v){
   // Fetch the ALREADY committed binding, not an ID from JSON-RPC or client input.
   const r=await fetch(cfg.authorityUrl+'/remote/binding',{method:'POST',headers:{authorization:'Bearer '+cfg.mcpSecret,'content-type':'application/json'},body:JSON.stringify(q)});if(!r.ok)throw Error('No durable cancellation binding');const binding=await r.json();v={binding,state:'PENDING',executorStopped:false,cancelTombstone:true};save(v);
  }if(!v)return send(200,{state:'UNKNOWN',executorStopped:false});scoped(v,q.identity);if(req.url==='/control/cancel'){fence(v.binding,q.fencingToken);v.controlFence=Number(q.fencingToken);save(v);}v=await (req.url==='/control/cancel'?cancel(v):refresh(v));return send(200,v);}
 if(req.url!=='/mcp')return send(404,{error:'route'});
 let result;if(q.method==='initialize')result={protocolVersion:'2024-11-05',capabilities:{tools:{}},serverInfo:{name:'stage2-real-remote-executor',version:'1'}};
 else if(q.method==='notifications/initialized')return send(202,{});
 else if(q.method==='tools/list')result={tools};else if(q.method==='ping')result={};
 else if(q.method==='tools/call'){const b=verify(q.params?._meta,q.params.arguments);if(b.toolName!==q.params.name)throw Error('Tool binding mismatch');result=await execute(b,q.params.arguments);}
 else throw Error('Unsupported MCP method');send(200,{jsonrpc:'2.0',id:q.id,result});
 }catch(e){send(409,{error:e.message});}});
for(const name of readdirSync(cfg.stateRoot).filter(n=>n.endsWith('.json'))){const v=get(name.slice(0,-5));if(!v.receipt&&v.state==='PENDING'){v.state='UNKNOWN';save(v);}}
server.listen(cfg.port??18402,'0.0.0.0',()=>console.log('Real remote MCP executor ready'));
