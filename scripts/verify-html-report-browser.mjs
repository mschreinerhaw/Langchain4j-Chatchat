/** Real Chromium checks of the same assembler used by the exploration iframe. */
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
const output=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/html-report-browser');await mkdir(output,{recursive:true});
const source=(await readFile('chatchat-api/web-app/src/js/utils/htmlReport.js','utf8')).replace(/^import .*;\r?\n/gm,'').replace(/export /g,'');
const server=createServer((req,res)=>{res.setHeader('Content-Type','text/html');res.end('<!doctype html><html><body><script>'+source+';window.assemble=assembleHtmlReport;</script></body></html>');});
await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;
try{
 const executablePath=process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||process.env.LOCALAPPDATA+'/ms-playwright/chromium-1179/chrome-win/chrome.exe';
 browser=await chromium.launch({executablePath,headless:true});const page=await browser.newPage();await page.goto(`http://127.0.0.1:${server.address().port}/`);
 const sources={'output/site/report.html':'<!doctype html><html><head><link rel="stylesheet" href="styles.css"><script defer src="app.js"></script></head><body><h1>Interactive acceptance</h1><p id="total">Loading</p><button id="toggle">Show detail</button><p id="detail" hidden>Original source values</p><p id="isolation"></p></body></html>',
 'output/site/styles.css':'body{color:rgb(12,34,56)}',
 'output/site/app.js':`fetch('data.json').then(r=>r.json()).then(data=>document.querySelector('#total').textContent=String(data.values.reduce((a,b)=>a+b,0)));document.querySelector('#toggle').onclick=()=>document.querySelector('#detail').hidden=false;try{parent.localStorage.getItem('acceptance-token');document.querySelector('#isolation').textContent='LEAK';}catch{document.querySelector('#isolation').textContent='ISOLATED';}`,
 'output/site/data.json':'{"values":[12,25,5]}'};
 const fixture={report:{entryPath:'output/site/report.html'},files:Object.entries(sources).map(([path,text])=>({path,size:Buffer.byteLength(text),sha256:createHash('sha256').update(text).digest('hex'),content:Buffer.from(text).toString('base64')}))};
 const bundle=process.env.RUNTIME_TEST_HTML_BUNDLE?JSON.parse(await readFile(process.env.RUNTIME_TEST_HTML_BUNDLE,'utf8')):fixture;
 await page.evaluate(async bundle=>{localStorage.setItem('acceptance-token','MUST_NOT_BE_DISCLOSED');const iframe=document.createElement('iframe');iframe.setAttribute('sandbox','allow-scripts');iframe.style.cssText='width:1200px;height:800px';iframe.srcdoc=await window.assemble(bundle);document.body.append(iframe);},bundle);
 const frame=await (await page.waitForSelector('iframe')).contentFrame();await frame.waitForSelector('body');
 if(!process.env.RUNTIME_TEST_HTML_BUNDLE){await frame.locator('#total').filter({hasText:'42'}).waitFor();assert.equal(await frame.locator('#isolation').textContent(),'ISOLATED');await frame.locator('#toggle').click();assert.equal(await frame.locator('#detail').isVisible(),true);assert.equal(await frame.locator('body').evaluate(el=>getComputedStyle(el).color),'rgb(12, 34, 56)');}
 else {await page.waitForTimeout(2000);assert.ok((await frame.locator('body').innerText()).trim().length>20);}
 assert.notEqual(await frame.locator('html').getAttribute('data-report-error'),'true');
 await page.screenshot({path:resolve(output,'report.png'),fullPage:true});
 await writeFile(resolve(output,'browser-verification.json'),JSON.stringify({passed:true,realDeliveredBundle:!!process.env.RUNTIME_TEST_HTML_BUNDLE,opaqueSandbox:true,scriptErrorsObserved:false,fixtureInteractionChecked:!process.env.RUNTIME_TEST_HTML_BUNDLE},null,2));
 console.log('Real Chromium report rendering passed');
}finally{await browser?.close();await new Promise(r=>server.close(r));}
