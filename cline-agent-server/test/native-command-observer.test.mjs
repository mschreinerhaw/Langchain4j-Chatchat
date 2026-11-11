import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {forkNativeWorker} from '../src/native-process.mjs';
test('Host observation preserves actual child callback/streams and reports its real failed exit',async()=>{
 const root=await mkdtemp(join(tmpdir(),'native-command-observation-'));
 const script=join(root,'fixture.cjs');
 await writeFile(script,`const cp=require('node:child_process');cp.execFile(process.execPath,['-e',"process.stdout.write('original-output');process.stderr.write('original-error');process.exit(7)"],(error,stdout,stderr)=>process.send({kind:'fixture.result',exitCode:error?.code??0,stdout,stderr},()=>process.exit(0)));`);
 async function run(observe){
  const messages=[];
  const child=forkNativeWorker(script,[],{execArgv:observe?['--import',new URL('../src/native-command-observer.mjs',import.meta.url).href]:[],stdio:['ignore','pipe','pipe','ipc']});
  child.on('message',message=>messages.push(message));await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>code===0?resolve():reject(Error('Fixture exited '+code)));});return messages;
 }
 try{
  const original=await run(false),observed=await run(true);
  assert.deepEqual(observed.find(m=>m.kind==='fixture.result'),original.find(m=>m.kind==='fixture.result'));
  const records=observed.filter(m=>m.kind==='host.command').map(m=>m.event);
  assert.equal(records.filter(e=>e.type==='started').length,1);const complete=records.find(e=>e.type==='completed');
  assert.equal(complete.exitCode,7);assert.equal(complete.signal,null);assert(complete.durationMs>=0);
  assert.equal(Buffer.concat(records.filter(e=>e.type==='data'&&e.stream==='stdout').map(e=>e.data)).toString(),'original-output');
  assert.equal(Buffer.concat(records.filter(e=>e.type==='data'&&e.stream==='stderr').map(e=>e.data)).toString(),'original-error');
 }finally{await rm(root,{recursive:true,force:true});}
});
