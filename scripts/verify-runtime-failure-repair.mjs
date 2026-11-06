// Replay the actual private server receipts without publishing their source text.
// node scripts/verify-runtime-failure-repair.mjs PRIVATE_DIR PUBLIC_RESULT.json
import {readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {prepareProgram,CodeHistory} from '../coding-agent-node/src/code-repair.js';
import {editProgram} from '../coding-agent-node/src/sdk-adapter.js';
const sha=s=>createHash('sha256').update(s).digest('hex');
const [root,output]=process.argv.slice(2);
const receipts=JSON.parse(await readFile(join(root,'receipts-private.json'),'utf8'));
const journal=JSON.parse(await readFile(join(root,'journal-latest-private.json'),'utf8'));
const proposals=receipts.flatMap(r=>(r.payload.response.toolCalls??[]).filter(t=>t.name==='python_execute').map(t=>{
  const raw=t.arguments??t.input;return typeof raw==='string'?JSON.parse(raw):raw;
}));
const initial=proposals.find(p=>p.source), failed=proposals.find(p=>p.repair?.edits?.some(e=>e.newText.length===28866));
assert.ok(initial&&failed,'Expected actual 28866-character failed patch');
const source=initial.source;
assert.equal(failed.repair.baseCodeSha256,sha(source));
const before=sha(source);
for(const apply of [(p,s)=>prepareProgram(p,s),editProgram]){
  await assert.rejects(async()=>apply(failed,source),error=>{
    assert.match(error.message,/REPAIR_SEGMENT_LIMIT/);
    assert.equal(error.details.newTextLength,28866);assert.equal(error.details.editIndex,2);
    assert.equal(error.details.anchorUnique,true);return true;
  });
}
// Reexpress the real replacement using existing exact edits. No new editor/loop.
const edits=[];let expected=source;
for(const edit of failed.repair.edits){
  const at=expected.indexOf(edit.oldText);assert.ok(at>=0&&at===expected.lastIndexOf(edit.oldText));
  expected=expected.slice(0,at)+edit.newText+expected.slice(at+edit.oldText.length);
  if(edit.newText.length<=16000){edits.push(edit);continue;}
  const first=edit.newText.slice(0,15000),rest=edit.newText.slice(15000);
  let anchor=first.slice(-160);
  // Anchors are copied from the actual replacement, never normalized.
  assert.equal(first.indexOf(anchor),first.lastIndexOf(anchor));
  edits.push({oldText:edit.oldText,newText:first},{oldText:anchor,newText:anchor+rest});
}
assert.ok(edits.every(e=>e.oldText.length<=16000&&e.newText.length<=16000));
const input={repair:{...failed.repair,edits}};
assert.equal(prepareProgram(input,source),expected);
const actual=await editProgram(input,source);assert.equal(actual,expected);
assert.equal(sha(source),before);
const history=new CodeHistory(journal.contract);
history.record(source,initial,{scriptVersion:failed.repair.parentCodeVersion},'offline-original');
const checkpoint=JSON.stringify(history.snapshot());
assert.throws(()=>history.propose(failed),/REPAIR_SEGMENT_LIMIT/);
assert.equal(JSON.stringify(history.snapshot()),checkpoint);
assert.throws(()=>history.propose({repair:{...input.repair,parentCodeVersion:999}}),/PATCH_REQUIRED/);
assert.throws(()=>prepareProgram({repair:{...input.repair,baseCodeSha256:'0'.repeat(64)}},source),/hash mismatch/);
assert.equal(history.propose(input),expected);
history.record(actual,input,{scriptVersion:failed.repair.parentCodeVersion+1},'offline-segmented');
assert.equal(history.versions.at(-1).parentHash,before);
assert.equal(new CodeHistory(journal.contract,history.snapshot()).currentSource,expected);
const result={schemaVersion:'runtime-data-repair-regression.v1',mode:'OFFLINE_REAL_FAILURE_RECEIPT',
  taskId:journal.contract.taskId,inputSnapshotRef:journal.contract.inputSnapshotRef,
  receiptFileSha256:sha(await readFile(join(root,'receipts-private.json'))),
  originalCodeSha256:before,expectedCodeSha256:sha(expected),sdkCodeSha256:sha(actual),
  originalEditLengths:failed.repair.edits.map(e=>({old:e.oldText.length,new:e.newText.length})),
  segmentedEditLengths:edits.map(e=>({old:e.oldText.length,new:e.newText.length})),
  parentVersion:failed.repair.parentCodeVersion,resultVersion:history.versions.at(-1).version,
  exactBytesPreserved:true,atomicRejection:true,staleHashRejected:true,staleVersionRejected:true,
  checkpointRestored:true,pythonExecuted:false,businessAcceptance:false};
await writeFile(output,JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));
