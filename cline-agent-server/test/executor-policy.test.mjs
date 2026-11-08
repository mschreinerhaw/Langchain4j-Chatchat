import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdtempSync,mkdirSync,writeFileSync,symlinkSync,rmSync,rmdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {signContract} from '../src/task-contract.mjs';
import {validateCreate,taskGrant,buildSpec,verifyOwned,assertAnchors} from '../src/worker-executor-policy.mjs';
const key='k'.repeat(64),cfg={signingKey:key,ownershipKey:'o'.repeat(64),instanceId:'executor-review',workerImage:'sha256:'+'a'.repeat(64),hostWorkersRoot:'/isolated/workers',resources:{memoryBytes:2147483648,nanoCpus:2000000000,pidsLimit:256,cpuSet:'8,9'},tenants:{alpha:{users:['alice'],workspaces:{project:'/trusted/template'},models:['model'],tools:['read_files'],datasets:['dataset']}}};
const contract={version:1,tenantId:'alpha',userId:'alice',sessionId:'session',taskId:'task',workspaceRef:'project',modelProfile:'model',toolPermissions:['read_files'],dataPermissions:['dataset'],networkPolicy:'broker-only',budgetPolicy:{status:'DEFERRED_STAGE_2_REVIEW'},expiresAt:Date.now()+3600000};
const request=()=>({workerId:randomUUID(),resourceProfile:'standard',apiToken:'a'.repeat(64),brokerToken:'b'.repeat(64),editTool:'editor'});
test('Executor refuses every caller-controlled Docker parameter',()=>{
 for(const [field,value] of Object.entries({image:'alpine',Image:'alpine',root:'/',HostConfig:{Privileged:true},mounts:['/:/host'],network:'host',pidMode:'host',devices:['/dev/kvm'],Cmd:['sh'],labels:{owner:'other'},dockerPath:'/containers/create',cpuSet:'0-127',memoryBytes:0,privileged:true,namespace:'host'}))assert.throws(()=>validateCreate({...request(),[field]:value}),{status:403},field);
 for(const resourceProfile of ['unlimited','../standard','STANDARD'])assert.throws(()=>validateCreate({...request(),resourceProfile}),{status:403});
});
test('Worker specification and ownership are server policy, including on privileged service calls',()=>{
 const b=request(),built=buildSpec(contract,b,cfg),s=built.spec;assert.equal(s.Image,cfg.workerImage);assert.equal(s.User,'1000:1000');assert.equal(s.HostConfig.NetworkMode,'none');assert.equal(s.HostConfig.ReadonlyRootfs,true);assert.deepEqual(s.HostConfig.CapDrop,['ALL']);assert.equal(s.HostConfig.Memory,2147483648);assert(s.HostConfig.Binds.every(p=>p.startsWith('/isolated/workers/'+b.workerId+'/')));assert(s.HostConfig.Binds.every(p=>!p.includes('docker.sock')));
 const info={Config:{Labels:s.Labels}};assert.equal(verifyOwned(info,contract,b.workerId,cfg),info);assert.throws(()=>verifyOwned(info,{...contract,userId:'bob'},b.workerId,cfg),{status:404});assert.throws(()=>verifyOwned(info,contract,randomUUID(),cfg),{status:404});
});
test('Executor verifies grants independently and expired grants authorize observation/stop only',()=>{
 assert.deepEqual(taskGrant(signContract(contract,key),cfg),contract);assert.throws(()=>taskGrant(signContract({...contract,userId:'bob'},key),cfg),{status:403});
 const expired=signContract({...contract,expiresAt:Date.now()-1000},key);assert.throws(()=>taskGrant(expired,cfg));assert.equal(taskGrant(expired,cfg,true).userId,'alice');assert.throws(()=>taskGrant(signContract(contract,'x'.repeat(64)),cfg));
});
test('symbolic-link mount anchors are rejected, even inside an otherwise permitted root',()=>{
 const root=mkdtempSync(join(tmpdir(),'cline-executor-')),id=randomUUID();
 try{mkdirSync(join(root,id));for(const d of ['workspace','state','bridge'])mkdirSync(join(root,id,d));writeFileSync(join(root,id,'config.json'),'{}');assertAnchors(root,id);rmdirSync(join(root,id,'workspace'));symlinkSync(join(root,id,'state'),join(root,id,'workspace'),'junction');assert.throws(()=>assertAnchors(root,id),{status:403});}
 finally{if(!root.startsWith(join(tmpdir(),'cline-executor-')))throw Error('Unexpected cleanup target');rmSync(root,{recursive:true,force:true});}
});
