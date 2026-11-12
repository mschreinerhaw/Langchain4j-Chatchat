/** Real Java monitor + Native Cline cancellation acceptance. No mocked routes. */
import assert from 'node:assert/strict';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
const origin=process.env.RUNTIME_TEST_ORIGIN||'http://192.168.195.221:18100',out=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/monitor-batch-stop-live');await mkdir(out,{recursive:true});
let auth,browser;const tasks=[],result={passed:false,scope:'REAL_NATIVE_MONITOR_BATCH_STOP',startedAt:new Date().toISOString()};
async function api(path,body){const r=await fetch(origin+'/api/v1'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(auth?{Authorization:'Bearer '+auth.token}:{})},body:body===undefined?undefined:JSON.stringify(body)});const value=await r.json();assert.ok(r.status===200||r.status===202,path+': '+r.status+' '+value.message);assert.equal(value.code,200,value.message);return value.data;}
try{
 assert.ok(process.env.RUNTIME_TEST_USER&&process.env.RUNTIME_TEST_PASSWORD,'Credentials must be supplied via environment');auth=await api('/enterprise/auth/login',{username:process.env.RUNTIME_TEST_USER,password:process.env.RUNTIME_TEST_PASSWORD});
 if(process.env.RUNTIME_TEST_EXISTING_TASKS)tasks.push(...JSON.parse(await readFile(process.env.RUNTIME_TEST_EXISTING_TASKS,'utf8')));
 const title=tasks.length?(await api(`/coding-monitor/tasks/${tasks[0].runId}/${tasks[0].taskId}`)).name.replace(/ \d+$/,''):'Monitor batch stop '+randomUUID().slice(0,8);result.title=title;
 if(!tasks.length)for(let i=1;i<=2;i++)tasks.push(await api('/coding/tasks',{requestId:'monitor-stop-'+randomUUID(),fileIds:[],artifactTypes:['SOURCE','REPORT','RESULT'],instruction:title+' '+i+'\nThis is an explicit cancellation acceptance task. Autonomously create a meaningful Python source file. In one foreground process print MONITOR_STOP_READY with flush enabled, then sleep for 300 seconds. Only after the sleep create output/late-result.json and a Markdown report. Execute the script using native Shell. Do not use background execution, skip the sleep, or write the final outputs before it finishes. There is no business data and no need to search.'}));
 result.tasks=tasks.map(({taskId,runId})=>({taskId,runId}));await writeFile(resolve(out,'tasks.json'),JSON.stringify(result.tasks,null,2));
 // Respect the existing one-Worker tenant capacity: cancel a running task and a queued task.
 for(const task of tasks.slice(0,1)){
  let started=false;
  for(const deadline=Date.now()+600000;Date.now()<deadline;){const events=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}/events?limit=256`);if(events.events.some(e=>e.type==='COMMAND_STARTED'&&JSON.stringify(e).toLowerCase().includes('python'))){started=true;break;}await new Promise(r=>setTimeout(r,2000));}
  assert.ok(started,'Need a real Native Python command before cancelling');
 }
 // Pause here if the orchestrator requests independent process inspection.
 await writeFile(resolve(out,'ready.json'),JSON.stringify({tasks:result.tasks,pythonCommandObservedFor:tasks[0].taskId}));
 if(process.env.RUNTIME_TEST_READY_GATE){const {access}=await import('node:fs/promises');for(;;){try{await access(process.env.RUNTIME_TEST_READY_GATE);break;}catch{}await new Promise(r=>setTimeout(r,500));}}
 const denied=await fetch(origin+`/api/v1/coding-monitor/tasks/${tasks[0].runId}/${tasks[0].taskId}/cancel`,{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});assert.equal(denied.status,401);result.anonymousStopRejected=true;
 browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});const page=await browser.newPage({viewport:{width:1600,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.addInitScript(token=>sessionStorage.setItem('runtime-coding-monitor-session',token),auth.token);await page.goto(origin+'/monitor/');await page.locator('#filters input[name=name]').fill(title);await page.locator('#filters button').click();await page.waitForFunction(()=>document.querySelectorAll('#rows tr').length===2);
 await page.locator('#select-all').check();page.once('dialog',dialog=>dialog.accept());await page.locator('#stop-selected').click();await page.waitForFunction(()=>document.querySelector('#stop-result').textContent.includes('已处理 2/2'),{},{timeout:180000});result.browserBatchResult=await page.locator('#stop-result').textContent();await page.screenshot({path:resolve(out,'batch-stop.png'),fullPage:true});
 const outcomes=[];
 for(const task of tasks){let detail;for(const deadline=Date.now()+180000;Date.now()<deadline;){detail=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}`);if(detail.outcome.status==='CANCELLED')break;await new Promise(r=>setTimeout(r,2000));}assert.equal(detail.outcome.status,'CANCELLED');assert.equal(detail.outcome.executionControl.executorStopped,true);assert.equal(detail.outcome.resultDatasetRef,null);outcomes.push(detail);}
 await writeFile(resolve(out,'outcomes.json'),JSON.stringify(outcomes,null,2));await page.reload();await page.locator('#filters input[name=name]').fill(title);await page.locator('#filters button').click();await page.waitForFunction(()=>document.querySelectorAll('#rows tr').length===2&&[...document.querySelectorAll('#rows tr')].every(row=>row.textContent.includes('已取消')));assert.equal(await page.locator('.stop-task').count(),0);assert.deepEqual(errors,[]);result.refreshRetainsCancellation=true;result.passed=true;
}catch(error){result.error=error.message;throw error;}finally{await browser?.close();result.finishedAt=new Date().toISOString();await writeFile(resolve(out,'verification.json'),JSON.stringify(result,null,2));if(!result.passed&&auth)for(const task of tasks)try{await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}/cancel`,{});}catch{}}
