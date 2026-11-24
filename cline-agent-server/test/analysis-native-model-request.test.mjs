import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {once} from 'node:events';
import {createServer} from 'node:http';
import {mkdtemp,mkdir,readFile,rm,writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {tmpdir} from 'node:os';
import {dirname,join,resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import test from 'node:test';
import {createAnalysisToolHooks} from '../scripts/analysis-tool-results.mjs';

const checkout=process.env.CLINE_TEST_UPSTREAM;
const manifest='input/data/0/data-supply-manifest.json';
const raw='PRIVATE_ROW_BODY_'.repeat(4500);
const toolNames=['read_files','search_codebase','editor','apply_patch','run_commands','python_execute','mcp__data__query'];

async function upstreamAgent(root) {
 const source=resolve(checkout);
 const pin=JSON.parse(await readFile(new URL('../upstream-pin.json',import.meta.url),'utf8'));
 const entry=pin.files.find(file=>file.path.endsWith('/agent-runtime.ts'));
 const runtime=join(source,entry.path);
 assert.equal(createHash('sha256').update(await readFile(runtime)).digest('hex'),entry.sha256);
 const require=createRequire(join(dirname(source),'fixture.cjs'));
 const {build}=require('esbuild');
 const shared=join(source,'sdk/packages/shared/src');
 const bundle=join(root,'native-loop.mjs');
 await build({
  stdin:{contents:`export {AgentRuntime} from ${JSON.stringify(runtime)};
    export {formatMessagesForAiSdk} from ${JSON.stringify(join(shared,'llms/ai-sdk-format.ts'))};`,resolveDir:source},
  outfile:bundle,bundle:true,platform:'node',format:'esm',
  plugins:[{name:'native-hook-test-dependencies',setup(builder){
   builder.onResolve({filter:/^@cline\/(shared|llms)$/},args=>({path:args.path,namespace:'fixture'}));
   builder.onLoad({filter:/.*/,namespace:'fixture'},args=>args.path==='@cline/shared'
    ?{resolveDir:shared,contents:[
      'export * from "./services/telemetry";',
      'export {estimateTokens} from "./llms/tokens";',
      'export {mergeModelOptions} from "./llms/model-options";',
      'export {TOOL_REJECTION_SUFFIX} from "./llms/tools";',
      'export {normalizeJsonLikeStringsForSchema} from "./parse/json";',
      'export {omitUndefinedValues} from "./parse/object";',
      'export {trimNonEmpty} from "./parse/string";',
    ].join('\n')}
    :{contents:'export const classifyProviderError = () => "unknown"; export const isRetryableProviderError = () => false; export function createGateway(){throw Error("Fixture must use its explicit model adapter");}'});
  }}],
 });
 return import(pathToFileURL(bundle).href);
}

test('pinned Cline runtime sends first-use governed results across HTTP and restores their association',
 {skip:!checkout},async t=>{
 const root=await mkdtemp(join(tmpdir(),'native-model-request-'));
 t.after(()=>rm(root,{recursive:true,force:true}));
 const {AgentRuntime,formatMessagesForAiSdk}=await upstreamAgent(root);
 const calls=new Map();
 const gateway=createServer(async(req,res)=>{
  const chunks=[];
  for await(const chunk of req) chunks.push(chunk);
  const value=JSON.parse(Buffer.concat(chunks));
  const requests=calls.get(req.url)??[];
  requests.push(value);calls.set(req.url,requests);
  res.end(JSON.stringify({ok:true}));
 });
 gateway.listen(0,'127.0.0.1');await once(gateway,'listening');
 t.after(()=>new Promise(resolveClose=>gateway.close(resolveClose)));
 const analysisRoot=join(root,'analysis');
 const codingRoot=join(root,'coding');
 await mkdir(join(analysisRoot,'input','data','0'),{recursive:true});
 await mkdir(join(codingRoot,'input','data','0'),{recursive:true});
 await writeFile(join(analysisRoot,manifest),'{"datasets":[]}');
 for(const workspace of [analysisRoot,codingRoot]){
  await writeFile(join(workspace,'input/data/0/source.jsonl'),raw);
 }
 const inputs={read_files:{files:[{path:'input/data/0/source.jsonl'}]}};
 let nativeReads=0;
 const tools=workspace=>toolNames.map(name=>({name,description:'Native fixture tool',inputSchema:{type:'object'},
  async execute() {
   if(name==='read_files'){
    nativeReads++;
    return readFile(join(workspace,'input/data/0/source.jsonl'),'utf8');
   }
   return ['editor','apply_patch'].includes(name)?'CODE_REPAIR_READY':raw;
  }}));
 function agent(id,hooks,steps,onRequest) {
  return new AgentRuntime({agentId:id,hooks,tools:tools(hooks?analysisRoot:codingRoot),model:{
   async stream(request) {
    const response=await fetch(`http://127.0.0.1:${gateway.address().port}/${id}`,{
     method:'POST',body:JSON.stringify({messages:formatMessagesForAiSdk(request.systemPrompt,request.messages)}),
     headers:{'content-type':'application/json'},
    });
    assert.equal(response.status,200);
    await response.arrayBuffer();
    await onRequest?.(request);
    const names=steps.shift()??[];
    return (async function*(){
     for(const name of names)yield {type:'tool-call-delta',toolCallId:id+'-'+name,toolName:name,input:inputs[name]??{}};
     if(!names.length)yield {type:'text-delta',text:'Done'};
     yield {type:'finish',reason:names.length?'tool-calls':'stop'};
    })();
   },
  }});
 }
 const [analysis,coding]=await Promise.all([
  agent('analysis',createAnalysisToolHooks(analysisRoot,manifest),[toolNames]).run('Analyze'),
  agent('coding',undefined,[toolNames]).run('Edit code'),
 ]);
 assert.equal(analysis.status,'completed');assert.equal(coding.status,'completed');
 assert.equal(nativeReads,1);
 const governed=JSON.stringify(calls.get('/analysis')[1]);
 const ordinary=JSON.stringify(calls.get('/coding')[1]);
 assert.doesNotMatch(governed,/PRIVATE_ROW_BODY_/);
 assert.match(governed,/REFERENCE_ONLY/);
 assert.match(governed,/CODE_REPAIR_READY/);
 assert.match(ordinary,/PRIVATE_ROW_BODY_/);
 assert.doesNotMatch(ordinary,/REFERENCE_ONLY/);
 assert.equal(await readFile(join(analysisRoot,'input/data/0/source.jsonl'),'utf8'),raw);
 for(const tool of toolNames)assert.match(governed,new RegExp('analysis-'+tool));
 const ref=analysis.messages.flatMap(msg=>msg.content)
  .find(part=>part.output?.tool==='run_commands')?.output.evidence;
 assert.ok(ref?.path);
 assert.equal(JSON.parse(await readFile(join(analysisRoot,ref.path),'utf8')),raw);
 const restored=agent('restored',createAnalysisToolHooks(analysisRoot,manifest),[]);
 restored.restore(analysis.messages);
 assert.equal((await restored.run('Continue analysis')).status,'completed');
 const continued=JSON.stringify(calls.get('/restored')[0]);
 assert.doesNotMatch(continued,/PRIVATE_ROW_BODY_/);
 assert.ok(continued.includes(ref.sha256));
 const waiting=Promise.withResolvers();
 const interrupted=agent('cancelled',createAnalysisToolHooks(analysisRoot,manifest),[['run_commands']],request=>{
  if(calls.get('/cancelled').length!==2)return;
  waiting.resolve();
  return new Promise((_,reject)=>{
   if(request.signal.aborted){reject(request.signal.reason);return;}
   request.signal.addEventListener('abort',()=>reject(request.signal.reason),{once:true});
  });
 });
 const pending=interrupted.run('Run and cancel');
 await waiting.promise;
 interrupted.abort('Fixture cancellation');
 const cancelled=await pending;
 assert.equal(cancelled.status,'aborted');
 assert.equal(calls.get('/cancelled').length,2);
 assert.equal(JSON.parse(await readFile(join(analysisRoot,ref.path),'utf8')),raw);
 console.log(JSON.stringify({governedHttpBytes:Buffer.byteLength(governed),ordinaryHttpBytes:Buffer.byteLength(ordinary),
  restoredContainsEvidence:true,cancelledEvidencePreserved:true,ordinaryToolResultsPreserved:true,rawDataInGovernedRequest:false}));
});
