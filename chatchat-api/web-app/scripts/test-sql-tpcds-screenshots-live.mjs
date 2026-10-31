import {chromium} from 'playwright-core';import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
assert.ok(process.env.TPCDS_AUTH_FILE&&process.env.TPCDS_STATE_FILE&&process.env.TPCDS_ORIGIN,'Set TPCDS_AUTH_FILE, TPCDS_STATE_FILE, TPCDS_ORIGIN');
const auth=JSON.parse(fs.readFileSync(process.env.TPCDS_AUTH_FILE)),state=JSON.parse(fs.readFileSync(process.env.TPCDS_STATE_FILE));
const out=path.resolve(process.env.TPCDS_SCREENSHOTS||'../../target/tpcds-screenshots');fs.mkdirSync(out,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try{
 const ctx=await browser.newContext({viewport:{width:1680,height:1100}});await ctx.addInitScript(a=>localStorage.setItem('chatchat.auth.session',JSON.stringify(a)),auth);
 const page=await ctx.newPage();await page.goto(process.env.TPCDS_ORIGIN+'/index.html#/w/data-science/sqlStudio');
 if(process.env.TPCDS_SCREENSHOT_MODE==='agent'){
  assert.ok(process.env.TPCDS_TASK_ID,'Set the actual TPCDS_TASK_ID');
  await page.goto(process.env.TPCDS_ORIGIN+'/index.html#/w/zhice/tasks');
  await page.getByPlaceholder('搜索任务编号、问题、租户或状态').fill(process.env.TPCDS_TASK_ID);
  const task=page.locator('.task-table > button').filter({has:page.locator('.task-id',{hasText:process.env.TPCDS_TASK_ID.slice(0,8)})});await task.first().waitFor({timeout:60000});await task.first().click();
  await page.locator('.runtime-tabs button.active').filter({hasText:'事件'}).waitFor({timeout:60000});
  await page.locator('.runtime-tabs button').filter({hasText:'链路探索'}).click();
  await page.locator('.plan-dag-layout').waitFor({timeout:60000});
  await page.waitForFunction(()=>document.querySelectorAll('.plan-flow-node').length>2,{},{timeout:60000});
  await page.screenshot({path:path.join(out,'agent-call-chain-real.png'),fullPage:true});console.log('Captured actual Agent task chain');
 }else if(process.env.TPCDS_SCREENSHOT_MODE==='exploration'){
  assert.ok(state.exploration?.id&&state.exploration.nodeId,'Actual exploration state required');
  await page.goto(process.env.TPCDS_ORIGIN+'/index.html#/w/data-exploration/dataExploration?exploration='+state.exploration.id+'&node='+state.exploration.nodeId);
  await page.locator('.exploration-chart-card').waitFor({timeout:60000});
  await page.locator('.exploration-chart-card img, .exploration-chart-card .js-plotly-plot, .exploration-chart-card svg').first().waitFor({timeout:60000});
  await page.waitForFunction(()=>{const card=document.querySelector('.exploration-chart-card');const img=card?.querySelector('img');return img?img.complete&&img.naturalWidth>0&&img.getBoundingClientRect().height>100:!!card?.querySelector('.js-plotly-plot .main-svg');},{},{timeout:60000});
  await page.screenshot({path:path.join(out,'python-analysis-real.png'),fullPage:true});
  await page.getByRole('button',{name:'分析说明',exact:true}).click();await page.getByRole('dialog',{name:'分析说明'}).waitFor();
  await page.screenshot({path:path.join(out,'python-analysis-evidence-real.png'),fullPage:true});console.log('Captured actual Python chart and execution evidence');
 }else{
 const picker=page.getByRole('combobox',{name:'SQL 工程',exact:true});await picker.waitFor({timeout:60000});await picker.selectOption(state.project.id);
 await page.locator('.sql-tree-file button[title="sql/tpcds_monthly.sql"]').click();
 await page.getByRole('button',{name:'模板管理与发布',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'发布 MCP SQL 模板'});await dialog.waitFor();
 const select=dialog.locator('select').first();await select.selectOption(state.monthly.row.id);await dialog.locator('.sql-test-result-preview').waitFor({timeout:60000});
 await page.screenshot({path:path.join(out,'sql-publication-real.png'),fullPage:true});
 await dialog.locator('.sql-test-result-preview').scrollIntoViewIfNeeded();
 await page.screenshot({path:path.join(out,'sql-preview-real.png'),fullPage:true});console.log('Captured actual SQL publication and result preview');
 }
}finally{await browser.close();}
