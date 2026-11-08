// Fault injection only: forwards real HTTP requests/results, or drops their transport.
// Never constructs a tool result or an execution receipt.
import http from 'node:http';
import {readFileSync,appendFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const cfg=JSON.parse(readFileSync(process.env.CLINE_REMOTE_CONFIG));
const delay=ms=>new Promise(r=>setTimeout(r,ms));
http.createServer(async(req,res)=>{
 let mode;try{mode=readFileSync(cfg.stateRoot+'/fault-mode','utf8').trim();}catch{mode='pass';}
 let request='';for await(const b of req)request+=b;
 const log=x=>appendFileSync(cfg.stateRoot+'/fault-proxy.jsonl',JSON.stringify({at:Date.now(),mode,requestSha256:createHash('sha256').update(request).digest('hex'),...x})+'\n');
 if(mode==='drop-request'){log({forwarded:false});res.writeHead(503);return res.end('injected transport loss before forwarding');}
 try{const actual=await fetch('http://cline-p32-remote:18402/mcp',{method:'POST',headers:{authorization:req.headers.authorization,'content-type':'application/json',accept:'application/json, text/event-stream'},body:request});const bytes=Buffer.from(await actual.arrayBuffer());log({forwarded:true,actualStatus:actual.status,responseSha256:createHash('sha256').update(bytes).digest('hex')});
 if(mode==='drop-response'){res.writeHead(502);return res.end('injected loss after actual remote response');}
 if(mode==='delay-response')await delay(3000);
 res.writeHead(actual.status,{'content-type':actual.headers.get('content-type')??'application/json'});res.end(bytes);
 }catch(e){log({transportError:e.message});res.writeHead(502);res.end('upstream transport error');}
}).listen(18403,'0.0.0.0');
