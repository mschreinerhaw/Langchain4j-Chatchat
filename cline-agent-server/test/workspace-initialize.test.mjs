import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,chmodSync,statSync,readFileSync,symlinkSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {spawnSync} from 'node:child_process';
import {initializeWorkspace} from '../src/workspace-initialize.mjs';
import {verifyWorkspaceAccess} from '../src/workspace-access.mjs';
const skip=process.platform!=='linux'||process.getuid?.()!==0;
test('root-owned mounted workspace is repaired automatically and initialization is repeatable',{skip},t=>{
 const root=mkdtempSync(join(tmpdir(),'workspace-init-'));t.after(()=>rmSync(root,{recursive:true,force:true}));
 mkdirSync(join(root,'output'),{mode:0o500});writeFileSync(join(root,'output','report.txt'),'original',{mode:0o400});
 mkdirSync(join(root,'input'),{mode:0o555});writeFileSync(join(root,'input','data.txt'),'frozen',{mode:0o444});
 assert.throws(()=>verifyWorkspaceAccess(root,{uid:10001,gid:10001}),/ACCESS_DENIED/);
 for(let i=0;i<2;i++){
  initializeWorkspace(root,{uid:10001,gid:10001});verifyWorkspaceAccess(root,{uid:10001,gid:10001});
  const result=spawnSync(process.execPath,['-e',"require('fs').appendFileSync(process.argv[1],'!')",join(root,'output','report.txt')],{uid:10001,gid:10001});assert.equal(result.status,0);
 }
 assert.equal(readFileSync(join(root,'output','report.txt'),'utf8'),'original!!');
 assert.equal(statSync(join(root,'input')).mode&0o777,0o555);assert.equal(statSync(join(root,'input','data.txt')).uid,0);
});
test('unsafe entries cannot cause ownership changes outside workspace',{skip},t=>{
 const root=mkdtempSync(join(tmpdir(),'workspace-init-')),outside=mkdtempSync(join(tmpdir(),'workspace-outside-'));t.after(()=>{rmSync(root,{recursive:true,force:true});rmSync(outside,{recursive:true,force:true});});
 symlinkSync(outside,join(root,'escape'));assert.throws(()=>initializeWorkspace(root,{uid:10001,gid:10001}),/Unsafe/);
 assert.equal(statSync(root).uid,0);assert.equal(statSync(outside).uid,0);
});
