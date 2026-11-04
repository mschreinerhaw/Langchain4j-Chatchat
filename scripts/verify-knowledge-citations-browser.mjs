// UI fixtures exercise the production bundle; source resolution uses the real authorized API.
import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const session=JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE,'utf8'));
const citation=JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_CITATION_FILE,'utf8'));
assert.match(citation.citationId,/^[0-9a-f]{64}$/);
assert.ok(citation.artifact?.artifactId,'An actual published report reference is required');
const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless:true,args:['--no-proxy-server']});
const page=await browser.newPage({viewport:{width:1440,height:1000}});
await page.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),session);
const errors=[];page.on('pageerror',error=>errors.push(error.name));
try {
  await page.goto((process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100')+'/#/w/zhice/chat',{waitUntil:'domcontentloaded'});
  await page.waitForFunction(()=>Array.isArray(document.querySelector('#app')?._vnode?.component?.proxy?.$refs?.activeViewComponent?.messages),null,{timeout:45000});
  async function open(id) {
    await page.evaluate(value=>{
      const view=document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent;
      view.messages=[{id:'knowledge-browser-fixture',role:'assistant',content:'[引用原文](runtime-evidence:knowledge:'+value+')',status:'completed'}];
    },id);
    await page.locator('a[href="#knowledge-evidence-'+id+'"]').first().click();
    await page.getByRole('dialog').waitFor();
  }
  await open('0'.repeat(64));
  await page.getByRole('alert').filter({hasText:'无法读取此引用'}).waitFor();
  await page.getByRole('dialog').getByRole('button',{name:'关闭',exact:true}).click();
  await open(citation.citationId);
  await page.waitForFunction(()=>document.querySelector('.knowledge-source-dialog pre')?.textContent?.trim().length>0);
  const text=await page.getByRole('dialog').innerText();
  assert.ok(text.includes('已核验当前访问权限和原文版本'));
  assert.ok(text.includes('原文索引未提供页码'));
  await page.getByRole('dialog').getByRole('button',{name:'关闭',exact:true}).click();
  await page.evaluate(artifact=>{
    const view=document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent;
    view.messages=[{id:'published-knowledge-report',role:'assistant',content:'',status:'completed',uiArtifact:artifact}];
  },citation.artifact);
  await page.locator('.enterprise-ui-artifact a[href="#knowledge-evidence-'+citation.citationId+'"]').first().click();
  await page.waitForFunction(()=>document.querySelector('.knowledge-source-dialog pre')?.textContent?.trim().length>0);
  assert.deepEqual(errors,[]);
  const report={productionBundle:true,injectedUiFixture:true,liveResolver:true,
    invalidCitationDenied:true,authorizedSourceVisible:true,actualPublishedArtifactCitationOpens:true,unavailablePageExplained:true,pageErrors:errors};
  writeFileSync(process.env.CHATCHAT_BROWSER_REPORT || 'target/knowledge-browser-summary.json',JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));
} finally {await browser.close();}
