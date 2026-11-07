import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {codeManifest} from '../src/native-workspace.js';
import {codingValueHash} from '../src/coding-hash.js';

test('Node and Java share immutable Unicode source-tree golden and lineage rules',async()=>{
  const vector=JSON.parse(await readFile(new URL('../../chatchat-common/src/test/resources/runtime/coding/native-code-vector.json',import.meta.url)));
  assert.deepEqual(codeManifest(vector.taskId,vector.files,0,null,codingValueHash(Object.values(vector.scope))),vector.manifest);
  for(const [version,parent]of[[-1,null],[1,null],[0,'a'.repeat(64)],[65,'a'.repeat(64)],[1.5,'a'.repeat(64)]])
    assert.throws(()=>codeManifest('task',{},version,parent),/lineage/);
});
