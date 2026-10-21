// Verify the deployed application and an existing real Python report, without API fixtures.
import {chromium} from 'playwright-core';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const [authFile,base,output,conversationId]=process.argv.slice(2);
if(!authFile||!base||!output||!conversationId)throw new Error('AUTH_FILE BASE_URL OUTPUT CONVERSATION_ID required');
const auth=JSON.parse(await readFile(authFile,'utf8'));
await mkdir(output,{recursive:true});
let executablePath;
for(const candidate of [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','/usr/bin/chromium'].filter(Boolean)){
  try{await access(candidate);executablePath=candidate;break;}catch{}
}
assert.ok(executablePath,'Chromium required');
const browser=await chromium.launch({executablePath,headless:true});
const errors=[],failures=[];let submissions=0,page;
try{
  page=await browser.newPage({viewport:{width:1440,height:1100}});
  page.on('pageerror',error=>errors.push(error.message));
  page.on('response',response=>{if(response.status()>=400)failures.push({path:new URL(response.url()).pathname,status:response.status()});});
  page.on('request',request=>{if(request.method()==='POST'&&/\/(?:agent\/tasks|interactions\/chat)$/.test(new URL(request.url()).pathname))submissions++;});
  await page.addInitScript(value=>{if(window===window.top)localStorage.setItem('chatchat.auth.session',JSON.stringify(value));},auth);
  const headers={Authorization:'Bearer '+auth.token};
  const response=await page.request.get(base+`/api/v1/conversations/user/${auth.user.id}/summaries?limit=100`,{headers});
  assert.equal(response.status(),200);
  const summary=(await response.json()).data.find(item=>item.id===conversationId);
  assert.ok(summary,'Report must exist in authenticated history');
  await page.goto(base+'/#/w/zhice/chat',{waitUntil:'domcontentloaded'});
  const search=page.getByPlaceholder('搜索历史会话',{exact:true});await search.waitFor({timeout:60000});
  await search.fill(summary.title.slice(0,24));
  const created=new Date(summary.createdAt).toISOString();
  const card=page.locator('.recent-card').filter({has:page.locator(`time[datetime="${created}"]`)});
  await card.waitFor({timeout:30000});await card.click();
  const report=page.locator('.python-visual-report').first();await report.waitFor({timeout:60000});
  await report.locator('.python-report-metrics dd').first().waitFor();
  assert.ok((await report.locator('.python-report-metrics').innerText()).includes('100.00'));
  assert.equal(await report.locator('.report-code,.report-provenance').count(),0);
  const body=await report.innerText();
  for(const internal of ['代码 SHA-256','执行任务：','执行器已停止','分析代码与执行状态','结构与文件完整性校验'])assert.ok(!body.includes(internal));
  const message=report.locator('xpath=ancestor::*[contains(concat(" ",normalize-space(@class)," ")," chat-message ")]').first();
  assert.equal(await message.locator('.runtime-execution-panel').count(),0);
  const downloadReady=page.waitForEvent('download',{timeout:60000});
  await message.locator('button[title="导出当前回答为PDF"]').click();
  const download=await downloadReady;await download.saveAs(resolve(output,'live-report.pdf'));
  const pdf=await readFile(resolve(output,'live-report.pdf'));assert.equal(pdf.subarray(0,5).toString(),'%PDF-');assert.ok(pdf.length>20000);
  const pdfjs=await import('pdfjs-dist/legacy/build/pdf.mjs');
  const {createCanvas}=await import('@napi-rs/canvas');
  const pdfDocument=await pdfjs.getDocument({data:new Uint8Array(pdf),standardFontDataUrl:resolve('node_modules/pdfjs-dist/standard_fonts')+'/'}).promise;
  assert.ok(pdfDocument.numPages>=1);
  const firstPage=await pdfDocument.getPage(1),viewport=firstPage.getViewport({scale:1.5});
  const canvas=createCanvas(Math.ceil(viewport.width),Math.ceil(viewport.height));
  await firstPage.render({canvasContext:canvas.getContext('2d'),viewport}).promise;
  await writeFile(resolve(output,'pdf-first-page.png'),canvas.toBuffer('image/png'));
  const images=report.locator('.python-report-chart img');
  for(const image of await images.all()){
    await image.scrollIntoViewIfNeeded();await image.evaluate(element=>element.decode());
  }
  const iframe=report.locator('.python-report-chart iframe').first();await iframe.scrollIntoViewIfNeeded();
  await report.locator('.python-report-chart[aria-busy="true"]').waitFor({state:'detached',timeout:30000});
  assert.ok(await iframe.evaluate(element=>element.dataset.pdfImage?.startsWith('data:image/png;base64,')));
  await report.locator('h2').first().scrollIntoViewIfNeeded();await page.screenshot({path:resolve(output,'desktop-report.png')});
  await page.setViewportSize({width:390,height:900});await report.scrollIntoViewIfNeeded();
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await report.locator('h2').first().scrollIntoViewIfNeeded();await page.screenshot({path:resolve(output,'mobile-report.png')});
  assert.equal(submissions,0);assert.deepEqual(errors,[]);assert.deepEqual(failures,[]);
  const proof={status:'PASSED',realDeployment:true,httpFixtures:false,conversationId,exactTotal:'100.00',
    internalDetailsHidden:true,executionPanelHidden:true,pdfDownloaded:true,pdfBytes:pdf.length,pdfPages:pdfDocument.numPages,pdfIndependentlyRendered:true,staticAndInteractiveCharts:true,
    desktopAndMobile:true,historyTriggeredAnalysisRequests:submissions,pageErrors:errors,httpFailures:failures};
  await writeFile(resolve(output,'live-acceptance.json'),JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));
}catch(error){
  await writeFile(resolve(output,'failure-private.json'),JSON.stringify({error:error.message,errors,failures,url:page?.url(),body:page?await page.locator('body').innerText():''},null,2));
  throw error;
}finally{await browser.close();}
