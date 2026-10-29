import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { initialProject, encodeProject, decodeProject, proposeProject, validateProject } from '../src/python-project.js';
import { CodeHistory } from '../src/code-repair.js';
const hash=x=>createHash('sha256').update(x).digest('hex');
const original={entryPath:'pkg/main.py',files:[{path:'pkg/main.py',content:'from .calc import total\r\nprint(total())\r\n'},{path:'pkg/calc.py',content:'def total(): return 7\n'},{path:'pkg/__init__.py',content:''}]};
function config(){const payload=JSON.stringify(original);return {schemaVersion:'runtime_os.python.code-project.v1',sha256:hash(payload),payloadChunks:[payload],adapterTemplate:readFileSync('../chatchat-common/src/main/resources/runtime/coding/python-project-adapter.py','utf8')};}
test('full reuse preserves exact original files, empty package and CRLF',()=>{
  const c=config(),first=proposeProject({project:{reuse:true}},null,c);
  assert.deepEqual(decodeProject(first.source,c),validateProject(original));
  assert.equal(decodeProject(first.source,c).files.find(f=>f.path==='pkg/main.py').content,original.files[0].content);
  assert.equal(initialProject(c).files.length,3);
});
test('partial reuse edits only an exact file anchor and retains unrelated functions',()=>{
  const c=config(),first=encodeProject(original,c);
  const next=proposeProject({project:{baseCodeSha256:hash(first),parentCodeVersion:1,edits:[{path:'pkg/calc.py',oldText:'return 7',newText:'return 8'}],additions:[{path:'pkg/preference.py',content:'def preference(): return "buy"\n'}]}},first,c);
  const project=decodeProject(next.source,c);assert.equal(project.files.find(f=>f.path==='pkg/main.py').content,original.files[0].content);
  assert.equal(project.files.find(f=>f.path==='pkg/calc.py').content,'def total(): return 8\n');assert.equal(project.files.length,4);
});
test('later edits retain previous repair and reject stale hashes',()=>{
  const c=config(),first=encodeProject(original,c),change={baseCodeSha256:hash(first),parentCodeVersion:1,edits:[{path:'pkg/calc.py',oldText:'return 7',newText:'return 8'}]};
  const second=proposeProject({project:change},first,c).source;
  assert.throws(()=>proposeProject({project:change},second,c),/latest/);
  const third=proposeProject({project:{reuse:true,baseCodeSha256:hash(second),parentCodeVersion:2}},second,c).source;
  assert.equal(decodeProject(third,c).files.find(f=>f.path==='pkg/calc.py').content,'def total(): return 8\n');
});
test('rejects overwrite additions, ambiguous edits, path traversal and changed adapter',()=>{
  const c=config();
  for(const project of [{additions:[{path:'pkg/main.py',content:'replacement'}]},{edits:[{path:'pkg/main.py',oldText:'missing',newText:'x'}]},{additions:[{path:'../main.py',content:'x'}]}])assert.throws(()=>proposeProject({project},null,c));
  assert.throws(()=>decodeProject(encodeProject(original,c)+'print(1)',c));
  assert.throws(()=>initialProject({...c,sha256:'0'.repeat(64)}));
});
test('restarts through the existing durable code history without losing repairs or version checks',()=>{
  const c=config(),contract={taskId:'task',inputSnapshotRef:'input'},history=new CodeHistory(contract);
  const first=proposeProject({project:{reuse:true}},null,c);history.record(history.propose(first.proposal,first.source),first.proposal,{scriptVersion:1},'op-1');history.receipt({executorStopped:true,status:'FAILED',resultAvailable:false});
  const restored=new CodeHistory(contract,structuredClone(history.snapshot()));
  const second=proposeProject({project:{parentCodeVersion:1,baseCodeSha256:hash(restored.currentSource),edits:[{path:'pkg/calc.py',oldText:'return 7',newText:'return 9'}]}},restored.currentSource,c);
  assert.equal(restored.propose(second.proposal,second.source),second.source);
  assert.throws(()=>restored.propose({...second.proposal,repair:{...second.proposal.repair,parentCodeVersion:99}},second.source),/latest/);
  restored.record(second.source,second.proposal,{scriptVersion:2},'op-2');restored.receipt({executorStopped:true,status:'FAILED',resultAvailable:false});
  const resumed=new CodeHistory(contract,structuredClone(restored.snapshot()));assert.equal(decodeProject(resumed.currentSource,c).files.find(f=>f.path==='pkg/calc.py').content,'def total(): return 9\n');assert.equal(resumed.versions.length,2);
});
test('can fill an exactly empty package marker without overwriting a nonempty file',()=>{
  const c=config(),proposed=proposeProject({project:{edits:[{path:'pkg/__init__.py',oldText:'',newText:'from .calc import total\n'}]}},null,c);
  assert.equal(decodeProject(proposed.source,c).files.find(f=>f.path==='pkg/__init__.py').content,'from .calc import total\n');
  assert.throws(()=>proposeProject({project:{edits:[{path:'pkg/main.py',oldText:'',newText:'replacement'}]}},null,c));
});
