/** Real authenticated users, real provider/Core/Python/Silo. No mocked executor. */
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const origin=process.env.RUNTIME_TEST_ORIGIN;
assert.ok(origin,'Explicit candidate origin required');
const out=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/native-temporal-live');
await mkdir(out,{recursive:true});
const users=JSON.parse(await readFile(process.env.RUNTIME_TEST_USERS_FILE,'utf8'));
const fixture=await readFile(process.env.RUNTIME_TEST_PARQUET);
const count=Number(process.env.RUNTIME_TEST_TASKS||4);
const label=process.env.RUNTIME_TEST_LABEL||'candidate';
const result={label,passed:false,startedAt:new Date().toISOString(),tasks:[],samples:[],nodeSamples:[],
 inputSha256:createHash('sha256').update(fixture).digest('hex')};
const credentials=[];
async function api(path,body,token){
 const multipart=body instanceof FormData;
 const r=await fetch(origin+'/api/v1'+path,{method:body===undefined?'GET':'POST',
  headers:{...(multipart?{}:{'Content-Type':'application/json'}),...(token?{Authorization:'Bearer '+token}:{})},
  body:body===undefined?undefined:multipart?body:JSON.stringify(body),signal:AbortSignal.timeout(120000)});
 const text=await r.text();if(!r.ok)throw Error(path+': HTTP '+r.status+' '+text.slice(0,500));
 const e=JSON.parse(text);assert.equal(e.code,200,e.message);return e.data;
}
const delay=ms=>new Promise(r=>setTimeout(r,ms));
try{
 for(const u of users){
  const auth=await api('/enterprise/auth/login',{username:u.username,password:u.password});
  const form=new FormData();form.append('file',new Blob([fixture]),'synthetic-analysis.parquet');
  const input=await api('/interactions/user-assets',form,auth.token);
  assert.equal(input.sha256,result.inputSha256);
  credentials.push({token:auth.token,userId:auth.user.id,input});
 }
 const start=Date.now();
 for(let i=0;i<count;i++){
  const u=credentials[i%credentials.length];
  const requestId='temporal-'+randomUUID();
  const request={requestId,fileIds:[u.input.id],modelName:process.env.RUNTIME_TEST_MODEL||'qwen3.7-plus',
   artifactTypes:['REPORT','RESULT'],
   resultSchema:{type:'object',required:['count','total','mean'],properties:{count:{type:'integer'},total:{type:'number'},mean:{type:'number'}}},
   instruction:'Analyze the authorized explicitly SYNTHETIC acceptance Parquet, not customer data. Independently use native Shell and Python to read the actual Parquet and calculate count, total and mean of amount. Create meaningfully named Python source, execute it, and deliver that source, a concise Markdown report and RESULT JSON with count, total and mean at full precision. Explain the synthetic input source. Do not invent or replace input data. Choose your own implementation and filenames.'+(process.env.RUNTIME_TEST_INSTRUCTION_SUFFIX||'')};
  const task=await api('/coding/tasks',request,u.token);
  assert.ok(task.taskId.startsWith('temporal-native-'),'Task must enter Temporal scheduling');
  result.tasks.push({index:i,userId:u.userId,requestId,taskId:task.taskId,runId:task.runId,submittedAt:Date.now()});
  console.log('submitted',label,i,task.taskId);
  // Same request identity must not create another logical Workflow/task.
  const again=await api('/coding/tasks',request,u.token);assert.equal(again.taskId,task.taskId);
 }
 await writeFile(resolve(out,'submitted.json'),JSON.stringify(result,null,2));
 let pending=new Set(result.tasks.map(t=>t.index));
 for(const deadline=Date.now()+1800000;pending.size&&Date.now()<deadline;){
  result.nodeSamples.push({at:Date.now(),fleet:await api('/coding-monitor/scheduler/nodes',undefined,credentials[0].token)});
  for(const index of [...pending]){
   const task=result.tasks[index],u=credentials[index%credentials.length];
   const detail=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}`,undefined,u.token);
   const status=detail.outcome.status;
   result.samples.push({at:Date.now(),index,status,workflow:detail.workflow,nodeId:detail.nodeId,
    executions:detail.executions?.length||0,modelRequests:detail.modelReceipts?.length||0});
   await writeFile(resolve(out,`task-${index}.json`),JSON.stringify(detail,null,2));
   console.log('observed',label,index,status,detail.workflow?.workflowStatus||'',detail.nodeId||'');
   if(!['CREATED','RUNNING'].includes(status)){
    pending.delete(index);task.finishedAt=Date.now();task.status=status;
    task.detailFile=`task-${index}.json`;
    if(status==='COMPLETED'){
     const delivery=await api(`/coding/tasks/runs/${task.runId}/${task.taskId}/delivery`,undefined,u.token);
     await writeFile(resolve(out,`delivery-${index}.json`),JSON.stringify(delivery,null,2));
     const rows=delivery.results||[];
     task.numericVerified=rows.some(r=>r.count===4&&r.total===40&&r.mean===10);
     task.receiptsVerified=!!detail.executions?.length&&!!detail.modelReceipts?.length;
     task.artifacts=delivery.artifacts?.map(f=>({id:f.id,name:f.fileName,sha256:f.sha256,size:f.size}));
    }
   }
  }
  await writeFile(resolve(out,'progress.json'),JSON.stringify(result,null,2));
  if(pending.size)await delay(5000);
 }
 result.elapsedSeconds=(Date.now()-start)/1000;
 result.completed=result.tasks.filter(t=>t.status==='COMPLETED'&&t.numericVerified&&t.receiptsVerified).length;
 result.throughputPerMinute=60*result.completed/result.elapsedSeconds;
 assert.equal(pending.size,0,'All tasks must reach verified terminal state');
 assert.equal(result.completed,count,'Every failure remains in report; business values and real receipts required');
 result.passed=true;
}catch(e){result.error=e.message;console.error(e.message);process.exitCode=1;}
finally{result.finishedAt=new Date().toISOString();await writeFile(resolve(out,'verification.json'),JSON.stringify(result,null,2));}
