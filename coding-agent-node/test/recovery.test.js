import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const contract={schemaVersion:'runtime_os.coding.v1',taskId:'restore-task',
  scope:{tenantId:'tenant',userId:'owner',runId:'run'},objective:{instruction:'Calculate'},
  inputSnapshotRef:'input:immutable',workspaceRef:'workspace:immutable',
  authorization:{allowedTools:['python_execute'],allowedSideEffects:['CODE_EXECUTION'],
    allowedDataRefs:['c'+'a'.repeat(63)],networkPolicy:'NONE'},
  outputContract:{requiredFiles:['rows.jsonl']},budget:{maxModelCalls:4}};
const worker=fileURLToPath(new URL('../fixtures/recovery-worker.mjs',import.meta.url));
test('real SDK survives abnormal process exit via complete checkpoint without replaying Python or model ordinals',()=>{
  const first=spawnSync(process.execPath,[worker],{input:JSON.stringify({contract}),encoding:'utf8',timeout:10000});
  assert.equal(first.status,73,first.stderr);
  const crash=JSON.parse(first.stdout);
  assert.deepEqual(crash.operations,['model-1','python']);
  assert.equal(crash.checkpoint.modelOrdinal,1);
  const second=spawnSync(process.execPath,[worker],{input:JSON.stringify({contract,checkpoint:crash.checkpoint}),encoding:'utf8',timeout:10000});
  assert.equal(second.status,0,second.stderr);
  const continued=JSON.parse(second.stdout);
  assert.equal(continued.result.status,'completed');
  assert.deepEqual(continued.operations,['model-2']);
  assert.match(continued.result.text,/Continued/);
});
