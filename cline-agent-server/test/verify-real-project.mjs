// Independent result check. Kept outside the model's workspace; never repairs output.
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {resolve,join} from 'node:path';
import {readFileSync} from 'node:fs';
const root=resolve(process.argv[2]);
const {createMonitorClient}=await import(pathToFileURL(join(root,'dist/monitor-client.js')));
const calls=[];const c=createMonitorClient({baseUrl:'http://example.invalid///',token:'test-token',fetch:async(url,options)=>{calls.push({url,options});return new Response('{}',{status:200});}});
const id='a'.repeat(64);
async function reject(action){const count=calls.length;await assert.rejects(async()=>action(),TypeError);assert.equal(calls.length,count,'Invalid input reached network');}
for(const n of [-1,0.5,NaN,Infinity,Number.MAX_SAFE_INTEGER+1,'1',null]){await reject(()=>c.tasks({offset:n}));await reject(()=>c.events(id,n));}
for(const n of [0,-1,501,NaN,Infinity,1.5,'2',null])await reject(()=>c.tasks({limit:n}));
for(const n of [0,-1,1001,NaN,Infinity,1.5,'2',null])await reject(()=>c.events(id,0,n));
await c.tasks({q:'华是 & 科技',offset:0,limit:500,status:undefined});
let u=new URL(calls.at(-1).url);assert.equal(u.pathname,'/v1/monitor/tasks');assert.equal(u.searchParams.get('q'),'华是 & 科技');assert(!u.searchParams.has('status'));
assert.equal(calls.at(-1).options.headers.authorization,'Bearer test-token');
await c.events(id,Number.MAX_SAFE_INTEGER,1000);u=new URL(calls.at(-1).url);assert.equal(u.searchParams.get('limit'),'1000');
await c.events(id);assert(new URL(calls.at(-1).url).searchParams.get('limit')==='100');
assert.match(readFileSync(join(root,'src/monitor-types.ts'),'utf8'),/export (?:interface|type) \w*Query/);
console.log(JSON.stringify({status:'PASS',independentChecks:'parameter bounds, encoding, omission, native task IDs and authorization',networkCalls:calls.length}));
