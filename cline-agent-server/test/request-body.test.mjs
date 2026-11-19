import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {CONTROL_BODY_LIMIT,NATIVE_MODEL_BODY_LIMIT,readJsonBody} from '../src/request-body.mjs';

test('Native model route can read an incident-sized request while control requests retain their cap',async t=>{
 const server=http.createServer(async(req,res)=>{
  try{
   const value=await readJsonBody(req,req.url==='/model'?NATIVE_MODEL_BODY_LIMIT:undefined);
   res.end(JSON.stringify({chars:value.text.length}));
  }catch(e){
   res.writeHead(e.status,{Connection:'close'});
   res.end(JSON.stringify({bytes:e.bytes,limit:e.limit}));
  }
 });
 server.listen(0,'127.0.0.1');
 await once(server,'listening');
 t.after(()=>server.close());
 const origin='http://127.0.0.1:'+server.address().port;
 const body=JSON.stringify({text:'测'.repeat(450000)});
 const good=await fetch(origin+'/model',{method:'POST',body});
 assert.equal(good.status,200);
 assert.deepEqual(await good.json(),{chars:450000});
 const control=await fetch(origin+'/control',{method:'POST',body});
 assert.equal(control.status,413);
 assert.deepEqual(await control.json(),{bytes:Buffer.byteLength(body),limit:CONTROL_BODY_LIMIT});
 const chunked=await new Promise((resolve,reject)=>{
  const req=http.request(origin+'/control',{method:'POST'},resolve);
  req.on('error',reject);
  req.write(body.slice(0,300000));
  req.end(body.slice(300000));
 });
 assert.equal(chunked.statusCode,413);
 let response='';
 for await(const chunk of chunked)response+=chunk;
 assert.equal(JSON.parse(response).limit,CONTROL_BODY_LIMIT);
});
