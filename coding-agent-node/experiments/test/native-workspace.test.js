import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,readFile,writeFile,rm,symlink,link} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {createEditorExecutor} from '@cline/sdk';
import {createNativeCodeWorkspace} from '../native-workspace.js';
import {validateRepairEdit} from '../../src/code-repair.js';
const context={};
async function toolFailure(promise,pattern) {const value=await promise;assert.equal(value.success,false);if(pattern)assert.match(value.error,pattern);return value;}
async function fixture(t,options={}) {
  const base=await mkdtemp(join(tmpdir(),'native-workspace-test-'));const commits=[],executions=[];
  const workspace=await createNativeCodeWorkspace({base,taskId:'tenant-owner-task',files:{'main.py':'value = 1\n'},
    authorize:async()=>{},commit:async value=>{commits.push(structuredClone(value));return {manifestHash:value.manifest.sha256};},
    execute:async value=>{executions.push(value);return {manifestHash:value.manifest.sha256,exitCode:0};},...options});
  t.after(async()=>{await workspace.dispose();await rm(base,{recursive:true,force:true});});
  return {...workspace,base,commits,executions,call:(name,input)=>workspace.tools.find(t=>t.name===name).execute(input,context)};
}
test('pinned native editor cwd flag is not an absolute-path security boundary',async t=>{
  const base=await mkdtemp(join(tmpdir(),'native-editor-boundary-'));t.after(()=>rm(base,{recursive:true,force:true}));
  await mkdir(join(base,'task'));const outside=join(base,'outside.py');await writeFile(outside,'value = 1\n');
  await createEditorExecutor({restrictToCwd:true})({path:outside,old_text:'1',new_text:'2'},join(base,'task'),context);
  assert.equal(await readFile(outside,'utf8'),'value = 2\n');
});
test('same native read/search/editor view and immutable execution snapshot',async t=>{
  const f=await fixture(t);assert.deepEqual(f.tools.map(t=>t.name),['read_files','search_codebase','editor','python_execute']);
  const read=await f.call('read_files',{files:[{path:'main.py'}]});assert.equal(read[0].success,true);assert.match(String(read[0].result),/value = 1/);
  const search=await f.call('search_codebase',{queries:['value']});assert.equal(search[0].success,true);
  await f.call('editor',{path:'main.py',old_text:'value = 1',new_text:'value = 2'});
  const current=f.snapshot();await f.call('python_execute',{});
  assert.equal(current.manifest.version,1);assert.equal(current.files['main.py'],'value = 2\n');
  assert.equal(f.commits.at(-1).kind,'EXECUTION_SNAPSHOT');assert.equal(f.executions[0].manifest.sha256,current.manifest.sha256);
  assert.ok(Object.isFrozen(f.executions[0].files));assert.ok(Object.isFrozen(current.manifest.files[0]));
});
test('host refusal does not publish a native edit',async t=>{
  let deny=false;const f=await fixture(t,{commit:async v=>{if(deny)throw Error('durable write refused');return {manifestHash:v.manifest.sha256};}});
  deny=true;const before=f.snapshot();await toolFailure(f.call('editor',{path:'main.py',old_text:'1',new_text:'2'}),/refused/);
  assert.deepEqual(f.snapshot(),before);assert.equal(await readFile(join(f.root,'main.py'),'utf8'),'value = 1\n');
});
test('native patch edits multiple files; invalid second hunk publishes neither',async t=>{
  const f=await fixture(t,{strategy:'patch',files:{'main.py':'from helper import value\n','helper.py':'value = 1\n'}});
  const before=f.snapshot();
  await toolFailure(f.call('apply_patch',{input:'*** Begin Patch\n*** Update File: helper.py\n@@\n-value = 1\n+value = 2\n*** Update File: main.py\n@@\n-NONEXISTENT\n+print(value)\n*** End Patch'}));
  assert.deepEqual(f.snapshot(),before);
  await f.call('apply_patch',{input:'*** Begin Patch\n*** Update File: helper.py\n@@\n-value = 1\n+value = 2\n*** Update File: main.py\n@@\n from helper import value\n+print(value)\n*** End Patch'});
  assert.equal(f.snapshot().files['helper.py'],'value = 2\n');assert.match(f.snapshot().files['main.py'],/print\(value\)/);
});
for(const path of ['../outside.py','/tmp/outside.py','C:\\outside.py','nested/../../outside.py','main.py\n*** Delete File: outside.py'])
  test('denies native editor and patch escape '+JSON.stringify(path),async t=>{
    const f=await fixture(t);const before=f.snapshot();await toolFailure(f.call('editor',{path,old_text:'1',new_text:'2'}),/CODE_PATH_DENIED/);
    assert.deepEqual(f.snapshot(),before);
    const p=await fixture(t,{strategy:'patch'});await toolFailure(p.call('apply_patch',{input:'*** Begin Patch\n*** Add File: '+path+'\n+x\n*** End Patch'}));
    assert.equal(p.snapshot().manifest.version,0);
  });
