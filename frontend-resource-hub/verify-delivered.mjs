// Validate actual bytes recovered through authenticated Runtime/Silo downloads.
import {chromium} from 'playwright';
import {readdir,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const root=resolve(process.argv[2]),scenario=process.argv[3];
async function find(dir){for(const e of await readdir(dir,{withFileTypes:true})){const p=join(dir,e.name);if(e.isDirectory()){const f=await find(p);if(f)return f}else if(e.name==='standalone.html')return p}}
const file=await find(root);assert(file,'Delivered standalone.html required');
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,chromiumSandbox:process.platform!=='win32',env:Object.fromEntries(['PATH','HOME','LANG'].filter(k=>process.env[k]).map(k=>[k,process.env[k]]))});
const checks=[];
try{for(const viewport of [{width:1440,height:900},{width:390,height:844}]){
 const context=await browser.newContext({viewport,serviceWorkers:'block'}),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await context.route(/^https?:/,r=>{errors.push('External request '+r.request().url());return r.abort()});
 await page.goto(pathToFileURL(file).href,{waitUntil:'networkidle'});
 const body=(await page.locator('body').innerText())+' '+await page.locator('input,textarea,select').evaluateAll(els=>els.filter(el=>el.type!=='hidden'&&el.getClientRects().length).map(el=>el.value).join(' '));
 const expected={'native-acceptance':['FIXTURE-001','150'],'excel-ledger':['INV-001','INV-002'],'excel-ledger-repaired':['INV-001','INV-002'],'report-confirmation':['REPORT-001','REPORT-002'],'report-confirmation-repaired':['REPORT-001','REPORT-002'],'oa-extraction-approval':['REQ-001','TEST-USER'],'monitoring-dashboard':['120','150']}[scenario];
 assert(expected,'Known domain scenario required');for(const value of expected)assert(body.includes(value),`Business fixture ${value} must be visible`);
 const chartData=await page.evaluate(()=>[...document.querySelectorAll('[_echarts_instance_]')].flatMap(el=>window.echarts.getInstanceByDom(el).getOption().series.flatMap(s=>s.data.map(x=>typeof x==='number'?x:x.value))));
 if(['native-acceptance','report-confirmation','report-confirmation-repaired','monitoring-dashboard'].includes(scenario)){assert(chartData.includes(120));assert(chartData.includes(150))}
 if(scenario.startsWith('excel-ledger')||scenario.startsWith('report-confirmation'))assert.equal(await page.locator('tbody tr').count(),2,'Exactly the two authorized fixture rows; no duplicates or dropped rows');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'No horizontal overflow');
 if(scenario.startsWith('excel-ledger')){
  await page.locator('[name=query]').fill('INV-001');await page.locator('form[data-query] button[type=submit]').click();
  assert.match(await page.locator('tbody').textContent(),/INV-001/);assert.doesNotMatch(await page.locator('tbody').textContent(),/INV-002/);
  await page.locator('[name=query]').fill('');await page.locator('form[data-query] button[type=submit]').click();assert.match(await page.locator('tbody').textContent(),/INV-002/);
 }
 if(scenario.startsWith('report-confirmation')||scenario==='oa-extraction-approval'){
  await page.locator('[data-approval] [name=reason]').fill('Fixture confirmation');
  await page.locator('[data-approval] button[type=submit]').click();
  await page.waitForFunction(()=>document.querySelector('[data-approval] output')?.textContent.includes('API'),{},{timeout:5000});
 }
 assert.deepEqual(errors,[],'Offline package must not depend on HTTP or throw JS errors');
 checks.push({viewport,fixtureValues:'passed',chartValues:chartData,offline:'passed'});await context.close();
 }
 await writeFile(join(root,'offline-readback-verification.json'),JSON.stringify({scenario,status:'passed',checks,businessScope:'Synthetic domain fixture mappings and offline rendering; no production data or server-side mutations'},null,2));console.log(scenario+': Silo readback offline rendering and fixture mappings passed');
}finally{await browser.close()}
