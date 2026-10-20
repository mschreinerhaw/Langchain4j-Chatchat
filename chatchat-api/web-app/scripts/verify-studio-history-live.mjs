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
  await page.locator('.recent-card').first().waitFor({state:'visible',timeout:60000});
  const headers=await page.evaluate(async user=>{
    const session=JSON.parse(localStorage.getItem('chatchat.auth.session'));
    const response=await fetch(`/api/v1/conversations/user/${encodeURIComponent(user)}/summaries?limit=100`,
      {headers:{Authorization:`Bearer ${session.token}`}});
    return (await response.json()).data;
  },auth.user.id);
  const checked=[];
  for(const [index,label] of ['SQLStudio:','PythonStudio:'].entries()) {
    const report=reports[index];
    const header=headers.find(item=>item.id===report.conversationId);
    assert.ok(header,'The accepted task must be in the real recent-session index');
    await page.getByPlaceholder('搜索历史会话',{exact:true}).fill(label);
    const created=new Date(header.createdAt).toISOString();
    const card=page.locator('.recent-card').filter({has:page.locator(`time[datetime="${created}"]`)});
    await card.waitFor({state:'visible',timeout:60000});await card.click();
    await page.waitForFunction(path=>document.body.innerText.includes(path),report.artifacts[0].path,{timeout:30000});
    const blocks=await page.locator('pre').allTextContents();
    assert.ok(blocks.some(text=>text.length>40),'Archived code must be rendered');
    for(const [number,artifact] of report.artifacts.entries()) {
      const extension=artifact.path.slice(artifact.path.lastIndexOf('.'));
      const source=await readFile(`${root}/studio-${report.language}-source-${number+1}${extension}`,'utf8');
      if(!blocks.some(text=>text.includes(source))) {
        await writeFile(`${root}/studio-browser-source-diagnostic-private.json`,JSON.stringify({language:report.language,
          artifact:artifact.path,source,blocks,detailReads:[...detailReads],body:await page.locator('body').innerText()},null,2));
        await page.screenshot({path:`${root}/studio-browser-source-diagnostic.png`,fullPage:true});
      }
      assert.ok(blocks.some(text=>text.includes(source)),'Rendered history must retain the complete archived source');
    }
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
