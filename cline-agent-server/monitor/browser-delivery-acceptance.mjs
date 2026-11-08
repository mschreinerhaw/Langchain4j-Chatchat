/** Read-only browser revalidation of recorded real tasks; never dispatches or repairs work. */
import assert from 'node:assert/strict';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';

const origin=process.env.NATIVE_MONITOR_ORIGIN??'http://192.168.195.221:18346';
const output=resolve(process.env.NATIVE_MONITOR_EVIDENCE??'target/native-business-monitor-delivery/browser');await mkdir(output,{recursive:true});
const native=process.env.NATIVE_MONITOR_EXISTING_SESSION,runtime=process.env.NATIVE_MONITOR_RUNTIME_SESSION;
assert(native&&runtime,'Provide existing Native and Runtime session keys; this check creates no tasks');
const {chromium}=await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE??'chatchat-api/web-app/node_modules/playwright-core/index.mjs')));
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH??'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const evidence={scope:'READ_ONLY_REAL_BROWSER_REVALIDATION',startedAt:new Date().toISOString(),postControls:[],pageErrors:[]};
try{
 const context=await browser.newContext({acceptDownloads:true,viewport:{width:1650,height:1120}}),page=await context.newPage();
 page.on('pageerror',e=>evidence.pageErrors.push(e.message));page.on('request',r=>{const path=new URL(r.url()).pathname;if(r.method()==='POST'&&path!=='/api/login')evidence.postControls.push({path});});
 await page.goto(origin+'/monitor/');await page.locator('#username').fill(process.env.NATIVE_MONITOR_USERNAME??'admin');await page.locator('#password').fill(process.env.NATIVE_MONITOR_PASSWORD??'');await page.locator('#login-form button').click();await page.locator('#console').waitFor({state:'visible'});
 async function api(key,suffix=''){const response=await context.request.get(origin+'/api/sessions/'+encodeURIComponent(key)+suffix);assert.equal(response.status(),200);return response.json();}
 async function select(key){await page.goto(origin+'/monitor/#'+encodeURIComponent(key));await page.locator('#connection').filter({hasText:'实时连接'}).waitFor({timeout:60000});}
 async function screenshot(name){await page.screenshot({path:resolve(output,name+'.png'),fullPage:true});}
 await select(native);const nativeBefore=await api(native);assert(nativeBefore.modelReceipts.length>0);assert(nativeBefore.modelReceipts.every(r=>r.source==='NATIVE_CORE_OBSERVATION'));
 await page.locator('[data-tab=models]').click();await page.waitForTimeout(1500);assert.equal(await page.locator('#model-calls tr').count(),nativeBefore.modelReceipts.length+1);await screenshot('native-models');
 const nativeCode=await api(native,'/code');assert(nativeCode.patch?.includes('calculator.mjs'));await page.locator('[data-tab=code]').click();await page.locator('#detail-content > pre').first().filter({hasText:'calculator.mjs'}).waitFor();await screenshot('native-diff');
 evidence.native={sessionId:nativeBefore.sessionId,nativeSessionId:nativeBefore.nativeSessionId,status:nativeBefore.status,modelObservations:nativeBefore.modelReceipts,diffSha256:nativeCode.sha256,modelRowsMatch:true};
 await select(runtime);const before=await api(runtime);assert.equal(before.runtimeStatus,'COMPLETED');assert.equal(before.outcome.validationStatus,'PASSED');assert(before.executions.some(e=>e.status==='FAILED'));assert(before.executions.some(e=>e.status==='SUCCEEDED'));
 await page.locator('[data-tab=models]').click();await page.waitForTimeout(1500);assert.equal(await page.locator('#model-calls tr').count(),before.modelReceipts.length+1);await screenshot('runtime-models');
 await page.locator('[data-tab=python]').click();const failed=before.executions.find(e=>e.status==='FAILED');const block=page.locator('#detail-content > details').filter({has:page.locator('button').filter({hasText:'查看 stderr'})}).filter({hasText:failed.executionId}).first();
 await block.locator('summary').click();const [traceResponse]=await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname.endsWith('/trace')&&r.url().includes('kind=stderr')),block.getByRole('button',{name:'查看 stderr',exact:true}).click()]);const trace=await traceResponse.json();assert.match(trace.content,/NameError/);
 await page.waitForTimeout(6500);assert(await block.getAttribute('open')!==null);assert((await block.locator('pre').last().innerText()).includes(trace.fileId));await screenshot('runtime-error-retained');evidence.openedErrorSurvivesUpdates=true;
 await page.locator('[data-tab=files]').click();const downloadEvent=page.waitForEvent('download');await page.locator('#detail-content a').filter({hasText:'report.md'}).click();const download=await downloadEvent;await download.saveAs(resolve(output,'report.md'));evidence.download={suggestedFilename:download.suggestedFilename(),sha256:createHash('sha256').update(await readFile(resolve(output,'report.md'))).digest('hex')};assert.equal(evidence.download.sha256,'d32abc08fc777dbc08958723775e6f5602d4d7f7d321a68b9e6c15d187fed5c2');await screenshot('runtime-files');
 await page.reload();await page.locator('#connection').filter({hasText:'实时连接'}).waitFor({timeout:60000});await context.setOffline(true);await page.waitForTimeout(3000);await context.setOffline(false);await page.locator('#connection').filter({hasText:'实时连接'}).waitFor({timeout:60000});
 const after=await api(runtime),nativeAfter=await api(native);assert.equal(after.taskId,before.taskId);assert.equal(after.runtimeStatus,before.runtimeStatus);assert.equal(after.executions.length,before.executions.length);assert.equal(after.modelReceipts.length,before.modelReceipts.length);assert.equal(nativeAfter.modelReceipts.length,nativeBefore.modelReceipts.length);
 evidence.runtime={taskId:after.taskId,sessionId:after.sessionId,nativeSessionId:after.nativeSessionId,status:after.runtimeStatus,validationStatus:after.outcome.validationStatus,modelCalls:after.modelReceipts.length,executions:after.executions.map(e=>({operationId:e.executionId,status:e.status,codeHash:e.codeHash}))};evidence.refreshAndReconnectPreserveIdentity=true;assert.deepEqual(evidence.postControls,[]);assert.deepEqual(evidence.pageErrors,[]);evidence.status='PASS';
}catch(error){evidence.status='FAIL';evidence.error=error.message;throw error;}
finally{evidence.finishedAt=new Date().toISOString();await writeFile(resolve(output,'browser-delivery.json'),JSON.stringify(evidence,null,2));await browser.close();}
