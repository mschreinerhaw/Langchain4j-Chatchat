import test from 'node:test';
import assert from 'node:assert/strict';
import {Readable} from 'node:stream';
import {proxyFrontend} from '../src/frontend-proxy.mjs';
const config={url:'http://internal-hub:8080',token:'host-only-token'};
function req(path,method='GET',body=''){const r=Readable.from([Buffer.from(body)]);r.url=path;r.method=method;return r}
function res(){return {chunks:[],writeHead(s,h){this.status=s;this.headers=h},write(b){this.chunks.push(Buffer.from(b))},end(b){if(b)this.write(b)}}}
test('frontend proxy denies unregistered paths and cross-task job reads before any fetch',async()=>{
 let calls=0;const opts={fetcher:()=>{calls++;throw Error('Must not fetch')}};
 for(const path of ['/frontend/../../etc/passwd','/frontend/api/v1/preview/12345678-1234-1234-1234-123456789abc/bundle','/frontend/api/v1/resources?target=http://private','/frontend/assets/secret'])
  await assert.rejects(proxyFrontend(req(path),res(),config,{},opts));
 assert.equal(calls,0);
});
test('creation binds job ownership and keeps global credential exclusively upstream',async()=>{
 const id='12345678-1234-1234-1234-123456789abc',task={},response=res();let saved=false;
 await proxyFrontend(req('/frontend/api/v1/preview','POST','{"html":"hello"}'),response,config,task,{save:()=>saved=true,fetcher:async(url,options)=>{
  assert.equal(url,'http://internal-hub:8080/api/v1/preview');assert.equal(options.headers.authorization,'Bearer host-only-token');assert.equal(options.redirect,'error');return new Response(JSON.stringify({id,status:'running'}),{status:202});
 }});
 assert(saved);assert.deepEqual(task.frontendJobs,[id]);assert.equal(response.status,202);assert(!Buffer.concat(response.chunks).toString().includes('host-only-token'));
 const binary=res();await proxyFrontend(req('/frontend/api/v1/preview/'+id+'/bundle'),binary,config,task,{fetcher:async()=>new Response(Buffer.from([0,1,255]),{headers:{'content-type':'application/gzip'}})});
 assert.deepEqual(Buffer.concat(binary.chunks),Buffer.from([0,1,255]));
});
test('oversized HTML is rejected before upstream; unrelated routes remain owned by existing broker',async()=>{
 await assert.rejects(proxyFrontend(req('/frontend/api/v1/preview','POST','x'.repeat(2*1024*1024+1)),res(),config,{}),/too large/);
 assert.equal(await proxyFrontend(req('/mcp'),res(),config,{}),false);
 await assert.rejects(proxyFrontend(req('/frontend/api/v1/resources'),res(),undefined,{}),/unavailable/);
});
