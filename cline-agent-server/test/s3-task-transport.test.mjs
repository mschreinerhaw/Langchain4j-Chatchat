import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {storageGrant,objectCapability,inputManifestHash,validateS3Inputs,verifyStoppedDelivery,uploadS3Delivery,jsonHash} from '../src/s3-task-transport.mjs';

const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const config={allowedOrigins:['https://silo.example'],maxInputBytes:1024,maxOutputBytes:1024};
const inputs=[{path:'input/data/holdings.parquet',size:7,sha256:sha('parquet'),objectVersion:'v1',url:'https://silo.example/bucket/tenant-a/task-a/run-a/input/data?versionId=v1'}];
const contract={version:2,deliveryBinding:{taskId:'task-a',runId:'run-a',tenantId:'tenant-a',inputSnapshotVersion:'snapshot'},s3:{origin:'https://silo.example',inputPrefix:'/bucket/tenant-a/task-a/run-a/input/',outputPrefix:'/bucket/tenant-a/task-a/run-a/output/',inputManifestSha256:inputManifestHash(inputs)}};

test('signed input inventory binds physical files/versions independent of expiring signatures',()=>{
 assert.deepEqual(validateS3Inputs(contract,inputs,config),contract.s3);
 assert.equal(inputManifestHash(inputs),inputManifestHash(inputs.map(x=>({...x,url:x.url+'&signature=renewed'}))));
 for(const key of ['size','path','sha256','objectVersion']){
  const changed=[{...inputs[0],[key]:key==='size'?8:key==='path'?'input/data/other.parquet':key==='sha256'?'0'.repeat(64):'v2'}];
  assert.throws(()=>validateS3Inputs(contract,changed,config));
 }
 assert.throws(()=>validateS3Inputs(contract,[...inputs,inputs[0]],config));
 assert.throws(()=>validateS3Inputs(contract,inputs,{...config,maxInputBytes:6}),{status:413});
});
test('task scoped S3 capability rejects foreign tasks, tenants, origins and encoded escapes',()=>{
 const policy=storageGrant(contract,config);
 for(const url of [
  'https://silo.example/bucket/tenant-b/task-a/run-a/input/data',
  'https://silo.example/bucket/tenant-a/task-b/run-a/input/data',
  'https://other.example/bucket/tenant-a/task-a/run-a/input/data',
  'https://silo.example/bucket/tenant-a/task-a/run-a/input/%2e%2e/data',
  'https://silo.example/bucket/tenant-a/task-a/run-a/input/a%2fb',
  'https://silo.example/bucket/tenant-a/task-a/run-a/input/%252e%252e/data',
  'https://silo.example/bucket/tenant-a/task-a/run-a/input/../input/data',
  'https://user:secret@silo.example/bucket/tenant-a/task-a/run-a/input/data',
 ])assert.throws(()=>objectCapability(url,policy,'input'),{status:403});
 assert.throws(()=>storageGrant(contract,{allowedOrigins:[]}));
 assert.throws(()=>storageGrant({...contract,s3:{...policy,outputPrefix:policy.inputPrefix}},config));
});
test('sealed delivery requires stopped processes, unchanged bytes and resource limits',async()=>{
 const root=await mkdtemp(join(tmpdir(),'s3-seal-'));
 try{
  await writeFile(join(root,'business.py'),'print(7)');
  const manifest={artifacts:[{path:'business.py',type:'SOURCE',size:8,sha256:sha('print(7)')}]};
  await assert.rejects(verifyStoppedDelivery(root,manifest,{running:true},config));
  assert.equal(await verifyStoppedDelivery(root,manifest,{running:false},config),manifest);
  await assert.rejects(verifyStoppedDelivery(root,manifest,{running:false},{maxOutputBytes:7}));
  await writeFile(join(root,'business.py'),'print(8)');
  await assert.rejects(verifyStoppedDelivery(root,manifest,{running:false},config));
 }finally{await rm(root,{recursive:true,force:true});}
});
test('upload authorizes all objects before writing, retains real evidence and never accepts business success',async()=>{
 const root=await mkdtemp(join(tmpdir(),'s3-delivery-')),workspace=join(root,'workspace');
 try{
  await mkdir(workspace);await writeFile(join(workspace,'business.py'),'print(7)');
  const evidence=JSON.stringify({events:[{eventType:'tool.failed',payload:{stderr:'TypeError'}},{eventType:'turn.completed'}]});
  await writeFile(join(root,'sealed-native-events.json'),evidence);
  const manifest={schemaVersion:'coding_node_delivery.v1',artifacts:[{path:'business.py',type:'SOURCE',size:8,sha256:sha('print(7)')}]};
  const base=contract.s3.origin+contract.s3.outputPrefix;
  const plan={manifestSha256:jsonHash(manifest),artifacts:[{path:'business.py',parts:[{url:base+'source.part0'}]}],manifestUrl:base+'manifest.json',evidenceUrl:base+'events.json'};
  const writes=[];const fetcher=async(url,request)=>{writes.push({url:String(url),bytes:Buffer.from(request.body)});return new Response(null,{status:200,headers:{'x-amz-version-id':'immutable-'+writes.length}});};
  await assert.rejects(uploadS3Delivery(workspace,contract,manifest,{...plan,evidenceUrl:'https://silo.example/foreign/events'},config,{fetcher}));
  assert.equal(writes.length,0);
  const result=await uploadS3Delivery(workspace,contract,manifest,plan,config,{fetcher});
  assert.equal(result.acceptanceStatus,'PENDING_RUNTIME_VALIDATION');assert.equal(writes.length,3);
  assert.equal(writes[1].bytes.toString(),evidence);assert.equal(result.evidence.sha256,sha(evidence));
  assert.equal(result.manifest.sha256,jsonHash(manifest));
  const controller=new AbortController();controller.abort();
  await assert.rejects(uploadS3Delivery(workspace,contract,manifest,plan,config,{fetcher,signal:controller.signal}));
  assert.equal(writes.length,3);
 }finally{await rm(root,{recursive:true,force:true});}
});
