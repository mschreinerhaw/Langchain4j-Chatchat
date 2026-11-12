/** Browser regression of the real monitor assets against explicitly mocked control responses. */
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
const assets=new URL('../chatchat-api/src/main/resources/coding-monitor/',import.meta.url);
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
try{
 const page=await browser.newPage(),errors=[],calls=[];page.on('pageerror',e=>errors.push(e.message));
 const rows=[{taskId:'active',status:'RUNNING'},{taskId:'unknown',status:'RUNNING',lastError:'CANCEL_REQUESTED'},{taskId:'done',status:'COMPLETED'},{taskId:'denied',status:'RUNNING'}].map(row=>({...row,runId:'run',name:row.taskId,createdAt:new Date().toISOString()}));
 await page.addInitScript(()=>sessionStorage.setItem('runtime-coding-monitor-session','fixture-token'));
 await page.route('http://monitor.test/**',async route=>{
  const url=new URL(route.request().url());
  if(url.pathname.startsWith('/monitor/')){const file=url.pathname==='/monitor/'?'index.html':url.pathname.split('/').at(-1);await route.fulfill({body:await readFile(new URL(file,assets)),contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});return;}
  let data;
  if(url.pathname.endsWith('/auth/me'))data={displayName:'Fixture account'};
  else if(url.pathname.endsWith('/cancel')){
   const id=url.pathname.split('/').at(-2);calls.push(id);
   if(id==='denied'){await route.fulfill({status:403,json:{code:403,message:'Forbidden fixture'}});return;}
   const row=rows.find(x=>x.taskId===id);if(id==='active')row.status='CANCELLED';else row.stopPending=true;
   data={outcome:{status:row.status},stopState:id==='active'?'TERMINAL':'STOPPING'};
  }else {const name=url.searchParams.get('name');if(name==='stale')await new Promise(r=>setTimeout(r,300));const listed=name==='latest'?rows.slice(1,3):rows;data={items:listed,total:listed.length};}
  await route.fulfill({json:{code:200,data}});
 });
 await page.goto('http://monitor.test/monitor/');await page.locator('#rows tr').nth(3).waitFor();
 assert.equal(await page.locator('#rows input:disabled').count(),1);
 assert.match(await page.locator('#rows tr').nth(1).textContent(),/停止待确认/);
 page.once('dialog',dialog=>dialog.dismiss());await page.locator('.stop-task').first().click();assert.equal(calls.length,0);
 await page.locator('#select-all').check();await page.waitForFunction(()=>document.querySelector('#selection-count').textContent.includes('3'));
 page.once('dialog',dialog=>dialog.accept());await page.locator('#stop-selected').click();
 await page.waitForFunction(()=>document.querySelector('#stop-result').textContent.includes('已处理 3/3'));
 assert.deepEqual(calls.sort(),['active','denied','unknown']);assert.ok(!calls.includes('done'));
 assert.match(await page.locator('#stop-result').textContent(),/停止待确认 1 · 失败 1/);
 assert.equal(await page.locator('#rows input:checked').count(),1);
 assert.match(await page.locator('#rows tr').nth(0).textContent(),/已取消/);
 assert.match(await page.locator('#rows tr').nth(1).textContent(),/停止待确认/);
 assert.match(await page.locator('#rows tr').nth(3).textContent(),/Forbidden fixture/);
 await page.locator('#filters input[name=name]').fill('stale');await page.locator('#filters button').click();
 await page.locator('#filters input[name=name]').fill('latest');await page.locator('#filters button').click();
 await page.waitForTimeout(600);assert.equal(await page.locator('#rows tr').count(),2);
 assert.equal(await page.locator('#rows input:checked').count(),0);assert.equal(await page.locator('#stop-selected').isDisabled(),true);
 assert.deepEqual(errors,[]);console.log('PASS: batch selection, terminal exclusion, declined confirmation, pending stop, partial failure and no browser errors (mocked API regression)');
}finally{await browser.close();}
