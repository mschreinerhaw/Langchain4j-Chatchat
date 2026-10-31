import {chromium} from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const origin=process.env.STUDIO_ORIGIN,authFile=process.env.STUDIO_AUTH_FILE,state=process.env.STUDIO_OUTPUT;
assert.ok(origin&&authFile&&state,'Set STUDIO_ORIGIN, STUDIO_AUTH_FILE and STUDIO_OUTPUT');
const auth=JSON.parse(fs.readFileSync(authFile)),task=JSON.parse(fs.readFileSync(path.join(state,'agent-task.json')));
const out=process.env.STUDIO_SCREENSHOTS||'../../target/studio-python-screenshots';fs.mkdirSync(out,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try{
 const page=await browser.newPage({viewport:{width:1680,height:1100}});
 await page.addInitScript(a=>localStorage.setItem('chatchat.auth.session',JSON.stringify(a)),auth);
 await page.goto(origin+'/index.html#/w/zhice/tasks');
 await page.getByPlaceholder('搜索任务编号、问题、租户或状态').fill(task.taskId);
 const row=page.locator('.task-table > button').filter({has:page.locator('.task-id',{hasText:task.taskId.slice(0,8)})});
 await row.first().waitFor({timeout:60000});await row.first().click();
 await page.locator('.runtime-tabs button.active').filter({hasText:'事件'}).waitFor({timeout:60000});
 await page.locator('.runtime-tabs button').filter({hasText:'链路探索'}).click();
 await page.getByRole('button',{name:'计划快照',exact:true}).click();
 await page.locator('.plan-dag-layout').waitFor({timeout:60000});
 await page.waitForFunction(()=>document.querySelectorAll('.plan-flow-node').length>=4,{},{timeout:60000});
 const fit=page.getByRole('button',{name:'适配视图',exact:true});if(await fit.count())await fit.first().click();
 await page.waitForTimeout(600);
 await page.screenshot({path:path.join(out,'agent-service-plan-real.png'),fullPage:true});
 console.log('Captured actual SQL/Python Agent plan snapshot; no mocked API or edited plan.');
}finally{await browser.close();}
