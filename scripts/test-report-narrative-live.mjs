import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';

const [inputs,patch,output]=process.argv.slice(2).filter(a=>!a.startsWith('--'));
assert(inputs&&patch&&output,'INPUTS PATCH_DIR OUTPUT required');
const preview=process.argv.includes('--preview'),pdf=process.argv.includes('--pdf');
const base=process.env.CHATCHAT_STAGE_URL||'http://192.168.195.221:18100';
const auth=JSON.parse(readFileSync(join(inputs,'private/browser-session-private.json')));
let source=JSON.parse(readFileSync(join(inputs,'private/latest-result-private.json'))).data.payload;
if(typeof source==='string')source=JSON.parse(source);
const stored=JSON.parse(readFileSync(join(inputs,'private/latest-report-private.json')));
const original=stored.data??stored;
const storedManifest=JSON.parse(readFileSync(join(inputs,'private/manifest-private.json')));
const expectedManifest=storedManifest.data??storedManifest;
mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
let page,submissions=0;const errors=[],failures=[],checks={};
try {
 const context=await browser.newContext({viewport:{width:1440,height:1100},locale:'zh-CN'});
 await context.addInitScript(s=>localStorage.setItem('chatchat.auth.session',JSON.stringify(s)),auth);
 if(preview)await context.route('**/assets/*',async route=>{
  const name=new URL(route.request().url()).pathname.split('/').pop(),file=join(patch,'assets',name);
  if(existsSync(file))await route.fulfill({body:readFileSync(file),contentType:name.endsWith('.css')?'text/css':'text/javascript'});
  else await route.continue();
 });
 page=await context.newPage();page.setDefaultTimeout(60000);
 page.on('pageerror',e=>errors.push(e.message));
 page.on('response',r=>{if(r.status()>=400)failures.push({path:new URL(r.url()).pathname,status:r.status()});});
 page.on('request',r=>{if(r.method()==='POST'&&/\/(agent\/tasks|interactions\/chat)$/.test(new URL(r.url()).pathname))submissions++;});
 const headers={Authorization:'Bearer '+auth.token};
 const summaries=await context.request.get(base+`/api/v1/conversations/user/${auth.user.id}/summaries?limit=100`,{headers});assert.equal(summaries.status(),200);
 const summary=(await summaries.json()).data.find(s=>s.id===source.conversationId);assert.ok(summary,'Existing task conversation missing');
 await page.goto(base+'/#/w/zhice/chat');
 const search=page.getByPlaceholder('搜索历史会话',{exact:true});await search.fill(summary.title.slice(0,24));
 const created=new Date(summary.createdAt).toISOString();
 await page.locator('.recent-card').filter({has:page.locator(`time[datetime="${created}"]`)}).first().click();
 const report=page.locator(`.enterprise-ui-artifact[data-artifact-id="${source.uiArtifact.artifactId}"] .python-visual-report`);
 await report.waitFor();
 const narratives=report.locator('.report-narrative');
 const firstHeading=original.sections[0].narrative.match(/^\s*#{1,6}\s+(.+)$/m)?.[1];
 assert.ok(firstHeading);assert.equal(await narratives.first().locator('h1,h2,h3,h4,h5,h6').first().innerText(),firstHeading);
 assert.ok(await narratives.first().locator('strong').count());assert.ok(await narratives.first().locator('ul li').count());
 const text=await narratives.evaluateAll(nodes=>nodes.map(n=>{const c=n.cloneNode(true);c.querySelectorAll('pre,code').forEach(e=>e.remove());return c.textContent;}).join('\n'));
 assert.ok(!/^\s*#{1,6}\s/m.test(text));assert.ok(!/\*\*[^*]+\*\*/.test(text));checks.markdownRenderedAsHtml=true;
 for(let i=0;i<original.metrics.length;i++)assert.ok((await report.locator('.python-report-metrics dd').nth(i).textContent()).includes(String(original.metrics[i].value)));
 checks.originalMetricsPreserved=true;
 const images=report.locator('.python-report-chart img');
 for(let i=0;i<original.charts.length;i++){await report.locator('.python-report-chart').nth(i).scrollIntoViewIfNeeded();await images.nth(i).waitFor();await images.nth(i).evaluate(image=>image.decode());}
 assert.equal(await images.count(),original.charts.length);checks.originalChartCount=original.charts.length;
 assert.equal(await report.getAttribute('data-status'),original.status);checks.originalStatusPreserved=true;
 await narratives.first().scrollIntoViewIfNeeded();await page.screenshot({path:join(output,'desktop-narrative.png')});
 if(pdf){
  const message=report.locator('xpath=ancestor::*[contains(concat(" ",normalize-space(@class)," ")," chat-message ")]').first();
  const download=page.waitForEvent('download',{timeout:120000});await message.locator('button[title="导出当前回答为PDF"]').click();
  const file=await download;await file.saveAs(resolve(output,'report.pdf'));const bytes=readFileSync(join(output,'report.pdf'));assert.equal(bytes.subarray(0,5).toString(),'%PDF-');assert.ok(bytes.length>20000);checks.pdfBytes=bytes.length;
 }
 await page.setViewportSize({width:390,height:1000});await narratives.first().scrollIntoViewIfNeeded();
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await page.screenshot({path:join(output,'mobile-narrative.png')});checks.desktopAndMobile=true;
 const response=await context.request.get(base+'/api/v1'+source.uiArtifact.manifestUrl,{headers});assert.equal(response.status(),200);const manifest=(await response.json()).data;
 assert.equal(manifest.resources['visual-report'].sha256,expectedManifest.resources['visual-report'].sha256);checks.persistedReportUnchanged=true;
 assert.equal(submissions,0);assert.deepEqual(errors,[]);assert.deepEqual(failures,[]);
 const result={status:'PASSED',preview,artifactId:source.uiArtifact.artifactId,checks,analysisSubmissions:submissions,pageErrors:errors.length};
 writeFileSync(join(output,'acceptance.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}catch(e){await page?.screenshot({path:join(output,'failure.png')}).catch(()=>{});writeFileSync(join(output,'failure.json'),JSON.stringify({error:String(e),errors,failures},null,2));throw e;}
finally{await browser.close();}
