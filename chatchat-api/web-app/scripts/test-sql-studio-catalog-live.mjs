import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
assert.ok(process.env.CATALOG_ORIGIN && process.env.CATALOG_AUTH_FILE && process.env.CATALOG_OUTPUT, 'Set CATALOG_ORIGIN, CATALOG_AUTH_FILE and CATALOG_OUTPUT');
const auth=JSON.parse(fs.readFileSync(process.env.CATALOG_AUTH_FILE));
const out=path.resolve(process.env.CATALOG_OUTPUT);fs.mkdirSync(out,{recursive:true});
const titles=JSON.parse(process.env.CATALOG_TITLES || '["TPCDS 248 商品1年度销售汇总验收","TPCDS 248 单品期间月度销售分析验收"]');
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try {
  const context=await browser.newContext({viewport:{width:1870,height:1100}});
  await context.addInitScript(a=>{
    sessionStorage.setItem('chatchat.mcp.admin.token',a.token);sessionStorage.setItem('chatchat.mcp.admin.user',a.username);
    sessionStorage.setItem('chatchat.mcp.admin.expiresAt',String(a.expiresAt));sessionStorage.setItem('chatchat.mcp.admin.lastActivityAt',String(Date.now()));
  },auth);
  const page=await context.newPage(),snapshots=[];
  page.on('response',async response=>{
    const url=new URL(response.url());
    if(url.pathname.startsWith('/api/v1/database-query/studio-publications') && response.ok()) {
      const body=await response.json();snapshots.push({path:url.pathname,response:body});
    }
  });
  await page.goto(process.env.CATALOG_ORIGIN+'/admin/index.html');
  await page.getByText('查询能力模板',{exact:true}).click();
  await page.getByRole('menuitem',{name:'关系库查询',exact:true}).click();
  const search=page.getByPlaceholder('搜索名称、描述、实现步骤、工作台步骤、标签或数据源');
  if(process.env.CATALOG_CHECK_FILTERS==='1'){
    await page.locator('.catalog-table .el-table__row').first().waitFor({timeout:60000});
    const next=page.locator('.pagination-row .btn-next').first();
    const canPage=await next.isEnabled();
    if(canPage){await next.click();await page.waitForFunction(()=>document.querySelector('.pagination-row')?.innerText.includes('第 2 /'));await page.screenshot({path:path.join(out,'catalog-page-2.png'),fullPage:true});await page.locator('.pagination-row .btn-prev').first().click();}
    await page.locator('.catalog-filter-select').first().click();
    await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({hasText:/^SQL Studio$/}).click();
    await page.waitForFunction(()=>document.querySelectorAll('.catalog-table .el-table__row').length===4);
    assert.equal(await page.locator('.catalog-table .el-table__row').count(),4);
    await page.screenshot({path:path.join(out,'catalog-source-filter.png'),fullPage:true});
    fs.writeFileSync(path.join(out,'filter-and-pagination.json'),JSON.stringify({sourceFilter:'SQL_STUDIO',matchedRows:4,paginationNextAvailable:canPage,page2Verified:canPage,writesSaved:false},null,2));
  }
  await search.fill('SQL Studio');
  for(const title of titles) await page.locator('.el-table__row').filter({hasText:title}).first().waitFor({timeout:60000});
  await page.screenshot({path:path.join(out,'catalog-after.png'),fullPage:true});
  for(let i=0;i<titles.length;i++) {
    const row=page.locator('.el-table__row').filter({hasText:titles[i]}).first();
    assert.equal(await row.getByRole('button',{name:'编辑',exact:true}).count(),0,'Studio snapshot must not use the manual-template editor');
    assert.equal(await row.getByRole('button',{name:'删除',exact:true}).count(),0);
    await row.getByRole('button',{name:'详情',exact:true}).click();
    const dialog=page.getByRole('dialog',{name:titles[i],exact:true});await dialog.waitFor();
    assert.match(await dialog.locator('pre').first().innerText(),/SELECT/i);
    assert.ok((await dialog.innerText()).includes('真实试执行记录'));
    await page.screenshot({path:path.join(out,`catalog-detail-${i+1}.png`),fullPage:true});
    await dialog.getByRole('button',{name:'关闭',exact:true}).click();
  }
  fs.writeFileSync(path.join(out,'catalog-responses.json'),JSON.stringify({capturedAt:new Date().toISOString(),snapshots},null,2));
  console.log(JSON.stringify({status:'PASSED',visibleTitles:titles,details:titles.length,writesSaved:false}));
} finally {await browser.close();}
