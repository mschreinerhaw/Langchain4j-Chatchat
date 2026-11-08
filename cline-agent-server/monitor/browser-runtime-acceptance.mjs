/** Browser dispatch through the real Runtime entry; this script never executes or repairs Python. */
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const origin=process.env.NATIVE_MONITOR_ORIGIN??'http://192.168.195.221:18346',output=resolve(process.env.NATIVE_MONITOR_EVIDENCE??'target/native-monitor/joint');await mkdir(output,{recursive:true});
const {chromium}=await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE??'../Langchain4j-Chatchat/chatchat-api/web-app/node_modules/playwright-core/index.mjs')));
const evidence={scope:'REAL_BROWSER_RUNTIME_NATIVE_MANAGED_PYTHON',startedAt:new Date().toISOString(),requests:[],pageErrors:[]};
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH??'C:/Program Files/Google/Chrome/Application/chrome.exe'});
try{
 const context=await browser.newContext({viewport:{width:1650,height:1120},acceptDownloads:true}),page=await context.newPage();page.on('pageerror',e=>evidence.pageErrors.push(e.message));
 page.on('request',r=>{const path=new URL(r.url()).pathname;if(r.method()==='POST'&&path!=='/api/login')evidence.requests.push({path,body:r.postDataJSON(),timestamp:new Date().toISOString()});});
 await page.goto(origin+'/monitor/');await page.locator('#username').fill(process.env.NATIVE_MONITOR_USERNAME??'admin');await page.locator('#password').fill(process.env.NATIVE_MONITOR_PASSWORD??'');await page.locator('#login-form button').click();await page.locator('#console').waitFor({state:'visible'});
 async function api(path){const r=await context.request.get(origin+'/api'+path);assert.equal(r.status(),200);return r.json();}
 if(process.env.NATIVE_MONITOR_RUNTIME_SESSION){await page.goto(origin+'/monitor/#'+encodeURIComponent(process.env.NATIVE_MONITOR_RUNTIME_SESSION),{waitUntil:'domcontentloaded'});}
 else{
  evidence.originalRequest=process.env.NATIVE_MONITOR_BUSINESS_REQUEST??'JOINT_MONITOR_CSV_REPAIR. Analyze only the selected technical CSV: count parsed data rows and calculate the exact Decimal sum of column value. These two computed facts, source binding and a Markdown report are the complete objective. First reproduce the existing joint_analysis.py unchanged through Managed Python, read its actual error, then repair that same source yourself and execute again. Write rows.jsonl matching the complete outputContract.rowSchema, including verifiedSummary. Also save report.md under the authorized results directory. No external data, charts or financial interpretation is requested. Review the actual archived result before finishing.';
  await page.locator('#new').click();await page.locator('#mode').selectOption('runtime');await page.locator('#model').selectOption(process.env.NATIVE_MONITOR_MODEL??'qwen3.7-plus');await page.locator('#validation-profile').selectOption('csv-count-sum-v1');await page.locator('#value-field').fill('value');
  await page.locator('#inputs').selectOption(process.env.NATIVE_MONITOR_INPUT_ASSET);await page.locator('#instruction').fill(evidence.originalRequest);
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/sessions'&&r.request().method()==='POST');await page.locator('#create-form button[type=submit]').click();const r=await response;evidence.dispatch=await r.json();assert.equal(r.status(),202);
  await writeFile(resolve(output,'browser-dispatch.json'),JSON.stringify(evidence,null,2));
  await page.waitForURL(/#runtime/, {timeout:180000});
 }
 const key=decodeURIComponent(new URL(page.url()).hash.slice(1));evidence.sessionKey=key;
 const deadline=Date.now()+15*60000;let snapshot;
 while(Date.now()<deadline){snapshot=await api('/sessions/'+encodeURIComponent(key));evidence.lastObserved=snapshot;console.log(JSON.stringify({sessionId:snapshot.sessionId,taskId:snapshot.taskId,native:snapshot.status,runtime:snapshot.runtimeStatus,executions:snapshot.executions?.map(e=>({operationId:e.executionId,status:e.status,codeHash:e.codeHash}))}));if(['COMPLETED','PARTIAL','FAILED','CANCELLED','BUDGET_EXCEEDED'].includes(snapshot.runtimeStatus))break;if(evidence.pageErrors.length)throw Error(evidence.pageErrors.join('; '));await page.waitForTimeout(2500);}
 evidence.final=snapshot;await page.waitForTimeout(2000);
 for(const tab of ['python','code','models','files','diagnostics']){await page.locator('[data-tab='+tab+']').click();await page.waitForTimeout(1500);await page.screenshot({path:resolve(output,'joint-'+tab+'.png'),fullPage:true});}
 evidence.code=await api('/sessions/'+encodeURIComponent(key)+'/code');evidence.assets=await api('/sessions/'+encodeURIComponent(key)+'/assets');
 const before=snapshot;await page.reload();await page.locator('#connection').filter({hasText:'实时连接'}).waitFor({state:'visible',timeout:60000});const after=await api('/sessions/'+encodeURIComponent(key));assert.equal(after.runtimeStatus,before.runtimeStatus);assert.equal(after.sessionId,before.sessionId);evidence.refreshPreservesIdentity=true;
 const files=Array.isArray(evidence.assets)?evidence.assets:evidence.assets.items??evidence.assets.files??[];evidence.downloads=[];
 const {createHash}=await import('node:crypto');
 for(const f of files){const r=await context.request.get(origin+'/api/assets/'+f.id+'/content');const bytes=await r.body();const digest=createHash('sha256').update(bytes).digest('hex');evidence.downloads.push({id:f.id,fileName:f.fileName,size:bytes.length,sha256:digest,httpStatus:r.status(),hashMatches:digest===f.sha256,sizeMatches:bytes.length===f.size});}
 assert.deepEqual(evidence.pageErrors,[]);assert.equal(snapshot.runtimeStatus,'COMPLETED');
 const executions=snapshot.executions??[];assert(executions.some(e=>e.status==='FAILED'));assert(executions.some(e=>e.status==='SUCCEEDED'));assert(new Set(executions.map(e=>e.codeHash)).size>=2);
 assert(evidence.downloads.length>0);assert(evidence.downloads.every(d=>d.httpStatus===200&&d.hashMatches&&d.sizeMatches));evidence.status='PASS_JOINT_DELIVERY';
}catch(e){evidence.status='FAIL';evidence.error=e.message;throw e;}
finally{evidence.finishedAt=new Date().toISOString();await writeFile(resolve(output,'browser-runtime.json'),JSON.stringify(evidence,null,2));await browser.close();}
