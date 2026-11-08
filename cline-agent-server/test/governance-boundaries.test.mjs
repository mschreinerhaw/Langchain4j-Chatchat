import test from 'node:test';
import assert from 'node:assert/strict';
import {signContract,verifyContract,contractHash,authorizeSession,holdsCapacity} from '../src/task-contract.mjs';
import {workerContainerSpec} from '../src/docker-engine.mjs';
const key='a'.repeat(64),contract={version:1,tenantId:'tenant-a',userId:'user-a',taskId:'task-a',sessionId:'session-a',workspaceRef:'project',modelProfile:'qwen',budgetPolicy:{status:'DEFERRED_STAGE_2_REVIEW'},toolPermissions:['read_files','editor'],dataPermissions:['dataset-a'],networkPolicy:'broker-only',expiresAt:Date.now()+3600000};
test('signed task identities reject tampering, expiry and arbitrary workspace paths',()=>{
 assert.deepEqual(verifyContract(signContract(contract,key),key),contract);
 const [body,signature]=signContract(contract,key).split('.');const forged=Buffer.from(JSON.stringify({...contract,tenantId:'tenant-b'})).toString('base64url');assert.throws(()=>verifyContract(forged+'.'+signature,key),{status:401});
 assert.throws(()=>verifyContract(signContract({...contract,expiresAt:1},key),key),{status:401});
 assert.throws(()=>verifyContract(signContract({...contract,workspaceRef:'/host/private'},key),key),{status:401});
 assert.throws(()=>verifyContract(body+'.'+signature,'b'.repeat(64)),{status:401});
});
test('session authorization binds tenant, user, task, immutable grant and revocation',()=>{
 const r={contract,contractHash:contractHash(contract)};assert.equal(authorizeSession(contract,r),r);
 for(const field of ['tenantId','userId','taskId','sessionId'])assert.throws(()=>authorizeSession({...contract,[field]:'other'},r),{status:404});
 assert.throws(()=>authorizeSession({...contract,dataPermissions:['dataset-b']},r),{status:409});
 assert.throws(()=>authorizeSession(contract,{...r,revoked:true}),{status:403});
});
test('worker launch provides no network, host ports, docker socket or shared tenant state',()=>{
 const a=workerContainerSpec({image:'sha256:pin',root:'/isolated/a',workerId:'a',sessionId:'sa',tenantId:'ta',memoryBytes:1024,cpuSet:'8,9'});
 const b=workerContainerSpec({image:'sha256:pin',root:'/isolated/b',workerId:'b',sessionId:'sb',tenantId:'tb',memoryBytes:1024,cpuSet:'10,11'});
 assert.equal(a.HostConfig.NetworkMode,'none');assert.equal(a.HostConfig.ReadonlyRootfs,true);assert.equal(a.HostConfig.PortBindings,undefined);assert.equal(a.User,'1000:1000');
 assert(!a.HostConfig.Binds.some(x=>x.includes('docker.sock')||x.startsWith('/isolated/b')));assert(!b.HostConfig.Binds.some(x=>x.startsWith('/isolated/a')));assert(a.Env.every(x=>!x.includes('KEY=')));
});
test('uncertain or failed provisioning cannot free a worker slot without a stop proof',()=>{
 assert.equal(holdsCapacity({workerState:'RECOVERABLE'}),true);
 assert.equal(holdsCapacity({workerState:'START_FAILED',provisioningAttempted:true}),true);
 assert.equal(holdsCapacity({workerState:'RECOVERABLE',stopProof:{running:false}}),false);
 assert.equal(holdsCapacity({workerState:'START_FAILED',provisioningAttempted:false}),false);
 assert.equal(holdsCapacity({workerState:'COMPLETED'}),true);
});
