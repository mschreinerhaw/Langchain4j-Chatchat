import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,readFile,writeFile,rm,symlink,link} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {randomUUID} from 'node:crypto';
import {createEditorExecutor} from '@cline/sdk';
import {createNativeCodeWorkspace} from '../src/native-workspace.js';
import {validateRepairEdit} from '../src/code-repair.js';
const context={toolCallId:'direct-native-test'};
const scope={tenantId:'tenant',userId:'owner',runId:'run'};
async function toolFailure(promise,pattern) {const value=await promise;assert.equal(value.success,false);if(pattern)assert.match(value.error,pattern);return value;}
async function fixture(t,options={}) {
  const base=await mkdtemp(join(tmpdir(),'native-workspace-test-'));const commits=[],executions=[];
  const workspace=await createNativeCodeWorkspace({base,taskId:'tenant-owner-task',scope,files:{'main.py':'value = 1\n'},executionMode:'python',
    authorize:async()=>{},commit:async value=>{commits.push(structuredClone(value));return {manifestHash:value.manifest.sha256};},
    execute:async value=>{executions.push(value);return {manifestHash:value.manifest.sha256,exitCode:0};},...options});
  t.after(async()=>{await workspace.dispose();await rm(base,{recursive:true,force:true});});
  return {...workspace,base,commits,executions,call:(name,input)=>workspace.tools.find(t=>t.name===name).execute(input,{...context,toolCallId:randomUUID()})};
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
test('resource admission accepts native CRLF patch bodies while SDK preserves source line endings',async t=>{
  const f=await fixture(t,{strategy:'patch',files:{'main.py':'value = 1\r\n'}});
  const patch='*** Begin Patch\r\n*** Update File: main.py\r\n@@\r\n-value = 1\r\n+value = 2\r\n*** End Patch';
  const result=await f.call('apply_patch',{input:patch});assert.equal(result.success,true);
  assert.equal(f.snapshot().files['main.py'],'value = 2\r\n');
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
  await assert.rejects(createNativeCodeWorkspace({base:f.base,taskId:'tenant-owner-task',scope,files:{'main.py':'tampered'},restoredManifest:saved.manifest,
    authorize:async()=>{},commit:async()=>{},execute:async()=>{}}),/RESTORE_CODE_HASH_MISMATCH/);
});
test('another tenant cannot restore the same task identifier and source bytes',async t=>{
  const f=await fixture(t);const saved=f.snapshot();
  await assert.rejects(createNativeCodeWorkspace({base:f.base,taskId:'tenant-owner-task',scope:{...scope,tenantId:'other'},files:saved.files,restoredManifest:saved.manifest,
    authorize:async()=>{},commit:async()=>{},execute:async()=>{}}),/RESTORE_CODE_HASH_MISMATCH/);
});
test('a mismatched bridge execution receipt is not accepted',async t=>{
  const f=await fixture(t,{execute:async()=>({manifestHash:'wrong',exitCode:0})});
  await assert.rejects(f.call('python_execute',{}),/EXECUTION_SNAPSHOT_HASH_MISMATCH/);
});
test('empty general project supports directories, text files and edits without execution',async t=>{
  const f=await fixture(t,{files:{},executionMode:'none'});
  assert.equal(f.tools.some(t=>t.name==='python_execute'||t.name==='run_commands'),false);
  assert.equal((await f.call('editor',{path:'README.md',new_text:'# Project\n'})).success,true);
  assert.equal((await f.call('editor',{path:'src/math_ops.py',new_text:'def add(a, b):\n    return a + b\n'})).success,true);
  assert.deepEqual(Object.keys(f.snapshot().files),['README.md','src/math_ops.py']);
  assert.equal(f.executions.length,0);assert.equal(f.commits.at(-1).nativeDiff.tool,'editor');
});
test('native run_commands executes a frozen version and returns true stderr and nonzero exit',async t=>{
  const calls=[];const f=await fixture(t,{executionMode:'commands',runCommand:async value=>{
    calls.push(value);return{operationId:value.operationId,manifestHash:value.manifest.sha256,executorStopped:true,
      stopProofRef:'test:stopped',exitCode:1,stdout:'test started\n',stderr:'KeyError: column\n'};
  }});
  assert.equal(f.tools.some(t=>t.name==='python_execute'),false);
  const output=await f.call('run_commands',{commands:['python -m unittest discover -v']});
  assert.equal(output[0].success,false);assert.match(output[0].result,/KeyError: column/);assert.match(output[0].result,/"exitCode":1/);
  assert.equal(calls[0].files['main.py'],'value = 1\n');assert.ok(Object.isFrozen(calls[0].files));
  assert.equal(f.commits.at(-1).kind,'EXECUTION_SNAPSHOT');
});
test('command success without independent stop and version proof is rejected',async t=>{
  const f=await fixture(t,{executionMode:'commands',runCommand:async()=>({exitCode:0,stdout:'ok',stderr:''})});
  const output=await f.call('run_commands',{commands:['python -m unittest']});
  assert.equal(output[0].success,false);assert.match(output[0].error,/EXECUTION_STOP_OR_VERSION_NOT_VERIFIED/);
});
test('a zero process exit without a verifiable command result remains a tool failure',async t=>{
  const f=await fixture(t,{executionMode:'commands',runCommand:async value=>({manifestHash:value.manifest.sha256,
    executorStopped:true,stopProofRef:'test:stop',exitCode:0,stdout:'',stderr:'missing native-command.json',resultAvailable:false})});
  const result=await f.call('run_commands',{commands:['python -m unittest']});
  assert.equal(result[0].success,false);assert.match(result[0].error,/NATIVE_COMMAND_RESULT_UNAVAILABLE/);
  assert.match(result[0].error,/missing native-command.json/);
});
test('a completed edit receipt can replay without applying the native edit twice',async t=>{
  let receipt=null;const f=await fixture(t,{executionMode:'none',lookup:async request=>{
    if(receipt){assert.equal(request.intentHash,receipt.intentHash);return receipt;}return null;
  },commit:async value=>{
    if(value.kind==='EDIT')receipt={intentHash:value.nativeDiff.intentHash,result:value.nativeDiff.result};
    return{manifestHash:value.manifest.sha256};
  }});
  const edit=f.tools.find(t=>t.name==='editor');const input={path:'/workspace/code/main.py',old_text:'1',new_text:'2'};
  assert.equal((await edit.execute(input,context)).success,true);const saved=f.snapshot();
  assert.equal((await edit.execute(input,context)).success,true);assert.deepEqual(f.snapshot(),saved);
});

test('lost durable edit acknowledgment reloads the committed source before another edit',async t=>{
  let authoritative=null,loseAck=true;
  const f=await fixture(t,{commit:async value=>{
    authoritative={manifest:value.manifest,files:value.files};
    if(value.kind==='EDIT'&&loseAck){loseAck=false;throw Error('COMMIT_ACK_LOST');}
    return{manifestHash:value.manifest.sha256};
  }});
  await toolFailure(f.call('editor',{path:'main.py',old_text:'1',new_text:'2'}),/COMMIT_ACK_LOST/);
  assert.equal(f.requiresReload(),true);assert.equal(f.snapshot().files['main.py'],'value = 1\n');
  assert.equal(authoritative.files['main.py'],'value = 2\n');
  await f.refresh(authoritative);
  assert.equal(f.requiresReload(),false);assert.equal(f.snapshot().manifest.version,1);
  assert.equal((await f.call('editor',{path:'main.py',old_text:'2',new_text:'3'})).success,true);
  assert.equal(f.snapshot().manifest.version,2);assert.equal(f.snapshot().files['main.py'],'value = 3\n');
});

test('reload rejects stale or altered manifests without replacing the code cache',async t=>{
  const f=await fixture(t);const old=f.snapshot();
  await f.call('editor',{path:'main.py',old_text:'1',new_text:'2'});
  await assert.rejects(f.refresh(old),/HOST_CODE_VERSION_REGRESSION/);
  const current=f.snapshot();
  await assert.rejects(f.refresh({...current,manifest:{...current.manifest,taskId:'other'}}),/HOST_CODE_VERSION_REGRESSION/);
  assert.deepEqual(f.snapshot(),current);
});

test('a missing disposable cache can be rebuilt from the governed immutable source',async t=>{
  const f=await fixture(t);const saved=f.snapshot();await rm(f.root,{recursive:true});
  await f.refresh(saved);assert.equal(await readFile(join(f.root,'main.py'),'utf8'),'value = 1\n');
  assert.deepEqual(f.snapshot(),saved);
});
