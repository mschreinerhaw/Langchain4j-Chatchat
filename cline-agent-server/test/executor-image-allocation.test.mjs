import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import http from 'node:http';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {once} from 'node:events';
import test from 'node:test';
import {signContract} from '../src/task-contract.mjs';
import {WorkerExecutorClient} from '../src/worker-executor-client.mjs';

for (const [workerImage, restoredImage] of [
 ['sha256:'+'a'.repeat(64), 'sha256:'+'a'.repeat(64)],
 ['sha256:5d6e19b5d1b70a328f95fc65769176b30eb45283f7842b6fce43ee0a7e4e7ef3',
  'sha256:14fd1c48edaf477985ac63aaddc292c28efe99469c2bf97fd71f14b19c4c718b'],
]) test('Missing image leaves no anchor; allocation succeeds after loading '+restoredImage.slice(0,16), {
 skip: process.platform !== 'linux' || process.getuid() !== 0, timeout: 12000,
}, async t => {
 const root=mkdtempSync(join(tmpdir(),'executor-image-'));
 const template=join(root,'template');mkdirSync(template);
 const apiToken='private-executor', signingKey='s'.repeat(64);
 const dockerSocket=join(root,'docker.sock'), socketPath=join(root,'executor.sock'), workersRoot=join(root,'workers');
 let available=false, created=null;
 const engine=http.createServer(async(req,res)=>{
  const path=req.url.replace(/^\/v1\.45/,'');
  function reply(code,value){res.writeHead(code,{'content-type':'application/json'});res.end(JSON.stringify(value));}
  if(path==='/info')return reply(200,{ID:'test-daemon',SecurityOptions:['name=rootless'],CgroupDriver:'cgroupfs',MemoryLimit:true,CpuCfsQuota:true,PidsLimit:true});
  if(path.startsWith('/containers/json?'))return reply(200,[]);
  if(path.startsWith('/images/')){
   const found=available&&path==='/images/'+restoredImage+'/json';
   return reply(found?200:404,found?{Id:restoredImage}:{message:'fixture image absent'});
  }
  if(path.startsWith('/containers/create?')){
   const input=[];for await(const chunk of req)input.push(chunk);
   const spec=JSON.parse(Buffer.concat(input));
   assert.equal(spec.Image,restoredImage);
   created={Id:'test-container',Config:{Labels:spec.Labels},HostConfig:spec.HostConfig,State:{Status:'created',Running:false}};
   return reply(201,{Id:created.Id});
  }
  if(path==='/containers/test-container/json')return reply(200,created);
  return reply(404,{message:'fixture container absent'});
 });
 engine.listen(dockerSocket);await once(engine,'listening');
 t.after(()=>{engine.close();rmSync(root,{recursive:true,force:true});});
 const config=join(root,'config.json');
 writeFileSync(config,JSON.stringify({workerImage,expectedDaemonId:'test-daemon',ownershipKey:'o'.repeat(64),instanceId:'fixture',
  apiToken,signingKey,dockerSocket,socketPath,workersRoot,hostWorkersRoot:workersRoot,controlRoot:join(root,'control'),
  maxWorkers:1,maxPerTenant:1,resources:{memoryBytes:1024**3,nanoCpus:1000000000,pidsLimit:128,cpuSet:''},
  modelNames:{model:'fixture'},tenants:{alpha:{users:['alice'],workspaces:{project:template},models:['model'],tools:[],datasets:[]}},
 }));
 const child=spawn(process.execPath,[new URL('../src/worker-executor.mjs',import.meta.url).pathname],{
  env:{...process.env,CLINE_EXECUTOR_CONFIG:config},stdio:['ignore','pipe','inherit'],
 });
 t.after(()=>child.kill());
 await Promise.race([once(child.stdout,'data'),once(child,'exit').then(([code])=>{throw Error('Executor exited '+code);})]);
 const grant=signContract({version:1,tenantId:'alpha',userId:'alice',sessionId:'session',taskId:'task',workspaceRef:'project',
  modelProfile:'model',toolPermissions:[],dataPermissions:[],networkPolicy:'broker-only',budgetPolicy:{status:'DEFERRED_STAGE_2_REVIEW'},
  expiresAt:Date.now()+60000,
 },signingKey);
 const input={workerId:randomUUID(),resourceProfile:'standard',apiToken:'a'.repeat(64),brokerToken:'b'.repeat(64),editTool:'editor'};
 const client=new WorkerExecutorClient({socketPath,token:apiToken});
 await assert.rejects(client.create(grant,input),{status:503,executorCode:'WORKER_IMAGE_MISSING'});
 assert.equal(existsSync(join(workersRoot,input.workerId)),false);
 assert.equal(created,null);
 available=true;
 assert.equal((await client.create(grant,input)).Id,'test-container');
});