test('read boundary returns a real failed per-file result without outside content',async t=>{
  const f=await fixture(t);const result=await f.call('read_files',{files:[{path:join(f.base,'outside.py')}]});
  assert.equal(result[0].success,false);assert.match(result[0].error,/CODE_PATH_DENIED/);assert.equal(result[0].result,'');
});
for(const kind of ['symlink','hardlink'])test('denies redirected cache '+kind,async t=>{
  const f=await fixture(t);const outside=join(f.base,'outside.py');await writeFile(outside,'secret = 7\n');await rm(join(f.root,'main.py'));
  try {await (kind==='symlink'?symlink(outside,join(f.root,'main.py')):link(outside,join(f.root,'main.py')));} catch(error) {if(error.code==='EPERM' && process.platform==='win32'){t.skip('Windows symlink privilege; Linux container must cover this');return;}throw error;}
  await assert.rejects(f.call('python_execute',{}),/CODE_LINK_DENIED/);assert.equal(f.executions.length,0);
});
test('revocation and cancellation stop execution before a frozen code dispatch',async t=>{
  let allowed=true;const controller=new AbortController();const f=await fixture(t,{signal:controller.signal,authorize:async()=>{if(!allowed)throw Error('ACCESS_REVOKED');}});
  allowed=false;await assert.rejects(f.call('python_execute',{}),/ACCESS_REVOKED/);assert.equal(f.executions.length,0);
  allowed=true;controller.abort(Error('CANCELLED'));await assert.rejects(f.call('python_execute',{}),/CANCELLED/);assert.equal(f.executions.length,0);
});
test('long source supports local native edits; oversized replacements are rejected by both tool protocols',async t=>{
  const old='#'+ 'a'.repeat(17000), replacement='#'+'b'.repeat(17000);
  assert.throws(()=>validateRepairEdit({oldText:old,newText:replacement},0,old),/REPAIR_SEGMENT_LIMIT/);
  const f=await fixture(t,{files:{'main.py':old+'\nvalue = 1\n'}});
  await toolFailure(f.call('editor',{path:'main.py',old_text:old,new_text:replacement}));
  const edited=await f.call('editor',{path:'main.py',old_text:'value = 1',new_text:'value = 2'});assert.equal(edited.success,true);assert.equal(f.snapshot().files['main.py'],old+'\nvalue = 2\n');
});
test('restore rematerializes exact durable code and rejects a corrupt restored tree',async t=>{
  const f=await fixture(t);await f.call('editor',{path:'main.py',old_text:'1',new_text:'2'});const saved=f.snapshot();
  const restored=await fixture(t,{files:saved.files,restoredManifest:saved.manifest});assert.deepEqual(restored.snapshot(),saved);
  await restored.call('python_execute',{});assert.equal(restored.executions[0].files['main.py'],'value = 2\n');
  await assert.rejects(createNativeCodeWorkspace({base:f.base,taskId:'tenant-owner-task',files:{'main.py':'tampered'},restoredManifest:saved.manifest,
    authorize:async()=>{},commit:async()=>{},execute:async()=>{}}),/RESTORE_CODE_HASH_MISMATCH/);
});
test('a mismatched bridge execution receipt is not accepted',async t=>{
  const f=await fixture(t,{execute:async()=>({manifestHash:'wrong',exitCode:0})});
  await assert.rejects(f.call('python_execute',{}),/EXECUTION_SNAPSHOT_HASH_MISMATCH/);
});
