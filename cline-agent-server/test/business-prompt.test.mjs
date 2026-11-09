import test from 'node:test';
import assert from 'node:assert/strict';
import {promptContext} from '../src/business/prompt.mjs';
import {runtimeTools} from '../src/business/runtime-tools.mjs';
import {runtimeRepairStopped} from '../src/business/native-policy.mjs';
import {deliver} from '../src/business/delivery.mjs';
import {verifiedReceipt} from '../src/business/runtime-tools.mjs';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';

test('a compact Native prompt defers complete source metadata and schema without losing them',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'native-context-'));
 try{
  const contract={objective:{instruction:'exact user requirement'},outputContract:{requiredFiles:['rows.jsonl']},runtimeContext:{nativeAnalysisContract:{schemaVersion:'native-analysis-context.v1',sourceRepresentation:{precision:'declared'}},pythonEnvironment:{packages:{pandas:'2'}},other:'complete metadata'}};
  const context={files:[],outputContract:{rowSchema:{type:'object'}},inputVersions:[{datasetReference:'exact:v1',fieldDefinitions:{value:'declared'}}]};
  const delivery=await deliver({contract,abort:new AbortController()},{readInputContext:async()=>context},dir),projected=promptContext(contract,delivery),full=JSON.parse(readFileSync(join(dir,'runtime-inputs.json')));
  assert.equal(projected.contextFile,'runtime-inputs.json');assert(!JSON.stringify(projected).includes('complete metadata'));
  assert.equal(full.runtimeContext.other,'complete metadata');assert.deepEqual(full.context,context);assert.deepEqual(full.outputContract,contract.outputContract);
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('frozen input identity and valid empty bytes are distinct from an incomplete transfer',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'native-delivery-'));const bytes=Buffer.alloc(0),sha256=createHash('sha256').update(bytes).digest('hex');
 const file={fileId:'input',fileName:'empty.csv',size:0,sha256,virtualPath:'/data/input/input/empty.csv'};
 const run={contract:{runtimeContext:{}},abort:new AbortController()},descriptor={...file,encoding:'base64',offset:0,nextOffset:0,content:''};
 try{
  const gateway={readInputContext:async()=>({files:[file]}),nativeInputFile:async()=>descriptor};
  assert.equal((await deliver(run,gateway,dir)).files[0].size,0);
  for(const changed of [{fileId:'other'},{fileName:'other.csv'},{size:1},{encoding:'utf8'}])await assert.rejects(deliver(run,{...gateway,nativeInputFile:async()=>({...descriptor,...changed})},dir),/INPUT_DESCRIPTOR_MISMATCH/);
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('archived-result review never treats omitted validation/result fields as success',()=>{
 const receipt={status:'SUCCEEDED',executorStopped:true,resultDatasetRef:'r',validationRecordRef:'v',evidenceBundleRef:'b',resultAvailable:true,validationStatus:'INCONCLUSIVE'};
 assert.equal(Boolean(verifiedReceipt(receipt)),true);
 assert.equal(Boolean(verifiedReceipt({...receipt,resultAvailable:undefined})),false);
 assert.equal(Boolean(verifiedReceipt({...receipt,validationStatus:undefined})),false);
});
test('Native exposes only the frozen authorized security resolver and daily snapshot operations',async()=>{
 const contract={schemaVersion:'runtime_os.coding.v3',metadataRefs:['runtime-coding:resource-calculation-inputs:v1'],authorization:{allowedTools:['python_execute','market_resolve_security','market_get_day_snapshot'],allowedSideEffects:['CODE_EXECUTION']},runtimeContext:{knowledgeExploration:{schemaVersion:'runtime_os.knowledge.v1'}},budget:{deadlineAt:new Date(Date.now()+60000).toISOString()}};
 const run={contract,nativeAgentSessionId:'agent',epoch:'epoch',abort:new AbortController()},calls=[];
 const tools=runtimeTools(run,{knowledge:async(c,name,args,id)=>{calls.push({name,args,id});return {status:'OK',count:0};}},'.',async()=>{});
 assert(tools.list().some(t=>t.name==='market_resolve_security'));assert(tools.list().some(t=>t.name==='market_get_day_snapshot'));
 await tools.call('market_resolve_security',{query:'authorized query'},{sessionId:'agent',epoch:'epoch',toolCallId:'resolve-1'});assert.equal(calls[0].id,'agent:resolve-1');
 await assert.rejects(tools.call('market_get_snapshot',{}, {sessionId:'agent',epoch:'epoch',toolCallId:'not-granted'}),/outside frozen Runtime authorization/);
});
test('Native transport does not duplicate the frozen objective or lose the output contract and input identities',()=>{
 const contract={objective:{instruction:'user objective with exact decimal rules',description:'scope'},outputContract:{rowSchema:{type:'object'}},runtimeContext:{skills:{privateStaged:'delivered separately'},inputFiles:[],model:{selectedModel:'configured'},other:'preserve'},inputSnapshotRef:'snapshot:v1'};
 const delivery={files:[{nativePath:'inputs/id/original.csv',virtualPath:'/data/input/id/original.csv',sha256:'a'.repeat(64)}],context:{datasetReferences:['source:v1']}};
 const result=promptContext(contract,delivery);assert(!JSON.stringify(result).includes(contract.objective.instruction));assert.deepEqual(result.outputContract,contract.outputContract);assert.deepEqual(result.files,delivery.files);assert.deepEqual(result.inputs,delivery.context);assert.equal(result.runtimeContext.other,'preserve');
});
test('Sandbox paths receive a precise error without calling the governed logical-data reader',async()=>{
 let calls=0;const run={contract:{authorization:{allowedTools:['input_read']},runtimeContext:{},budget:{deadlineAt:new Date(Date.now()+60000).toISOString()}},nativeAgentSessionId:'agent',epoch:'epoch',abort:new AbortController()};
 const tools=runtimeTools(run,{readInput:async()=>{calls++;return {actual:true};}},'.',()=>{});
 const context={sessionId:'agent',epoch:'epoch',toolCallId:'call'};
 await assert.rejects(tools.call('input_read',{datasetReference:'/data/input/id/file.csv',offset:0,limit:100},context),/datasetReference, not a Sandbox/);
 assert.equal(calls,0);assert.deepEqual(await tools.call('input_read',{datasetReference:'authorized:dataset:v1',offset:0,limit:100},context),{actual:true});assert.equal(calls,1);
});
test('Only a real stopped Runtime STOP receipt terminates further Native model admission',()=>{
 const receipt={executorStopped:true,resultAvailable:false,executionFeedback:{repair:{action:'STOP'}}};
 assert.equal(runtimeRepairStopped(receipt),true);
 assert.equal(runtimeRepairStopped({...receipt,executorStopped:false}),false);
 assert.equal(runtimeRepairStopped({...receipt,resultAvailable:true}),false);
 assert.equal(runtimeRepairStopped({...receipt,executionFeedback:{repair:{action:'REPAIR_IN_CURRENT_CONTEXT'}}}),false);
 assert.equal(runtimeRepairStopped(undefined),false);
});
