/** Read-only real Monitor API/browser checks for previously executed tasks. */
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
const {chromium}=await import(new URL('../chatchat-api/web-app/node_modules/playwright-core/index.mjs',import.meta.url));
const args=Object.fromEntries(Array.from({length:(process.argv.length-2)/2},(_,i)=>[process.argv[2+i*2].replace(/^--/,''),process.argv[3+i*2]]));
const auth=JSON.parse(await readFile(args.auth,'utf8')),cases=JSON.parse(await readFile(args.cases,'utf8'));
const output=resolve(args.output);await mkdir(output,{recursive:true});
const evidence={scope:'REAL_READ_ONLY_MONITOR_API_AND_BROWSER',cases:[],pageErrors:[],executionRequests:0};
const browser=await chromium.launch({headless:true,executablePath:args.chrome||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
try{
 const context=await browser.newContext({viewport:{width:1600,height:1000}}),page=await context.newPage();
 page.on('pageerror',e=>evidence.pageErrors.push(e.message));
 page.on('request',r=>{if(r.method()==='POST'&&/\/(execute|tasks)$/.test(new URL(r.url()).pathname))evidence.executionRequests++;});
 const unauthorized=await context.request.get(args.origin+'/v1/monitor/tasks');assert.equal(unauthorized.status(),401);
 await page.goto(args.origin+'/monitor/');await page.locator('#token').fill(auth.token);
 await page.locator('#login-form button[type=submit]').click();await page.locator('#console').waitFor({state:'visible',timeout:30000});
 async function api(path){const r=await context.request.get(args.origin+'/v1/monitor'+path);assert.equal(r.status(),200);return r.json();}
 for(const item of cases){
  let tasks=[];
  for(let i=0;i<8;i++){
   tasks=(await api('/tasks?q='+encodeURIComponent(item.taskId)+'&limit=100')).items;
   if(tasks.length&&tasks.every(t=>t.authority==='RUNTIME_CONFIRMED'&&t.runtimeOutcome?.terminal))break;
   await page.waitForTimeout(1000);
  }
  assert(tasks.length,'Executed task not retained');
  for(const task of tasks){assert.equal(task.taskId,item.taskId);assert.equal(task.runtimeOutcome?.status,item.status);assert.equal(task.authority,'RUNTIME_CONFIRMED');assert(task.runtimeOutcome.terminal);}
  const newest=tasks.reduce((a,b)=>a.fencingToken>b.fencingToken?a:b);
  const before=(await api('/tasks/'+newest.id)).task;
  await page.goto('about:blank');
  await page.goto(args.origin+'/monitor/#'+newest.id);await page.locator('#console').waitFor({state:'visible'});
  await page.locator('#detail-content').filter({hasText:item.taskId}).waitFor({timeout:30000});
  const labels={COMPLETED:'已完成',FAILED:'失败',CANCELLED:'已取消',PARTIAL:'部分完成',BUDGET_EXCEEDED:'预算超限'};
  assert.equal(await page.locator('#detail-heading .badge').innerText(),labels[item.status]);
  assert((await page.locator('#detail-content').innerText()).includes('状态已与 Runtime 权威记录核对'));
  await page.screenshot({path:resolve(output,newest.id+'.png'),fullPage:true});
  await context.setOffline(true);await page.waitForTimeout(4200);await context.setOffline(false);await page.reload();
  await page.locator('#detail-content').filter({hasText:item.taskId}).waitFor({timeout:30000});
  assert.equal(await page.locator('#detail-heading .badge').innerText(),labels[item.status]);
  const after=(await api('/tasks/'+newest.id)).task;
  assert.equal(after.runtimeOutcome.status,before.runtimeOutcome.status);assert.equal(after.runtimeOutcome.endedAt,before.runtimeOutcome.endedAt);
  assert.equal(after.eventCount,before.eventCount);assert.equal(after.codeCount,before.codeCount);
  evidence.cases.push({taskId:item.taskId,status:item.status,attempts:tasks.map(t=>({id:t.id,fencingToken:t.fencingToken,observedStatus:t.observedStatus,status:t.status,runtimeStatus:t.runtimeOutcome.status})),authority:after.authority,endedAt:after.runtimeOutcome.endedAt,eventsPreserved:true,reconnectAndReload:true});
 }
 assert.equal(evidence.executionRequests,0);assert.deepEqual(evidence.pageErrors,[]);evidence.status='PASS';
}catch(error){evidence.status='FAIL';evidence.error=error.message;throw error;}
finally{await writeFile(resolve(output,'monitor-browser-public.json'),JSON.stringify(evidence,null,2));await browser.close();}
