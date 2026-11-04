/** Export an allowlisted audit summary; never copy raw source rows or authentication. */
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
const root=process.env.REPORT_EVIDENCE_ROOT||'target/cline-report-live';
const out=process.env.REPORT_EVIDENCE_OUTPUT||'docs/testing/cline-report-skills-evidence';
fs.mkdirSync(out,{recursive:true});
const read=p=>fs.existsSync(p)?JSON.parse(fs.readFileSync(p,'utf8')):null;
const hash=p=>createHash('sha256').update(fs.readFileSync(p)).digest('hex');
for(const name of process.argv.slice(2)){
 const dir=path.join(root,name), task=read(path.join(dir,'task.json'));
 if(!task)continue;
 const coding=read(path.join(dir,'coding.json'))||read(path.join(dir,'coding-progress.json'));
 const response=read(path.join(dir,'response.json'));
 const runFile=read(path.join(dir,'run.private.json'));
 const run=runFile?.data||runFile;
 const runMetadata=run?.metadata||{};
 const executionMetadata=Object.fromEntries(['agentPhaseDurationsMs','primaryCodingDurationMs','javaAnalysisRefinementSkipped','javaPostCodingExplorationSkipped','javaContentAnalysisModelCallCount','toolResultDataMarkdownSuppressed'].filter(k=>runMetadata[k]!==undefined).map(k=>[k,runMetadata[k]]));
 const events=read(path.join(dir,'events.json'))||[];
 const receipts=(coding?.tasks||[]).flatMap(t=>t.modelReceipts||[]);
 const skills=[],tools=[],workerTools=[],modelFailures=[];
 let responseContract=null;
 for(const e of events){
  let p=e.payload;try{if(typeof p==='string')p=JSON.parse(p);}catch{continue;}
  const m=p?.payload?.metadata, s=m?.payload;
  if(s?.type==='SKILLS_LOADED'||s?.type==='SKILLS_AVAILABLE')skills.push({type:s.type,at:s.timestamp,skillId:s.skillId,version:s.skillVersion,fingerprint:s.skillFingerprint});
  if(s?.type==='TOOL_STARTED'||s?.type==='TOOL_FINISHED')workerTools.push({type:s.type,at:s.timestamp,tool:s.tool,status:s.status});
  if(s?.type==='MODEL_FAILED'||m?.summary==='Worker observation: MODEL_FAILED')modelFailures.push({at:e.createTime,modelCall:s?.modelCall,status:'FAILED'});
  if(e.type==='QUESTION'){const text=JSON.stringify(p);responseContract={synthesisRequested:text.includes('synthesize findings or computed summaries'),preserveUnexpectedRows:text.includes('Preserve and display any rows/fields')};}
  if(e.type==='TOOL_CALL'||e.type==='TOOL_RESULT')tools.push({sequence:e.sequence,type:e.type,tool:e.toolName,at:e.createTime,latencyMs:e.latencyMs,outcome:p?.runtime?.outcome,remoteToolInvoked:p?.runtime?.remoteToolInvoked,errorCode:e.errorCode});
 }
 const proof=read(path.join(dir,'browser-proof.json'));
 const reportPath=path.join(dir,'report.md'),renderedPath=path.join(dir,'rendered-report.txt');
 const headings=fs.existsSync(reportPath)?[...fs.readFileSync(reportPath,'utf8').matchAll(/^#{1,6}\s+(.+)$/gm)].map(m=>m[1].replace(/[*`]/g,'').trim()):[];
 const renderedText=fs.existsSync(renderedPath)?fs.readFileSync(renderedPath,'utf8'):null;
 const textCoverage=renderedText===null?null:{headingCount:headings.length,renderedHeadingCount:headings.filter(h=>renderedText.includes(h)).length,allHeadingsRendered:headings.length>0&&headings.every(h=>renderedText.includes(h))};
 const browser=proof?{taskId:proof.taskId,conversationId:proof.conversationId,status:proof.status,historyReopened:proof.historyReopened,reportCount:proof.reportCount,chartWaitError:proof.chartWaitError,images:proof.images?.map(i=>({loaded:i.loaded,width:i.width,height:i.height})),historyImages:proof.historyImages,pageErrors:proof.pageErrors,httpStatusCounts:Object.fromEntries([...new Set((proof.responses||[]).map(r=>r.status))].map(status=>[status,proof.responses.filter(r=>r.status===status).length]))}:null;
 const evidence={case:name,taskId:task.taskId,attemptId:task.attemptId,state:task.canonicalState,agentId:task.agentId||response?.metadata?.skillId||run?.request?.skillId,boundModel:run?.request?.modelName,createdAt:task.createTime,finishedAt:task.finishedAt,wallMs:task.finishedAt&&task.createTime?task.finishedAt-task.createTime:null,realExecution:true,syntheticSources:false,
  receipts:receipts.map(r=>({operationId:r.operationId,model:r.selectedModel,state:r.state,startedAt:r.startedAt,durationMs:r.durationMs,usage:r.usage})),
  totals:{modelCalls:receipts.length,modelMs:receipts.reduce((s,r)=>s+(r.durationMs||0),0),inputTokens:receipts.reduce((s,r)=>s+(r.usage?.inputTokens||0),0),outputTokens:receipts.reduce((s,r)=>s+(r.usage?.outputTokens||0),0)},executionMetadata,
  python:(coding?.tasks||[]).flatMap(t=>(t.executions||[]).map(e=>({executionId:e.executionId,status:e.status,stage:e.stage,exitCode:e.exitCode,durationMs:e.durationMs,codeHash:e.codeHash,manifestHash:e.manifestHash,validationStatus:e.validationStatus,artifacts:e.artifactRefs}))),codingOutcomes:(coding?.tasks||[]).map(t=>({taskId:t.taskId,status:t.outcome?.status,reasonCode:t.outcome?.reasonCode,meteringComplete:t.outcome?.usage?.meteringComplete,reservedModelCalls:t.outcome?.usage?.reserved?.modelCalls})),skills,tools,workerTools,modelFailures,responseContract,browser,textCoverage,
  files:Object.fromEntries(['report.md','runtime-report-full.png','runtime-history-reopened.png','rendered-report.txt'].filter(n=>fs.existsSync(path.join(dir,n))).map(n=>[n,{bytes:fs.statSync(path.join(dir,n)).size,sha256:hash(path.join(dir,n))}]))};
 fs.writeFileSync(path.join(out,name+'.json'),JSON.stringify(evidence,null,2)+'\n');
 console.log(JSON.stringify({case:name,state:evidence.state,...evidence.totals,python:evidence.python.length,loaded:skills.filter(s=>s.type==='SKILLS_LOADED').map(s=>s.skillId)}));
}
