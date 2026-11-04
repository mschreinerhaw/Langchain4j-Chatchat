/** Real deployed history checks. Uses an existing authorized session; never submits a new task. */
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';

const auth=JSON.parse(fs.readFileSync(process.env.REPORT_AUTH_FILE,'utf8'));
const origin=process.env.REPORT_ORIGIN||'http://192.168.195.221:18100';
const output=process.env.REPORT_OUTPUT||'target/conversation-source-disclosure';
const restrictedIds=(process.env.REPORT_RESTRICTED_CONVERSATIONS||'').split(',').filter(Boolean);
const healthyId=process.env.REPORT_HEALTHY_CONVERSATION;
assert.ok(auth.token&&restrictedIds.length&&healthyId,'Supply an authorized session and existing history IDs');
fs.mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.REPORT_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
const errors=[],proof={at:new Date().toISOString(),restricted:[]};
try{
 const context=await browser.newContext({viewport:{width:1440,height:1100},locale:'zh-CN',timezoneId:'Asia/Shanghai'});
 await context.addInitScript(a=>localStorage.setItem('chatchat.auth.session',JSON.stringify(a)),auth);
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.name+': '+e.message));
 await page.goto(origin+'/#/w/zhice/chat',{waitUntil:'domcontentloaded'});
 await page.waitForFunction(()=>document.querySelector('#app')?._vnode?.component?.proxy?.$refs?.activeViewComponent?.agents?.length,null,{timeout:60000});
 await page.evaluate(()=>document.querySelector('#app')._vnode.component.proxy.loadConversationHistory());
 const select=async id=>page.evaluate(async id=>{
  const app=document.querySelector('#app')._vnode.component.proxy;
  const row=app.conversationHistory.find(c=>c.id===id||c.conversationId===id);
  if(!row)throw Error('History missing from loaded page');
  await app.selectConversation(row);
  return{sidebarError:app.historyError,detailError:app.selectedConversation?.detailLoadError,
   restricted:app.selectedConversation?.messages?.filter(m=>m.contentRestricted).length};
 },id);
 for(const id of restrictedIds){
  const result=await select(id);assert.equal(result.sidebarError,'');assert.ok(!result.detailError);
  assert.ok(result.restricted);proof.restricted.push({id,...result});
 }
 await page.waitForFunction(()=>document.querySelector('.chat-view')?.textContent.includes('此条历史回答'));
 const screenshot=async name=>{
  const box=await page.locator('.chat-view').boundingBox(),v=page.viewportSize();
  const x=Math.max(0,box.x),y=Math.max(0,box.y);
  await page.screenshot({path:output+'/'+name,clip:{x,y,width:Math.min(box.width,v.width-x),height:Math.min(box.height,v.height-y)}});
 };
 await screenshot('restricted-history.png');
 proof.healthy=await select(healthyId);assert.equal(proof.healthy.sidebarError,'');
 assert.ok(!proof.healthy.detailError);assert.equal(proof.healthy.restricted,0);
 await page.locator('.report-renderer').first().waitFor({timeout:60000});
 // Actual report images load lazily: scroll before checking their decoded dimensions.
 await page.locator('.python-report-chart').first().scrollIntoViewIfNeeded();
 await page.waitForFunction(()=>[...document.querySelectorAll('.report-renderer img')].some(i=>i.complete&&i.naturalWidth>0),null,{timeout:60000});
 await screenshot('healthy-history-chart.png');
 proof.images=await page.locator('.report-renderer img').evaluateAll(images=>images.map(i=>({loaded:i.complete&&i.naturalWidth>0,width:i.naturalWidth,height:i.naturalHeight})));
 proof.englishErrorVisible=await page.getByText('Document evidence authorization or version is no longer valid',{exact:true}).count();
 assert.equal(proof.englishErrorVisible,0);proof.pageErrors=errors;assert.deepEqual(errors,[]);
 fs.writeFileSync(output+'/browser-proof.json',JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));
}finally{await browser.close();}
