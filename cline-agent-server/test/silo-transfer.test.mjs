import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {downloadInput,uploadParts,transferChunkBytes} from '../src/silo-transfer.mjs';
const digest=b=>createHash('sha256').update(b).digest('hex');
test('an empty snapshot still requires a real readable immutable S3 object',async()=>{
 const root=await mkdtemp(join(tmpdir(),'silo-empty-'));
 const input={path:'input/empty.csv',size:0,sha256:digest(Buffer.alloc(0)),objectVersion:'empty-v1',url:'https://silo.invalid/empty?versionId=empty-v1'};
 try{
  await assert.rejects(downloadInput(root,input,{fetcher:async()=>new Response(null,{status:403})}),/unavailable/);
  await assert.rejects(readFile(join(root,input.path)),{code:'ENOENT'});
  await downloadInput(root,input,{fetcher:async()=>new Response(null,{status:200})});
  assert.equal((await readFile(join(root,input.path))).length,0);
 }finally{await rm(root,{recursive:true,force:true});}
});
test('ranged input retries incomplete transport and verifies exact SHA-256 before exposing bytes',async()=>{
 const root=await mkdtemp(join(tmpdir(),'silo-transfer-')),data=Buffer.alloc(transferChunkBytes+13,42),ranges=[];let first=true;
 const server=createServer((req,res)=>{const [,start,end]=/bytes=(\d+)-(\d+)/.exec(req.headers.range);ranges.push([Number(start),Number(end)]);
  res.writeHead(206,{'content-range':`bytes ${start}-${end}/${data.length}`});
  if(first){first=false;res.end(data.subarray(Number(start),Number(end)));}else res.end(data.subarray(Number(start),Number(end)+1));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try{
  const input={path:'input/data/original.parquet',size:data.length,sha256:digest(data),objectVersion:'immutable-1',url:`http://127.0.0.1:${server.address().port}/object?versionId=immutable-1`};
  const receipt=await downloadInput(root,input);assert.equal(receipt.sha256,input.sha256);assert.deepEqual(await readFile(join(root,input.path)),data);assert.equal(ranges.length,3);
  await assert.rejects(downloadInput(root,{...input,sha256:'0'.repeat(64),path:'input/bad'}),/SHA-256/);
  await assert.rejects(readFile(join(root,'input/bad')),e=>e.code==='ENOENT');
  await assert.rejects(downloadInput(root,{...input,path:'input/../../other'}),/Invalid input/);
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));await rm(root,{recursive:true,force:true});}
});
test('output upload receipts bind exact bytes; cancellation and changed artifacts cannot complete',async()=>{
 const root=await mkdtemp(join(tmpdir(),'silo-output-')),data=Buffer.from('business report');await writeFile(join(root,'report.md'),data);
 const artifact={path:'report.md',size:data.length,sha256:digest(data)},seen=[];
 const fetcher=async(_url,options)=>{seen.push(options.body);return new Response(null,{status:200,headers:{'x-amz-version-id':'version-1'}});};
 try{
  const plan={parts:[{url:'https://silo.invalid/task/part-0'}]};const receipt=await uploadParts(root,artifact,plan,{fetcher});
  assert.deepEqual(seen[0],data);assert.equal(receipt.parts[0].sha256,artifact.sha256);
  await assert.rejects(uploadParts(root,{...artifact,sha256:'0'.repeat(64)},plan,{fetcher}),/SHA-256/);
  const controller=new AbortController();controller.abort();await assert.rejects(uploadParts(root,artifact,plan,{fetcher,signal:controller.signal}),e=>e.name==='AbortError');
 }finally{await rm(root,{recursive:true,force:true});}
});
