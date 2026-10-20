// Read the deployed UI and APIs through the existing SSH loopback tunnel. No HTTP fixtures.
import { chromium } from 'playwright-core';
import {readFile,writeFile,access} from 'node:fs/promises';
import assert from 'node:assert/strict';
const root='../../target/runtime-os-refactor-20261020';
const auth=JSON.parse(await readFile(`${root}/studio-browser-auth-private.json`,'utf8'));
const reports=await Promise.all(['sql-final','python-final'].map(async label=>JSON.parse(await readFile(`${root}/${label}-acceptance.json`,'utf8'))));
let executablePath;
for(const file of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {
  try {await access(file);executablePath=file;break;} catch {}
}
assert.ok(executablePath,'Chromium is required');
const browser=await chromium.launch({executablePath,headless:true});
try {
  const page=await browser.newPage({viewport:{width:1560,height:1000}});
  const errors=[];let submissions=0;const detailReads=new Set();
  page.on('pageerror',error=>errors.push(error.message));
  page.on('request',request=>{
    const path=new URL(request.url()).pathname;
    if(request.method()==='POST' && (/\/agent\/tasks$/.test(path)||/\/assist(?:\/coding)?$/.test(path)))submissions++;
    for(const report of reports)if(path===`/api/v1/conversations/${report.conversationId}`)detailReads.add(report.conversationId);
  });
  await page.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),auth);
  await page.goto('http://127.0.0.1:18281/#/w/zhice/chat',{waitUntil:'domcontentloaded'});
  const checked=[];
  for(const [index,label] of ['SQLStudio:','PythonStudio:'].entries()) {
    const card=page.locator('.recent-card').filter({has:page.locator('.recent-title').filter({hasText:label})}).first();
    await card.waitFor({state:'visible',timeout:60000});await card.click();
    const report=reports[index];
    await page.waitForFunction(path=>document.body.innerText.includes(path),report.artifacts[0].path,{timeout:30000});
    const blocks=await page.locator('pre').allTextContents();
    assert.ok(blocks.some(text=>text.length>40),'Archived code must be rendered');
    checked.push({language:report.language,conversationId:report.conversationId,recentCardVisible:true,archivedCodeRendered:true});
    await page.screenshot({path:`${root}/studio-history-${report.language}.png`,fullPage:true});
  }
  for(const report of reports)assert.ok(detailReads.has(report.conversationId),'History details must come from the real API');
  assert.equal(submissions,0,'Opening history must not submit or regenerate a task');
  assert.deepEqual(errors,[]);
  const result={status:'PASSED',httpFixtures:false,checks:checked,historyTriggeredGenerationRequests:submissions,pageErrors:errors};
  await writeFile(`${root}/studio-history-browser.json`,JSON.stringify(result,null,2));
  console.log(JSON.stringify(result));
} finally {await browser.close();}
