/** Publish only numeric/identity diagnostics from actual isolated integration receipts. */
import {readFile,readdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
const [beforeDir,afterDir,output] = process.argv.slice(2);
if(!output)throw Error('Usage: summarize-context-benchmark.mjs before-directory after-directory output.json');
const files=(await readdir(beforeDir)).filter(name=>name.endsWith('.json')).sort();
assert.ok(files.length>0);
function describe(report){
 const calls=report.modelCalls||[];
 const sum=key=>calls.every(call=>Number.isSafeInteger(call.usage?.[key]))
  ? calls.reduce((value,call)=>value+call.usage[key],0):null;
 const numericKeys=['modelCall','durationMs','callbackConnectMs','callbackHeadersMs','callbackBodyMs','callbackHttpDurationMs',
  'queueTimeMs','firstTokenMs','generationTimeMs','requestBytes','estimatedInputTokens','repeatedContextBytes',
  'duplicateContextBytes','newContextBytes','contextSizeBefore','contextSizeAfter','compactionCount','toolDurationMs'];
 return {status:report.status,wallMs:report.wallMs,modelCalls:calls.length,inputTokens:sum('inputTokens'),outputTokens:sum('outputTokens'),
  tokenUsageSource:'PROVIDER_RECEIPTS',cost:null,generatedCodeExecuted:report.generatedCodeExecuted,
  timings:(report.events||[]).map(e=>e.payload).filter(p=>p&&['MODEL_RESOLVED','MODEL_TIMING','TOOL_TIMING'].includes(p.type))
   .map(p=>({type:p.type,...Object.fromEntries(numericKeys.map(k=>[k,p[k]??null]))})),
  providerCalls:calls.map(c=>({modelInfo:c.modelInfo,usage:c.usage}))};
}
const pairs=[];
for(const file of files){
 const before=JSON.parse(await readFile(path.join(beforeDir,file),'utf8'));
 const after=JSON.parse(await readFile(path.join(afterDir,file),'utf8'));
 assert.equal(before.workloadSha256,after.workloadSha256,'Workload differs');
 assert.equal(before.model,after.model,'Model differs');assert.equal(before.fastPath,after.fastPath,'Profile differs');
 assert.equal(before.contextOptimizationEnabled,false);assert.equal(after.contextOptimizationEnabled,true);
 pairs.push({case:file.replace('.json',''),model:before.model,workloadSha256:before.workloadSha256,
  environment:before.scope,before:describe(before),after:describe(after)});
}
const summary=mode=>({tasks:pairs.length,completed:pairs.filter(p=>p[mode].status==='COMPLETED').length,
 inputTokens:pairs.every(p=>p[mode].inputTokens!==null)?pairs.reduce((s,p)=>s+p[mode].inputTokens,0):null,
 outputTokens:pairs.every(p=>p[mode].outputTokens!==null)?pairs.reduce((s,p)=>s+p[mode].outputTokens,0):null,
 modelCalls:pairs.reduce((s,p)=>s+p[mode].modelCalls,0),wallMs:pairs.reduce((s,p)=>s+p[mode].wallMs,0)});
const report={schemaVersion:'coding.performance.comparison.v1',capturedAt:new Date().toISOString(),
 scope:'REAL_PROVIDER_ISOLATED_JAVA_NODE_H2_PARSER',productionModified:false,providerCredentialsExported:false,
 samplesPerCasePerPolicy:1,realPythonSandbox:false,reportBusinessCorrectnessCertified:false,
 caveats:['Small source-generation workloads; full and fast profiles matched separately.',
  'Loopback plus SSH relay used equally for both policies; not production networking.',
  'Single samples and stochastic provider outputs cannot establish causal latency improvements or P95.',
  'Source candidates are parsed and versioned, not executed in Python Sandbox.',
  'Historical 256-task baseline is heterogeneous and is not the control arm for this comparison.'],
 before:summary('before'),after:summary('after'),targets:{inputTokenReduction30Percent:'NOT_DEMONSTRATED',p95Reduction30Percent:'INSUFFICIENT_MATCHED_SAMPLES',repeatedToolReads50Percent:'NOT_DEMONSTRATED',fullAcceptance:'PENDING'},pairs};
await writeFile(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({before:report.before,after:report.after,targets:report.targets}));
