import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
import {gunzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
const root=new URL('../../docs/testing/evidence/cline-server-p3-stage2-20261108/',import.meta.url);
const json=p=>{const path=new URL(p,root);return JSON.parse(existsSync(path)?readFileSync(path,'utf8'):gunzipSync(readFileSync(new URL(p+'.gz',root))).toString());};
const hash=b=>createHash('sha256').update(b).digest('hex');
test('stage2 exported evidence retains every recorded byte and hash',()=>{
 for(const f of json('SHA256SUMS.json')){assert(!f.path.includes('..'));const data=readFileSync(new URL(f.path,root));assert.equal(data.length,f.bytes,f.path);assert.equal(hash(data),f.sha256,f.path);}
});
test('real native calls bind to stable scoped identities and actual stop receipts',()=>{
 for(const label of ['long_mcp','python_execute','python_execute-core-kill']){
  const [status,operations]=json('runs/native/'+label+'-final-operations.json');assert.equal(status,200);assert(operations.length>0);
  for(const op of operations){const c=op.identity;const expected='cline-'+hash(JSON.stringify([c.tenantId,c.userId,c.sessionId,c.taskId,op.nativeSessionId,op.nativeToolCallId,op.toolName]));assert.equal(op.operationId,expected);assert.equal(op.state,'CANCELLED');assert.equal(op.receipt.stopConfirmed,true);assert(op.stopProofRef.startsWith('native-mcp-receipt:'));if(op.childOperationId){assert.equal(op.receipt.python.stopConfirmed,true);assert.equal(op.receipt.python.cancelRequested,true);assert.equal(op.receipt.python.exitCode,137);}}
  for(const op of operations){const row=json('journal/reconciliation-matrix.json').find(r=>r.operationId===op.operationId);assert.equal(row.taskStatus,'CANCELLED');assert.equal(row.unresolved,false);}
 }
 const coreKill=json('runs/native/summary.json').find(x=>x.case==='official-core-kill-injected');assert.equal(coreKill.pass,true);assert.equal(coreKill.detail.outerContainerStillRunning,true);
});
test('final reconciliation is complete while initial real failures remain visible',()=>{
 for(const file of ['runs/protocol-tests.json','runs/fault-tests.json','runs/native/summary.json'])assert(json(file).every(x=>x.pass),file);
 assert(json('runs/initial-protocol-failure.json').some(x=>!x.pass));assert(json('runs/initial-native-failure/summary.json').some(x=>!x.pass));
 assert(json('journal/reconciliation-matrix.json').every(x=>!x.unresolved));assert.equal(json('source/core-source-hashes.json').unchanged,true);assert.equal(json('source/upstream-host-diff-matrix.json').length,2);
 assert.equal(json('runs/remote-credential-boundaries.json').pass,true);assert.equal(json('runs/late-observation.json').allProcessesStopped,true);
});
