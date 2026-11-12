/** Browser check against real Java monitoring APIs; CSP stays enforced. */
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
const {chromium}=await import(pathToFileURL(process.env.RUNTIME_TEST_PLAYWRIGHT_MODULE||resolve('chatchat-api/web-app/node_modules/playwright-core/index.mjs')).href);
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {join} from 'node:path';
const origin=process.env.RUNTIME_TEST_ORIGIN,out=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/native-temporal-browser');assert.ok(origin,'Explicit candidate origin required');await mkdir(out,{recursive:true});
const u=JSON.parse(await readFile(process.env.RUNTIME_TEST_USERS_FILE,'utf8'))[0];
const r=await fetch(origin+'/api/v1/enterprise/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u.username,password:u.password})});const auth=(await r.json()).data;
const task=JSON.parse(await readFile(process.env.RUNTIME_TEST_TASK_FILE,'utf8')).tasks[0];
const browser=await chromium.launch({headless:true,...(process.env.RUNTIME_TEST_CHROME?{executablePath:process.env.RUNTIME_TEST_CHROME}:{})});
const result={passed:false,pageErrors:[],failures:[]};
try{
 const context=await browser.newContext({viewport:{width:1600,height:1000}});
 await context.addInitScript(token=>sessionStorage.setItem('runtime-coding-monitor-session',token),auth.token);
 const page=await context.newPage();page.on('pageerror',e=>result.pageErrors.push(e.message));
 page.on('response',r=>{if(r.url().includes('/api/')&&r.status()>=400)result.failures.push({path:new URL(r.url()).pathname,status:r.status()});});
 await page.goto(origin+'/monitor/',{waitUntil:'domcontentloaded'});await page.locator('#rows button.link').first().waitFor({timeout:30000});
 await page.locator('#nodes-panel').waitFor({state:'visible'});await page.locator('#nodes-panel > summary').click();await page.waitForTimeout(500);
 assert.ok((await page.locator('#nodes').innerText()).includes('cline-worker-1'));
 await page.screenshot({path:join(out,'task-list.png'),fullPage:true});
 await page.locator('#rows tr').filter({hasText:task.taskId}).locator('button.link').click();await page.locator('#overview').getByText('Workflow ID',{exact:true}).waitFor();
 assert.equal((await page.locator('#timeline-panel').getAttribute('open'))!==null,false);assert.equal((await page.locator('#artifacts-panel').getAttribute('open'))!==null,false);
 assert.ok((await page.locator('#overview').innerText()).includes('WORKFLOW_EXECUTION_STATUS_COMPLETED'));
 await page.locator('#timeline-panel > summary').click();await page.waitForTimeout(1000);
 assert.ok(await page.locator('#timeline > *').count()>0);await page.screenshot({path:join(out,'task-detail.png'),fullPage:true});
 await context.setOffline(true);await page.waitForTimeout(500);await context.setOffline(false);await page.reload({waitUntil:'domcontentloaded'});await page.locator('#rows button.link').first().waitFor();await page.locator('#rows tr').filter({hasText:task.taskId}).locator('button.link').click();await page.locator('#overview').getByText('Workflow ID',{exact:true}).waitFor();
 assert.deepEqual(result.pageErrors,[]);assert.deepEqual(result.failures,[]);result.passed=true;result.taskId=task.taskId;result.workflowVisible=true;result.historyAfterReload=true;result.offlineReconnect=true;result.nativeTimelineVisible=true;result.defaultCollapsed=true;
}catch(e){result.error=e.message;process.exitCode=1;console.error(e.message);}finally{await writeFile(join(out,'verification.json'),JSON.stringify(result,null,2));await browser.close();}
