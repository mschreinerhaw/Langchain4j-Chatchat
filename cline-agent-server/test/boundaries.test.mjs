import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,symlinkSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {Journal} from '../src/journal.mjs';
import {workspacePath} from '../src/host-bridge.mjs';
import {toolFailed} from '../src/tool-outcome.mjs';
import {validateRuntime} from '../src/runtime-assets.mjs';

test('missing ProtoBus descriptor fails explicitly before native or provider initialization',()=>{
 const dir=mkdtempSync(join(tmpdir(),'cline-runtime-'));try{
  writeFileSync(join(dir,'runtime-manifest.json'),JSON.stringify({sourceCommit:'a459f1d04dea030833b558be645011ab739c463b',files:[]}));
  assert.throws(()=>validateRuntime(dir),/missing required asset: cline-core.js/);
  const names=['cline-core.js','package.json','proto/descriptor_set.pb','proto/host/workspace.proto','proto/cline/task.proto'];
  const files=names.map(path=>({path,bytes:1,sha256:createHash('sha256').update('x').digest('hex')}));
  writeFileSync(join(dir,'runtime-manifest.json'),JSON.stringify({sourceCommit:'a459f1d04dea030833b558be645011ab739c463b',files}));
  writeFileSync(join(dir,'cline-core.js'),'x');writeFileSync(join(dir,'package.json'),'x');
  assert.throws(()=>validateRuntime(dir),/Runtime resource missing: proto\/descriptor_set.pb/);
  writeFileSync(join(dir,'cline-core.js'),'corrupt');
  assert.throws(()=>validateRuntime(dir),/integrity mismatch: cline-core.js/);
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('native editor size rejection and shell structured errors cannot become success events',()=>{
 assert.equal(toolFailed({output:{query:'insert:test.js',result:'',success:false,error:'Editor input too large'}}),true);
 assert.equal(toolFailed({output:[{query:'build',result:'',error:'Command exited 1'}]}),true);
 assert.equal(toolFailed({output:{result:'Edited file'}}),false);
});
test('durable replay resumes after the last delivered ID across journal reopen',()=>{
 const dir=mkdtempSync(join(tmpdir(),'cline-journal-'));try{
  const file=join(dir,'events.jsonl'),a=new Journal(file);
  a.append({eventType:'permission.requested',payload:{id:'approval'}});a.append({eventType:'permission.resolved',payload:{decision:'deny'}});
  const b=new Journal(file);assert.deepEqual(b.after('1').map(e=>e.eventType),['permission.resolved']);assert.equal(b.append({eventType:'turn.completed'}).eventId,'3');assert.throws(()=>b.after('NaN'));
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('host path boundary rejects traversal and symlinks escaping the authorized workspace',()=>{
 const dir=mkdtempSync(join(tmpdir(),'cline-path-'));try{
  const root=join(dir,'workspace');mkdirSync(root);writeFileSync(join(root,'a.txt'),'a');writeFileSync(join(dir,'private.txt'),'secret');
  assert.equal(workspacePath(root,'a.txt'),join(root,'a.txt'));assert.throws(()=>workspacePath(root,'../private.txt'));
  if(process.platform!=='win32'){symlinkSync(join(dir,'private.txt'),join(root,'escape'));assert.throws(()=>workspacePath(root,'escape'));}
 }finally{rmSync(dir,{recursive:true,force:true});}
});
