// Real deployed page and persisted Sandbox receipts only; no request interception.
import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFile,writeFile,mkdir } from 'node:fs/promises';
import assert from 'node:assert/strict';
const base=process.env.CHATCHAT_STAGE_BASE_URL||'http://127.0.0.1:28880';
const supplied=JSON.parse(await readFile(process.env.CHATCHAT_STAGE_SESSION_FILE,'utf8')),session=supplied.data??supplied;
const cases=JSON.parse(await readFile(process.env.CHATCHAT_PYTHON_REUSE_CASES_FILE,'utf8'));
const output=process.env.CHATCHAT_PYTHON_REUSE_OUTPUT;if(!output)throw new Error('Set CHATCHAT_PYTHON_REUSE_OUTPUT');await mkdir(output,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHATCHAT_BROWSER_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
const proof={scope:'Real Vue page, actual Cline SDK model calls and Python Sandbox receipts; controlled samples',cases:[],errors:[]};
try{
 const context=await browser.newContext({viewport:{width:1440,height:1200},locale:'zh-CN',timezoneId:'Asia/Shanghai'});
 await context.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),session);
 const page=await context.newPage();page.on('pageerror',e=>proof.errors.push(e.message));
 for(const test of cases){
  const response=await context.request.get(base+'/api/v1/data-science/python/code-reuse/'+encodeURIComponent(test.requestId),{headers:{Authorization:'Bearer '+session.token}});
  assert.equal(response.status(),200);const body=await response.json();assert.equal(body.code,200);const state=body.data;
  assert.equal(state.status,test.status||'COMPLETED');assert.equal(state.resultAvailable,state.status==='COMPLETED');
  await page.goto(base+'/#/w/data-science/dataScienceDevelop');
  await page.waitForSelector('.python-reuse-panel',{timeout:60000});
  await page.evaluate(({id,session})=>localStorage.setItem(`python-code-reuse:${session.user?.tenantId||''}:${session.user?.id||''}:data-science`,id),{id:test.requestId,session});
  await page.reload();await page.locator('.python-reuse-panel > summary').click();
  await page.waitForFunction(expected=>document.querySelectorAll('.python-reuse-panel > details').length===expected.count&&document.querySelector('.python-reuse-panel')?.innerText.includes(expected.status),{count:state.executions.length,status:state.status},{timeout:60000});
  const panel=page.locator('.python-reuse-panel');assert.equal((await panel.innerText()).includes(state.status),true);
  assert.equal(await panel.locator('.python-reuse-spinner').count(),0);
  if(test.case==='C'){
   assert.equal(state.executions.length,2);assert.equal(state.executions[0].error?.exceptionType,'KeyError');
   await panel.locator(':scope > details > summary').first().click();assert.match(await panel.innerText(),/KeyError/);
   assert.match(await panel.innerText(),/project\/main.py/);
   assert.equal((await panel.locator('.python-code-diff').count())>0,true);
   const failed=panel.locator(':scope > details').first();await failed.scrollIntoViewIfNeeded();
   await page.screenshot({path:output+'/python-code-reuse-C-error-visible.png'});
   await panel.locator('.python-code-diff').first().scrollIntoViewIfNeeded();
   await page.screenshot({path:output+'/python-code-reuse-C-diff-visible.png'});
  }
  const screenshot=`python-code-reuse-${test.case}.png`;await panel.screenshot({path:output+'/'+screenshot});
  await page.reload();await page.locator('.python-reuse-panel > summary').click();
  await page.waitForFunction(expected=>document.querySelectorAll('.python-reuse-panel > details').length===expected.count&&document.querySelector('.python-reuse-panel')?.innerText.includes(expected.status),{count:state.executions.length,status:state.status},{timeout:60000});
  assert.equal((await panel.innerText()).includes(state.status),true);
  proof.cases.push({case:test.case,requestId:test.requestId,taskId:state.taskId,status:state.status,executions:state.executions.length,restoredAfterReload:true,sourceFiles:state.sources.length,screenshot});
 }
 assert.deepEqual(proof.errors,[]);await writeFile(output+'/browser-proof.json',JSON.stringify(proof,null,2));
 console.log(JSON.stringify({passed:proof.cases.length,errors:proof.errors.length}));
}finally{await browser.close();}
