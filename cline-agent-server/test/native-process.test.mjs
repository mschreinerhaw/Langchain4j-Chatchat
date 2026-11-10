import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync,realpathSync} from 'node:fs';
import {join,dirname,basename} from 'node:path';
import {tmpdir} from 'node:os';
import {forkNativeWorker} from '../src/native-process.mjs';

test('actual child IPC retains SDK Error message and stack alongside ordinary native events',async()=>{
 const root=realpathSync(tmpdir()),dir=mkdtempSync(join(root,'native-ipc-'));
 const script=join(dir,'worker.cjs');
 writeFileSync(script,`process.send({kind:'native.event',event:{type:'agent',payload:{type:'error',error:new Error('exact native model failure'),recoverable:false}}},()=>process.disconnect());`);
 const child=forkNativeWorker(script,[],{stdio:['ignore','pipe','pipe','ipc']});
 try {
  const event=await new Promise((resolve,reject)=>{child.once('message',resolve);child.once('error',reject);child.once('exit',code=>{if(code)reject(Error('Child exited '+code));});});
  assert.equal(event.kind,'native.event');
  assert.equal(event.event.payload.recoverable,false);
  assert.ok(event.event.payload.error instanceof Error);
  assert.equal(event.event.payload.error.message,'exact native model failure');
  assert.match(event.event.payload.error.stack,/worker\.cjs/);
  await new Promise(resolve=>child.exitCode!==null?resolve():child.once('exit',resolve));
 } finally {if(child.exitCode===null)child.kill();assert.equal(dirname(realpathSync(dir)),root);assert.ok(basename(dir).startsWith('native-ipc-'));rmSync(dir,{recursive:true,force:true});}
});
