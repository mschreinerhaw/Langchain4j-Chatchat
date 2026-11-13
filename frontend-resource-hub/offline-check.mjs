import {chromium} from 'playwright';
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const evidence=JSON.parse(await readFile(process.argv[2]));
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,chromiumSandbox:process.platform!=='win32'});
try{
 for(const c of evidence.cases){
 const context=await browser.newContext({viewport:{width:390,height:844},serviceWorkers:'block'}),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await context.route(/^https?:/,r=>{errors.push('External dependency '+r.request().url());return r.abort()});
 await page.goto(pathToFileURL(resolve(`jobs/${c.id}/standalone.html`)).href,{waitUntil:'networkidle'});
 assert.equal(await page.locator('h1').textContent(),'Synthetic business acceptance');
 if(await page.locator('table').count()){
 assert.match(await page.locator('tbody').textContent(),/FIXTURE-001/);
 if(await page.locator('[name=query]').count()){
 await page.locator('[name=query]').fill('no-matching-row');await page.locator('[data-query] button').click();assert.equal(await page.locator('tbody tr').count(),0);
 await page.locator('[name=query]').fill('');await page.locator('[data-query] button').click();assert.equal(await page.locator('tbody tr').count(),1);
 }}
 if(await page.locator('[data-approval]').count()){
 await page.locator('[name=reason]').fill('Fixture approval');await page.locator('[data-approval] button').click();assert.match(await page.locator('output').textContent(),/等待业务接口/);
 }
 const charts=await page.evaluate(()=>[...document.querySelectorAll('[data-chart]')].map(el=>echarts.getInstanceByDom(el).getOption().series[0].data));
 for(const data of charts)assert.deepEqual(data.map(x=>typeof x==='number'?x:x.value),[120,150]);
 assert.deepEqual(errors,[]);c.offline={status:'passed',chartMapping:charts.length,interactions:'checked where present'};await context.close();
 }
 await writeFile(process.argv[2],JSON.stringify(evidence,null,2));console.log('Offline dependency, business fixture mapping and interaction checks passed for all five templates');
}finally{await browser.close()}
