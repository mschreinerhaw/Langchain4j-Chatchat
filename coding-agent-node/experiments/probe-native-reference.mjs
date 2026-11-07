/** Deterministic harness check only. The command port is a stub, not Sandbox evidence. */
import assert from 'node:assert/strict';
import {createNativeCodeWorkspace} from './native-reference-workspace.mjs';

const results=[];
for(const strategy of ['editor','patch']){
  const commits=[],commands=[];
  const workspace=await createNativeCodeWorkspace({taskId:'reference-probe',scope:{tenantId:'tenant',userId:'owner',runId:'run'},
    files:{'main.py':'value = 1\n','other.py':'other = 4\n'},strategy,authorize:async()=>{},
    commit:async value=>{commits.push(value);return{manifestHash:value.manifest.sha256};},
    runCommand:async value=>{commands.push(value);return{manifestHash:value.manifest.sha256,executorStopped:true,
      stopProofRef:'PROBE_STUB_NOT_A_RUNTIME_PROOF',resultAvailable:true,exitCode:0,stdout:'PROBE_STUB_ONLY',stderr:''};}});
  try{
    const execute=(name,input,id)=>workspace.tools.find(tool=>tool.name===name).execute(input,{toolCallId:id});
    await execute(strategy==='editor'?'editor':'apply_patch',strategy==='editor'?{path:'main.py',old_text:'value = 1',new_text:'value = 2'}:
      {input:'*** Begin Patch\n*** Update File: main.py\n@@\n-value = 1\n+value = 2\n*** End Patch'},'edit');
    assert.equal(workspace.snapshot().files['main.py'],'value = 2\n');
    assert.equal(workspace.snapshot().files['other.py'],'other = 4\n');
    const read=await execute('read_files',{files:[{path:'main.py'},{path:'/workspace/code/other.py'}]},'read');
    assert.ok(read.every(value=>value.success));assert.match(JSON.stringify(read),/value = 2/);
    const command=await execute('run_commands',{commands:['THIS_MUST_NOT_RUN_ON_THE_WORKER']},'command');
    assert.ok(command.every(value=>value.success));assert.match(JSON.stringify(command),/PROBE_STUB_ONLY/);
    assert.equal(commands.length,1);assert.equal(commits.filter(value=>value.kind==='EDIT').length,1);
    assert.equal(commits.filter(value=>value.kind==='EXECUTION_SNAPSHOT').length,1);
    results.push({strategy,nativeReadAndEdit:true,commandForwardedOnlyToStub:true,manifestHash:workspace.snapshot().manifest.sha256});
  }finally{await workspace.dispose();}
}
console.log(JSON.stringify({scope:'DETERMINISTIC_REFERENCE_HARNESS_ONLY_NO_MODEL_OR_RUNTIME_EXECUTION',results}));
// All file IO and the stub above settled; SDK losing read timeout handles are not execution evidence.
process.exit(0);
