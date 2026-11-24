import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, writeFile, rm, symlink} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, dirname} from 'node:path';
import {createAnalysisToolHooks} from '../scripts/analysis-tool-results.mjs';

const manifest='input/data/1/data-supply-manifest.json';
const call=(toolName,input,result)=>({toolCall:{toolName,toolCallId:'native-call-1'},input,result});

async function fixture(t) {
 const root=await mkdtemp(join(tmpdir(),'cline-analysis-'));
 t.after(()=>rm(root,{recursive:true,force:true}));
 for(const [path,body] of [[manifest,'{"datasets":[]}'],['input/data/0/rows.jsonl','{"raw":"secret"}\n'],
   ['downloads/data.csv','private'],['output/analysis_summary.json','{"rows":1}'],
   ['output/result_detail.json','private'],['analyze.py','print("hello")']]){
  const name=join(root,path);
  await mkdir(dirname(name),{recursive:true});
  await writeFile(name,body);
 }
 return {root,hooks:createAnalysisToolHooks(root,manifest)};
}

test('analysis read policy keeps raw inputs and details off the model path',async t=>{
 const {root,hooks}=await fixture(t);
 for(const input of ['input/data/0/rows.jsonl',{files:[{path:join(root,'input/data/0/rows.jsonl'),start_line:1}]},
   {filePath:'downloads/data.csv'},{file_paths:'output/result_detail.json'},
   {paths:[manifest,'input/data/0/rows.jsonl']},'../outside']){
  assert.equal((await hooks.beforeTool(call('read_files',input))).skip,true);
 }
 for(const path of [manifest,'analyze.py','output/analysis_summary.json']){
  assert.equal(await hooks.beforeTool(call('read_files',{files:[{path}]})),undefined);
 }
 await writeFile(join(root,'output/analysis_summary.json'),'X'.repeat(4097));
 assert.equal((await hooks.beforeTool(call('read_files','output/analysis_summary.json'))).skip,true);
 assert.equal(await hooks.beforeTool(call('editor',{})),undefined);
 if(process.platform!=='win32'){
  await symlink(join(root,'input/data/0/rows.jsonl'),join(root,'alias.py'));
  assert.equal((await hooks.beforeTool(call('read_files','alias.py'))).skip,true);
 }
});

test('native tool hook preserves correlation and compact diagnostics and archives original results',async t=>{
 const {root,hooks}=await fixture(t);
 const original=[{success:false,error:'Command exited with code 1',
  result:'  File "/workspace/analyze.py", line 7\nValueError: RAW PRIVATE ROW\n'+'x'.repeat(80_000)}];
 const context=call('run_commands',{}, {output:original,isError:true});
 const {result}=await hooks.afterTool(context);
 assert.equal(result.isError,true);
 assert.equal(context.toolCall.toolCallId,'native-call-1');
 assert.deepEqual(result.output.diagnostics,[{success:false,exitCode:1,errorType:'ValueError',file:'analyze.py',line:7}]);
 assert.doesNotMatch(JSON.stringify(result),/RAW PRIVATE ROW/);
 assert.ok(JSON.stringify(result).length<1_500);
 const ref=result.output.evidence;
 assert.equal(ref.bytes,Buffer.byteLength(JSON.stringify(original)));
 assert.deepEqual(JSON.parse(await readFile(join(root,ref.path),'utf8')),original);
 assert.equal((await hooks.afterTool(context)).result.output.evidence.path,ref.path);
 assert.equal((await hooks.beforeTool(call('read_files',ref.path))).skip,true);
 for(const name of ['runtime__document_search','tenant_data__search','search_codebase']){
  const response=await hooks.afterTool(call(name,{}, {output:'sensitive'.repeat(600)}));
  assert.equal(response.result.output.evidence.status,'AVAILABLE');
  assert.doesNotMatch(JSON.stringify(response),/sensitive/);
 }
 assert.equal(await hooks.afterTool(call('runtime__web_search',{}, {output:'small receipt'})),undefined);
 assert.equal(await hooks.afterTool(call('skills',{}, {output:'on demand guidance'})),undefined);
 assert.equal(await hooks.afterTool(call('read_files','analyze.py', {output:'source code'})),undefined);
});

test('hook failures and results from other large tools never forward the raw payload',async t=>{
 const {root,hooks}=await fixture(t);
 const raw='RAW_DATA_SENTINEL'.repeat(400);
 const broken=createAnalysisToolHooks(root,manifest,{
  beforeTool(){throw new Error('private before detail');},
  afterTool(){throw new Error('private after detail');},
 });
 assert.match((await broken.beforeTool(call('read_file',manifest))).reason,/ANALYSIS_TOOL_POLICY_CHECK_FAILED/);
 const result=(await broken.afterTool(call('python_execute',{}, {output:raw}))).result;
 assert.equal(result.output.code,'ANALYSIS_TOOL_POLICY_FAILED');
 assert.equal(result.output.omitted,true);
 assert.doesNotMatch(JSON.stringify(result),/RAW_DATA_SENTINEL|private after detail/);
 assert.equal(JSON.parse(await readFile(join(root,result.output.evidence.path),'utf8')),raw);
 const recovered=createAnalysisToolHooks(root,manifest);
 const stored=(await recovered.afterTool(call('some_other_tool',{}, {output:raw}))).result.output.evidence;
 assert.deepEqual(stored,result.output.evidence);
 await rm(join(root,'.runtime-tool-results'),{recursive:true,force:true});
 await writeFile(join(root,'.runtime-tool-results'),'not a directory');
 const failed=(await hooks.afterTool(call('run_commands',{}, {output:raw}))).result;
 assert.equal(failed.output.code,'ANALYSIS_TOOL_EVIDENCE_WRITE_FAILED');
 assert.doesNotMatch(JSON.stringify(failed),/RAW_DATA_SENTINEL/);
});
