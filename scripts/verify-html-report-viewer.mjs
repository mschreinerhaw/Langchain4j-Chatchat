import {build} from '../chatchat-api/web-app/node_modules/esbuild/lib/main.js';
/** Exercise the production Vue viewer with a real, independently verified delivery bundle. */
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
const root='chatchat-api/web-app',out=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/html-viewer');await mkdir(out,{recursive:true});
const bundle=JSON.parse(await readFile(process.env.RUNTIME_TEST_HTML_BUNDLE,'utf8'));
const utility=(await readFile(root+'/src/js/utils/htmlReport.js','utf8')).replace(/^import .*;\r?\n/gm,'').replace(/export /g,'');
const component=(await readFile(root+'/src/js/components/HtmlReportFrame.js','utf8')).replace(/^import .*;\r?\n/gm,'').replace('export default','window.TestComponent=');
const template=(await readFile(root+'/src/components/HtmlReportFrame.vue','utf8')).split('<template>')[1].split('</template>')[0];
const css=await readFile(root+'/src/styles/components/html-report-frame.css','utf8');
const vue=await readFile(root+'/node_modules/vue/dist/vue.esm-browser.js');
const hasher=(await build({stdin:{contents:"import {sha256} from '@noble/hashes/sha256';window.sha256=sha256;",resolveDir:resolve('chatchat-api/web-app')},bundle:true,write:false,format:'iife',platform:'browser'})).outputFiles[0].text;
const server=createServer((req,res)=>{
 if(req.url==='/vue.js'){res.setHeader('Content-Type','text/javascript');return res.end(vue);}
 if(req.url?.includes('/report-bundle')){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({code:200,data:bundle}));}
 res.setHeader('Content-Type','text/html;charset=utf-8');res.end('<!doctype html><style>'+css+'</style><div id="app"></div><script type="module">import * as Vue from "/vue.js";const {ref,watch,onBeforeUnmount}=Vue;const getStoredAuthSession=()=>null,workspaceHeaders=()=>({});'+hasher+utility+'\n'+component+'\nwindow.TestComponent.template='+JSON.stringify(template)+';Vue.createApp(window.TestComponent,{report:'+JSON.stringify(bundle.report)+'}).mount("#app");</script>');
});await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;
try{
 browser=await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||process.env.LOCALAPPDATA+'/ms-playwright/chromium-1179/chrome-win/chrome.exe',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});await page.addInitScript(()=>Object.defineProperty(globalThis,'crypto',{value:{},configurable:true}));page.on('pageerror',error=>console.error(error.stack));page.on('console',m=>{if(m.type()==='error')console.error(m.text());});await page.goto(`http://127.0.0.1:${server.address().port}/`);
 const frame=await (await page.waitForSelector('iframe')).contentFrame();await frame.waitForSelector('body');await page.waitForTimeout(1000);
 assert.equal(await page.locator('iframe').getAttribute('sandbox'),'allow-scripts');assert.notEqual(await frame.locator('html').getAttribute('data-report-error'),'true');
 await page.screenshot({path:resolve(out,'viewer-initial.png'),fullPage:true});
 await page.getByRole('button',{name:'全屏查看'}).click();assert.equal(await page.evaluate(()=>!!document.fullscreenElement),true);await page.evaluate(()=>document.exitFullscreen());
 const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'下载离线报告'}).click();const download=await downloaded;
 const path=resolve(out,'offline-report.html');await download.saveAs(path);const html=await readFile(path,'utf8');assert.ok(html.includes('window.fetch=')&&html.includes('Content-Security-Policy'));assert.ok(!html.includes('Bearer '));
 const offline=await browser.newPage();await offline.goto('file:///'+path.replaceAll('\\','/'));await offline.waitForTimeout(1000);assert.match(await offline.locator('body').innerText(),/\b42\b/);assert.notEqual(await offline.locator('html').getAttribute('data-report-error'),'true');
 await writeFile(resolve(out,'viewer-verification.json'),JSON.stringify({passed:true,fullscreen:true,offlineDownload:true,offlineRender:true,opaqueSandbox:true},null,2));console.log('Production Vue viewer: rendering, fullscreen and offline download passed');
}finally{await browser?.close();await new Promise(r=>server.close(r));}
