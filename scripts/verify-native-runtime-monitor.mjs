import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const origin=process.env.CODING_CANDIDATE_ORIGIN;
const output=process.env.CODING_CANDIDATE_BROWSER_OUTPUT;
assert(origin&&output&&process.env.CODING_CANDIDATE_USERNAME&&process.env.CODING_CANDIDATE_PASSWORD);
await mkdir(output,{recursive:true});
const evidence={origin,diagnosticOnly:false,pageErrors:[],checks:{},tasks:[]};
const browser=await chromium.launch({headless:true,executablePath:process.env.CODING_BROWSER_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--no-proxy-server']});
try{
 const context=await browser.newContext({viewport:{width:1600,height:1050},locale:'zh-CN'}),page=await context.newPage();
 page.on('pageerror',e=>evidence.pageErrors.push(e.message));
 let response=await context.request.get(origin+'/api/v1/coding-monitor/tasks');assert.equal(response.status(),401);evidence.checks.unauthenticatedDenied=true;
 response=await context.request.get(origin+'/api/v1/coding-monitor/tasks',{headers:{Authorization:'Bearer invalid'}});assert.equal(response.status(),401);evidence.checks.invalidTokenDenied=true;
 await page.goto(origin+'/monitor/',{waitUntil:'domcontentloaded',timeout:60000});
 await page.locator('#login-form input[name=username]').fill(process.env.CODING_CANDIDATE_USERNAME);
 await page.locator('#login-form input[name=password]').fill(process.env.CODING_CANDIDATE_PASSWORD);
 await page.locator('#login-form button').click();await page.locator('#list').waitFor({state:'visible',timeout:45000});
 await page.locator('#rows tr').first().waitFor({timeout:45000});evidence.checks.actualLoginAndTaskList=true;assert((await page.locator('.top .muted').innerText()).includes('\u5185\u90e8 \u00b7 \u53ea\u8bfb \u00b7 \u7cfb\u7edf\u7ba1\u7406\u5458'));evidence.checks.accountHeader=true;
 await page.locator('#filters input[name=name]').fill('SYNTHETIC');await page.locator('#filters select[name=status]').selectOption('COMPLETED');await Promise.all([page.waitForResponse(r=>r.url().includes('/coding-monitor/tasks?')&&r.url().includes('SYNTHETIC')),page.locator('#filters button').click()]);
 await page.screenshot({path:output+'/task-list.png',fullPage:true});
 const token=await page.evaluate(()=>sessionStorage.getItem('runtime-coding-monitor-session'));
 async function api(path){const r=await context.request.get(origin+'/api/v1/coding-monitor'+path,{headers:{Authorization:'Bearer '+token}});assert.equal(r.status(),200);const v=await r.json();return v.data??v;}
 const list=await api('/tasks?size=20&name=SYNTHETIC&status=COMPLETED');
 for(const row of list.items){
  const root='/tasks/'+encodeURIComponent(row.runId)+'/'+encodeURIComponent(row.taskId);
  const detail=await api(root),events=await api(root+'/events?afterSequence=0&limit=256'),files=await api(root+'/artifacts');
  evidence.tasks.push({taskId:row.taskId,status:row.status,modelRequests:detail.modelReceipts.length,verifiedRepairs:detail.repairs.filter(r=>r.verified).length,events:events.events.length,files:files.files.map(f=>({path:f.fileName,state:f.state,size:f.size,sha256:f.sha256})),validationStatus:detail.outcome.validationStatus});
  const denied=await context.request.get(origin+'/api/v1/coding-monitor/tasks/wrong-run/'+encodeURIComponent(row.taskId),{headers:{Authorization:'Bearer '+token}});assert.equal(denied.status(),403);
 }
 evidence.checks.foreignRunDenied=true;
 const observed=list.items.find(row=>evidence.tasks.find(t=>t.taskId===row.taskId)?.events>0);assert(observed);await page.locator('#rows tr').filter({hasText:observed.taskId}).locator('button').click();await page.locator('#detail').waitFor({state:'visible'});
 assert.equal(await page.locator('#timeline-panel').getAttribute('open'),null);assert.equal(await page.locator('#artifacts-panel').getAttribute('open'),null);assert.equal(await page.locator('#overview h1').evaluate(e=>getComputedStyle(e).fontSize),'16px');assert.equal(await page.locator('#overview h1').evaluate(e=>getComputedStyle(e).fontWeight),'400');evidence.checks.compactTitleAndDefaultCollapsed=true;
 await page.screenshot({path:output+'/compact-task-overview.png'});
 await page.locator('#timeline-panel > summary').click();await page.locator('#timeline article').first().waitFor({timeout:15000});
 await page.screenshot({path:output+'/task-detail.png',fullPage:true});evidence.checks.actualTimelineVisible=true;
 const choice=list.items.find(row=>evidence.tasks.find(t=>t.taskId===row.taskId)?.verifiedRepairs>0);
 if(choice){
  await page.locator('#back').click();await page.locator('#rows tr').filter({hasText:choice.taskId}).locator('button').click();
  await page.locator('#artifacts-panel > summary').click();
  await page.locator('#files article').first().waitFor({timeout:15000});
  assert((await page.locator('#repairs').innerText()).includes('TypeError'));evidence.checks.realRepairEvidenceVisible=true;
  const sourceName=evidence.tasks.find(t=>t.taskId===choice.taskId).files.find(f=>f.path.endsWith('.py')&&f.size>20).path;const source=page.locator('#files article').filter({hasText:sourceName}).first();await source.getByRole('button',{name:'预览',exact:true}).click();
  await page.locator('#preview').waitFor({state:'visible'});assert((await page.locator('#preview-body').innerText()).length>20);
  await page.screenshot({path:output+'/actual-python-source.png',fullPage:true});await page.locator('#close-preview').click();evidence.checks.dynamicPythonSourcePreview=true;
  const chart=page.locator('#files article').filter({hasText:'.png'}).first();await chart.getByRole('button',{name:'预览',exact:true}).click();await page.locator('#preview-body img').waitFor();
  await page.screenshot({path:output+'/actual-chart.png',fullPage:true});evidence.checks.actualChartPreview=true;
 }
 assert.equal(evidence.pageErrors.length,0);evidence.checks.noBrowserErrors=true;
 await context.close();
}catch(error){evidence.failure=error.message;throw error;}
finally{await writeFile(output+'/browser-verification.json',JSON.stringify(evidence,null,2));await browser.close();}
console.log(JSON.stringify({checks:evidence.checks,tasks:evidence.tasks.length,output}));
