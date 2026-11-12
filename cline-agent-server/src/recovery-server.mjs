// Narrow recovery control plane. No Agent loop, model endpoint, arbitrary
// Docker access or task dispatch. It survives a Native Gateway container exit.
import http from 'node:http';
import {readFileSync,mkdirSync,writeFileSync,renameSync} from 'node:fs';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {verifyIssuedContract} from './task-issuer.mjs';
import {signContract} from './task-contract.mjs';
import {WorkerExecutorClient} from './worker-executor-client.mjs';
import {Journal} from './journal.mjs';
const cfg=JSON.parse(readFileSync(process.env.CLINE_GOVERNANCE_CONFIG,'utf8'));
if(!cfg.executor||cfg.dockerSocket)throw Error('Restricted Executor required');
const executor=new WorkerExecutorClient(cfg.executor),root=join(cfg.stateRoot,'revocations');mkdirSync(root,{recursive:true,mode:0o700});
const audit=new Journal(join(root,'recovery-audit.jsonl'));
const send=(res,status,data)=>{res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(data));};
http.createServer(async(req,res)=>{
 try{
  const match=req.url?.match(/^\/v1\/sessions\/([A-Za-z0-9_-]{1,96})\/recovery-stop$/);
  if(req.method!=='POST'||!match)return send(res,404,{message:'Recovery route unavailable'});
  const c=verifyIssuedContract((req.headers.authorization??'').replace(/^Bearer /,''),cfg,{allowExpired:true});
  if(c.version!==2||c.sessionId!==match[1])return send(res,403,{message:'Recovery task binding differs'});
  let size=0;for await(const chunk of req){size+=chunk.length;if(size>1024)throw Object.assign(Error('Request too large'),{status:413});}
  // Host routing checks this marker as well as the Executor's independent
  // serialized fence. It contains no model credentials or signed URLs.
  const marker=join(root,c.sessionId+'.json'),temporary=marker+'.'+randomUUID()+'.tmp';writeFileSync(temporary,JSON.stringify({taskId:c.taskId,sessionId:c.sessionId,revokedAt:new Date().toISOString()}),{mode:0o600});renameSync(temporary,marker);
  const proof=await executor.request('POST','/internal/tasks/revoke',signContract(c,cfg.signingKey),{});
  const stopped=marker+'.'+randomUUID()+'.tmp';writeFileSync(stopped,JSON.stringify({taskId:c.taskId,sessionId:c.sessionId,revokedAt:new Date().toISOString(),stopProof:proof}),{mode:0o600});renameSync(stopped,marker);
  audit.append({type:'recovery.stopped',payload:{tenantId:c.tenantId,taskId:c.taskId,sessionId:c.sessionId,proof}});
  send(res,200,{sessionId:c.sessionId,stopProof:proof,source:'RESTRICTED_EXECUTOR_RECOVERY'});
 }catch(e){send(res,e.status??503,{message:e.status?e.message:'Recovery stop unconfirmed'});}
}).listen(Number(process.env.CLINE_RECOVERY_PORT??18362),'127.0.0.1',()=>console.log('Restricted Native recovery control ready'));
