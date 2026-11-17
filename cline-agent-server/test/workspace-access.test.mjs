import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,chmodSync,chownSync,rmSync,readdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {verifyWorkspaceAccess} from '../src/workspace-access.mjs';
test('workspace probe checks create/read/rename/delete without residue',t=>{
 const root=mkdtempSync(join(tmpdir(),'workspace-access-'));t.after(()=>rmSync(root,{recursive:true,force:true}));
 verifyWorkspaceAccess(root);assert.deepEqual(readdirSync(root),[]);
});
test('Core identity rejects root-owned bind mount and succeeds after ownership repair',{skip:process.platform!=='linux'||process.getuid?.()!==0},t=>{
 const root=mkdtempSync(join(tmpdir(),'workspace-access-'));t.after(()=>rmSync(root,{recursive:true,force:true}));
 chmodSync(root,0o700);assert.throws(()=>verifyWorkspaceAccess(root,{uid:10001,gid:10001}),/WORKSPACE_ACCESS_DENIED/);
 chownSync(root,10001,10001);verifyWorkspaceAccess(root,{uid:10001,gid:10001});assert.deepEqual(readdirSync(root),[]);
 chmodSync(root,0o500);assert.throws(()=>verifyWorkspaceAccess(root,{uid:10001,gid:10001}),/WORKSPACE_ACCESS_DENIED/);
});
