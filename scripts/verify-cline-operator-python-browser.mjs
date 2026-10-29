// Real /monitor page + Java journal + immutable code archives; no network mocks.
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
const base=process.env.CLINE_MONITOR_BASE_URL,output=process.env.CLINE_MONITOR_OUTPUT;
if(!base||!output)throw new Error('Set CLINE_MONITOR_BASE_URL and CLINE_MONITOR_OUTPUT');
const {token}=JSON.parse(await readFile(process.env.CLINE_MONITOR_AUTH_FILE,'utf8'));
const cases=JSON.parse(await readFile(process.env.CLINE_MONITOR_CASES_FILE,'utf8'));await mkdir(output,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHATCHAT_BROWSER_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
const proof={scope:'Actual standalone Cline /monitor; durable Java Python receipts and code archives',cases:[],errors:[]};
async function until(check,timeout=60000){const end=Date.now()+timeout;while(Date.now()<end){if(await check())return;await new Promise(r=>setTimeout(r,100));}throw new Error('Real page condition timed out');}
try{
 const context=await browser.newContext({viewport:{width:1680,height:1200},locale:'zh-CN',timezoneId:'Asia/Shanghai'});const page=await context.newPage();page.on('pageerror',e=>proof.errors.push(e.message));
 const get=async path=>{const response=await context.request.get(base+path);assert.equal(response.status(),200);return response.json();};
 await page.goto(base+'/monitor/');await page.locator('#token').fill(token);await page.locator('#login-form button[type=submit]').click();await page.waitForSelector('#console:not([hidden])');
 for(const test of cases){
  const list=await get('/v1/monitor/tasks?q='+encodeURIComponent(test.taskId));const observed=list.items.find(x=>x.taskId===test.taskId);assert.ok(observed);
  const path='/v1/monitor/tasks/'+observed.id,initial=await get(path),before=await get(path+'/python');assert.equal(before.source,'DURABLE_CODING_JOURNAL');assert.equal(before.executions.length,test.expectedExecutions);
  await page.locator('#search').fill(test.taskId);await page.locator(`.task-row[data-task-id="${observed.id}"]`).click();await page.locator('[data-tab=python]').click();
  await until(async()=>await page.locator('.python-diagnostics .python-execution').count()===test.expectedExecutions);
  const panel=page.locator('.python-diagnostics');assert.match(await panel.innerText(),/原始日志不可用/);
  const counts={};for(const label of ['已归档执行','自动修复'])counts[label]=await panel.locator('.python-stats .mini-stat').filter({hasText:label}).locator('strong').innerText();
  assert.equal(Number(counts['已归档执行']),test.expectedExecutions);assert.equal(Number(counts['自动修复']),test.expectedRepairs);
  for(let i=0;i<before.executions.length;i++){
   const execution=panel.locator('.python-execution').nth(i);assert.equal(await execution.getAttribute('data-execution-id'),before.executions[i].executionId);
   await execution.locator(':scope>summary').click();
  }
  for(const type of test.expectedErrors)assert.match(await panel.innerText(),new RegExp(type));
  assert.match(await panel.innerText(),/project\/main.py:5/);
  await until(async()=>(await panel.innerText()).includes('原 MCP 回执'),30000);
  await panel.locator('.python-execution').first().scrollIntoViewIfNeeded();await page.screenshot({path:output+'/operator-python-'+test.case+'-error.png'});
  const buttons=panel.getByRole('button',{name:'查看真实修复 Diff'});assert.equal(await buttons.count(),test.expectedRepairs);
  for(let i=0;i<test.expectedRepairs;i++){
   await buttons.nth(i).click();await until(async()=>await page.locator('.python-diff pre').count()>=i+1,30000);
  }
  const diff=await panel.locator('.python-diff').last().innerText();assert.match(diff,/amount/);assert.match(diff,/\+ /);
  await panel.locator('.python-diff').last().scrollIntoViewIfNeeded();await page.screenshot({path:output+'/operator-python-'+test.case+'-diff.png'});
  const after=await get(path+'/python'),final=await get(path);assert.deepEqual(after.executions,before.executions);assert.equal(after.revision,before.revision);assert.equal(final.task.modelCalls,initial.task.modelCalls);
  await page.reload();await page.locator('[data-tab=python]').click();await until(async()=>await page.locator('.python-execution').count()===test.expectedExecutions);
  assert.equal(await panel.locator('.python-execution button').count(),test.expectedRepairs);
  proof.cases.push({taskId:test.taskId,observationId:observed.id,executions:test.expectedExecutions,repairs:test.expectedRepairs,exceptions:test.expectedErrors,authority:before.source,realArchivedTiming:true,readOnlyModelCountUnchanged:true,journalRevisionUnchanged:true,restoredAfterReload:true});
 }
 assert.deepEqual(proof.errors,[]);await writeFile(output+'/browser-proof.json',JSON.stringify(proof,null,2));console.log(JSON.stringify({passed:proof.cases.length,errors:proof.errors.length}));
}catch(error){const page=browser.contexts()[0]?.pages()[0];if(page)await page.screenshot({path:output+'/browser-failure.png'});throw error;}
finally{await browser.close();}
