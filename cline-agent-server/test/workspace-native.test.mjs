import test from 'node:test';
import assert from 'node:assert/strict';
import {workspaceTools,workspaceReceipt,workspaceContract} from '../src/business/workspace.mjs';
import http from 'node:http';
import {createHash,createHmac} from 'node:crypto';
import {httpGateway} from '../src/business/runtime-callback.mjs';
const valid={valid:true,resultAvailable:true,codeArtifactRef:'code',executionReceiptRef:'receipt',resultDatasetRef:'files',validationRecordRef:'parse',evidenceBundleRef:'bundle'};
function fixture(allowed=['workspace_read','workspace_propose','workspace_metadata','workspace_validate']){
 const run={contract:{schemaVersion:'runtime_os.coding.v2',scope:{tenantId:'t',userId:'u',runId:'r'},taskId:'task',outputContract:{resultFormat:'WORKSPACE_FILES',resultSchemaRef:'python-studio.output.v1'},authorization:{allowedTools:allowed},budget:{deadlineAt:new Date(Date.now()+60000).toISOString()}},abort:new AbortController(),nativeAgentSessionId:'native',epoch:2};
 return {run,context:{sessionId:'native',epoch:2,toolCallId:'call'}};
}
test('only complete parser/archive receipts can finish a Studio task',()=>{
 assert.equal(workspaceReceipt(valid),true);
 for(const key of Object.keys(valid))assert.equal(workspaceReceipt({...valid,[key]:undefined}),false,key);
 assert.equal(workspaceContract({schemaVersion:'runtime_os.coding.v3',outputContract:{resultFormat:'JSON_LINES'}}),false);
});
test('proposal repair replaces the accepted receipt; retrying the same native call is idempotent',async()=>{
 const {run,context}=fixture();let calls=0;const events=[];
 const tools=workspaceTools(run,{proposeWorkspace:async(c,id,args)=>{calls++;assert.equal(args.schemaVersion,'python-studio.output.v1');return calls===1?valid:{valid:false,resultAvailable:false};}},e=>events.push(e));
 const args={files:[{path:'summary.py',content:'print(1)'}],notes:'source only'};
 await tools.call('workspace_propose',args,context);await tools.call('workspace_propose',args,context);
 assert.equal(calls,1);assert.equal(run.reviewedResult,'files');
 await tools.call('workspace_propose',args,{...context,toolCallId:'repair'});
 assert.equal(run.lastOperation,null);assert.equal(run.reviewedResult,null);assert.equal(events.at(-1).status,'FAILED');
});
test('scope, tool authorization and cancellation fail closed',async()=>{
 const {run,context}=fixture(['workspace_read']);let calls=0;
 const tools=workspaceTools(run,{readWorkspace:async()=>{calls++;return {files:[]};}},()=>{});
 assert.deepEqual(tools.list().map(t=>t.name),['workspace_read']);
 await assert.rejects(tools.call('workspace_propose',{},context),/authorization/);
 await assert.rejects(tools.call('workspace_read',{}, {...context,sessionId:'other'}),/context/);
 run.abort.abort();await assert.rejects(tools.call('workspace_read',{},context),/stopped/);
 assert.equal(calls,0);
});
test('parallel native metadata calls preserve sequential Java transactions',async()=>{
 const {run,context}=fixture();let active=0,peak=0;const calls=[];
 const tools=workspaceTools(run,{queryWorkspaceMetadata:async(c,id,args)=>{
  active++;peak=Math.max(peak,active);await new Promise(resolve=>setTimeout(resolve,5));calls.push(args.operation);active--;return {status:'SUCCEEDED'};
 }},()=>{});
 await Promise.all(['LIST_BINDINGS','SEARCH_METADATA','GET_TABLE_SCHEMA'].map((operation,i)=>tools.call('workspace_metadata',{operation},{...context,toolCallId:'parallel-'+i})));
 assert.equal(peak,1);assert.deepEqual(calls,['LIST_BINDINGS','SEARCH_METADATA','GET_TABLE_SCHEMA']);
});
test('callback base with trailing slash preserves the exact signed route',async()=>{
 const secret='test-only-key-not-a-production-secret';let observed;
 const server=http.createServer(async(req,res)=>{
  let body='';for await(const chunk of req)body+=chunk;
  const expected=createHmac('sha256',secret).update(['v2','POST',req.url,req.headers['x-chatchat-timestamp'],req.headers['x-chatchat-nonce'],createHash('sha256').update(body).digest('hex')].join('\n')).digest('hex');
  observed={path:req.url,signature:req.headers['x-chatchat-signature']===expected};
  res.setHeader('content-type','application/json');res.end('{"ok":true}');
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 try{await httpGateway(`http://127.0.0.1:${server.address().port}/`,secret,{}).preflight();assert.deepEqual(observed,{path:'/internal/coding/cline/preflight',signature:true});}
 finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});

test('later metadata pages require the actual first-page snapshot before dispatch',async()=>{
 const {run,context}=fixture();let calls=0;
 const tools=workspaceTools(run,{queryWorkspaceMetadata:async(c,id,args)=>{calls++;assert.equal(args.expectedSnapshotId,'snapshot-1');return {status:'SUCCEEDED'};}},()=>{});
 const missing=await tools.call('workspace_metadata',{operation:'LIST_TABLES',page:2},context);
 assert.equal(missing.errorCode,'METADATA_SNAPSHOT_REQUIRED');assert.equal(calls,0);
 await tools.call('workspace_metadata',{operation:'LIST_TABLES',page:2,expectedSnapshotId:'snapshot-1'},{...context,toolCallId:'corrected'});
 assert.equal(calls,1);
});
