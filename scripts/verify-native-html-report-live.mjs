/** Real provider + native Python/HTML generation + Silo + authorized viewer bundle. */
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const origin=process.env.RUNTIME_TEST_ORIGIN||'http://192.168.195.221:18100',out=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/native-html-live');await mkdir(out,{recursive:true});
let auth,task;const result={passed:false,startedAt:new Date().toISOString()};
async function api(path,body){const multipart=body instanceof FormData;const r=await fetch(origin+'/api/v1'+path,{method:body===undefined?'GET':'POST',headers:{...(multipart?{}:{'Content-Type':'application/json'}),...(auth?{Authorization:'Bearer '+auth.token}:{})},body:body===undefined?undefined:multipart?body:JSON.stringify(body)});assert.ok(r.status===200||r.status===202,path+': HTTP '+r.status);const e=await r.json();assert.equal(e.code,200,e.message);return e.data;}
try{
 auth=await api('/enterprise/auth/login',{username:process.env.RUNTIME_TEST_USER,password:process.env.RUNTIME_TEST_PASSWORD});
 const csv='category,amount\nA,12\nB,25\nC,5\n';const form=new FormData();form.append('file',new Blob([csv],{type:'text/csv'}),'synthetic-html-acceptance.csv');const input=await api('/interactions/user-assets',form);
 task=await api('/coding/tasks',{requestId:'html-'+randomUUID(),fileIds:[input.id],reportMode:'visual',artifactTypes:['REPORT','RESULT'],
 resultSchema:{type:'object',required:['count','total','mean'],properties:{count:{type:'integer'},total:{type:'number'},mean:{type:'number'}}},
 instruction:'Analyze the supplied explicitly SYNTHETIC acceptance CSV, not real business data. Compute record count, total and mean of amount from the actual file and deliver those fields in RESULT JSON. Deliver an interactive HTML report showing the actual statistics and a category comparison visualization. Include a working show/hide button for the original rows, so readers can inspect the source values. Choose the implementation and source filenames yourself. Explain the source and the synthetic nature of these observations. Perform the actual computation using your native environment; never substitute invented inputs.'});
 Object.assign(result,{taskId:task.taskId,runId:task.runId,inputSha256:input.sha256});await writeFile(resolve(out,'task.json'),JSON.stringify(task,null,2));
 let detail;for(const deadline=Date.now()+900000;Date.now()<deadline;){detail=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}`);await writeFile(resolve(out,'progress.json'),JSON.stringify({status:detail.outcome.status,summary:detail.outcome.summary}));if(!['CREATED','RUNNING'].includes(detail.outcome.status))break;await new Promise(r=>setTimeout(r,2000));}
 await writeFile(resolve(out,'detail.json'),JSON.stringify(detail,null,2));assert.equal(detail.outcome.status,'COMPLETED');
 const delivery=await api(`/coding/tasks/runs/${task.runId}/${task.taskId}/delivery`);await writeFile(resolve(out,'delivery.json'),JSON.stringify(delivery,null,2));assert.ok(delivery.visualReport?.htmlReport,'Need actual HTML projection used by exploration');
 const bundle=await api(`/interactions/user-assets/${delivery.visualReport.htmlReport.entryFileId}/report-bundle`);assert.equal(bundle.report.taskId,task.taskId);assert.equal(bundle.report.runId,task.runId);
 await writeFile(resolve(out,'report-bundle.json'),JSON.stringify(bundle,null,2));
 let facts=false;const artifacts=[];
 for(const f of bundle.files){const bytes=Buffer.from(f.content,'base64');assert.equal(createHash('sha256').update(bytes).digest('hex'),f.sha256);assert.equal(bytes.length,f.size);artifacts.push({path:f.path,sha256:f.sha256,size:f.size});if(f.path.endsWith('.json')){try{const parsed=JSON.parse(bytes.toString());const rows=Array.isArray(parsed)?parsed:[parsed];if(rows.some(v=>v.count===3&&v.total===42&&v.mean===14))facts=true;}catch{}}}
 assert.ok(facts,'Independently verify real computed result values');assert.ok(detail.executions?.length,'Need real native command receipts');assert.ok(detail.modelReceipts?.length,'Need actual provider receipts');
 Object.assign(result,{passed:true,artifacts,statisticsVerified:true,htmlEntry:bundle.report.entryPath,modelReceipts:detail.modelReceipts,executions:detail.executions.length});
}catch(e){result.error=e.message;throw e;}finally{result.finishedAt=new Date().toISOString();await writeFile(resolve(out,'verification.json'),JSON.stringify(result,null,2));if(!result.passed&&task&&auth)try{await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}/cancel`,{});}catch{}}
