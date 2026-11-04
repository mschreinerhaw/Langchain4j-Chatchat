// Production UI fixture; all references, the published artifact and source reads are real.
import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const session=JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE,'utf8'));
const fixture=JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_CITATION_FILE,'utf8'));
assert.ok(fixture.artifact?.artifactId,'Actual published artifact required');
assert.ok(fixture.citations.length>=2,'News and market citations required');
for(const source of fixture.citations)assert.match(source.citationId,/^[0-9a-f]{64}$/);
const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
const errors=[];
try {
  const page=await browser.newPage({viewport:{width:1440,height:1000}});
  page.on('pageerror',error=>errors.push(error.name));
  await page.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),session);
  await page.goto((process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100')+'/#/w/zhice/chat',{waitUntil:'domcontentloaded'});
  await page.waitForFunction(()=>Array.isArray(document.querySelector('#app')?._vnode?.component?.proxy?.$refs?.activeViewComponent?.messages),null,{timeout:45000});
  const checked=[];
  for(const source of fixture.citations) {
    await page.evaluate(id=>{
      const view=document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent;
      view.messages=[{id:'resource-source-fixture',role:'assistant',content:'[引用](runtime-evidence:knowledge:'+id+')',status:'completed'}];
    },source.citationId);
    const response=page.waitForResponse(r=>r.url().includes('/agent/knowledge/citations/'+source.citationId));
    await page.locator('a[href="#knowledge-evidence-'+source.citationId+'"]').first().click();
    const resolved=await response;assert.equal(resolved.status(),200);assert.match(resolved.headers()['cache-control']||'',/no-store/);
    await page.waitForFunction(()=>document.querySelector('.knowledge-source-dialog')?.textContent?.includes('已核验当前访问权限和原文版本'));
    const dialog=page.getByRole('dialog');const text=await dialog.innerText();
    if(source.resourceId==='financial_news')assert.ok(text.includes('发布时间：')&&text.includes('采集时间：'));
    if(source.resourceId==='financial_market') {
      assert.ok(text.includes('数据截止：')&&text.includes('不代表实时行情'));
      if(source.rowCount>0)assert.ok(await dialog.locator('table tbody tr').count()>0);
    }
    checked.push(source.resourceId);
    await dialog.getByRole('button',{name:'关闭',exact:true}).click();
  }
  await page.evaluate(artifact=>{
    document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent.messages=[{id:'published-resource-report',role:'assistant',content:'',status:'completed',uiArtifact:artifact}];
  },fixture.artifact);
  const published=fixture.citations[0];
  await page.locator('.enterprise-ui-artifact a[href="#knowledge-evidence-'+published.citationId+'"]').first().click();
  await page.waitForFunction(()=>document.querySelector('.knowledge-source-dialog')?.textContent?.includes('已核验当前访问权限和原文版本'));
  await page.getByRole('dialog').getByRole('button',{name:'关闭',exact:true}).click();
  await page.evaluate(()=>{
    document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent.messages=[{id:'invalid-resource-reference',role:'assistant',content:'[引用](runtime-evidence:knowledge:'+'0'.repeat(64)+')',status:'completed'}];
  });
  await page.locator('a[href="#knowledge-evidence-'+'0'.repeat(64)+'"]').first().click();
  await page.getByRole('alert').filter({hasText:'无法读取此引用'}).waitFor();
  assert.equal(await page.getByRole('dialog').locator('pre,table').count(),0);
  assert.deepEqual(errors,[]);
  const report={productionBundle:true,injectedUiFixture:true,liveResolver:true,resourceTypes:[...new Set(checked)],authorizedViews:checked.length,
    publishedArtifactCitationOpens:true,noStoreVerified:true,invalidReferenceClearsPriorSource:true,pageErrors:errors};
  writeFileSync(process.env.CHATCHAT_BROWSER_REPORT || 'target/resource-citations-browser-summary.json',JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));
}finally{await browser.close();}
