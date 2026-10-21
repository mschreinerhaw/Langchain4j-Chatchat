// Real deployed composer drop + paste uploads, with exact original downloads. No HTTP fixtures.
import {chromium} from 'playwright-core';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import assert from 'node:assert/strict';
import path from 'node:path';
const [authFile,base,evidence]=process.argv.slice(2);
const auth=JSON.parse(await readFile(authFile,'utf8'));await mkdir(evidence,{recursive:true});
let executablePath;
for(const candidate of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']){
  try{await access(candidate);executablePath=candidate;break;}catch{}
}
const browser=await chromium.launch({executablePath,headless:true});
const files=[],errors=[];
try {
  const page=await browser.newPage({viewport:{width:1500,height:1000}});
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),auth);
  await page.goto(base+'/#/chat',{waitUntil:'domcontentloaded'});
  const composer=page.locator('.prompt-card');await composer.locator('textarea').waitFor({timeout:30000});
  for(const name of ['联网搜索','上传文件','上传图片'])assert.equal(await composer.getByRole('button',{name,exact:true}).count(),0);
  const originals=[{name:`drag-${Date.now()}.csv`,type:'text/csv',content:'category,amount\nfirst,17.25\nsecond,2.50\n'},
    {name:`paste-${Date.now()}.md`,type:'text/markdown',content:'# 分析目标\r\n  原始内容与空白保持完整。\r\n'}];
  for(const [index,file] of originals.entries()) {
    const pending=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/v1/interactions/user-assets'&&response.request().method()==='POST');
    await (index===0?composer:composer.locator('textarea')).evaluate((element,{file,index})=>{
      const data=new DataTransfer();data.items.add(new File([file.content],file.name,{type:file.type}));
      const event=index===0?new DragEvent('drop',{dataTransfer:data,bubbles:true,cancelable:true}):new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true});
      element.dispatchEvent(event);if(!event.defaultPrevented)throw new Error('File event not accepted');
    },{file,index});
    const response=await pending;assert.equal(response.status(),200);const asset=(await response.json()).data;files.push(asset);
    await composer.getByRole('listitem').filter({hasText:file.name}).getByText(/已保存到用户空间/).waitFor({timeout:30000});
    const downloaded=await page.request.get(base+`/api/v1/interactions/user-assets/${asset.id}/content`,{headers:{Authorization:`Bearer ${auth.token}`}});
    assert.equal(downloaded.status(),200);assert.deepEqual(await downloaded.body(),Buffer.from(file.content));
  }
  await page.screenshot({path:path.join(evidence,'drag-and-paste-attachments.png'),fullPage:true});
  const items=composer.getByRole('listitem');assert.equal(await items.count(),2);
  await items.first().getByRole('button',{name:'移除附件'}).click();assert.equal(await items.count(),1);
  const retained=await page.request.get(base+`/api/v1/interactions/user-assets/${files[0].id}`,{headers:{Authorization:`Bearer ${auth.token}`}});
  assert.equal(retained.status(),200);assert.deepEqual(errors,[]);
  await writeFile(path.join(evidence,'browser-acceptance.json'),JSON.stringify({status:'PASSED',dropAcrossComposer:true,pasteFiles:true,
    exactOriginals:true,removeOnlyCurrentSelection:true,oldButtonsRemoved:true,fileCount:2,pageErrors:errors},null,2));
  console.log('PASSED: deployed composer drop, paste, exact bytes and per-question selection');
} finally {
  const request=await browser.newContext();
  for(const file of files)await request.request.delete(base+`/api/v1/interactions/user-assets/${file.id}`,{headers:{Authorization:`Bearer ${auth.token}`}});
  await browser.close();
}
