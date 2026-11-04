/** Submit through the real application, then reopen its persisted history. No response fixtures. */
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const origin=process.env.REPORT_ORIGIN||'http://192.168.195.221:18100';
const output=process.env.REPORT_OUTPUT||'target/cline-report-browser';
fs.mkdirSync(output,{recursive:true});
const request=JSON.parse(fs.readFileSync(process.env.REPORT_REQUEST_FILE,'utf8'));
const authResponse=await fetch(origin+'/api/v1/enterprise/auth/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username:process.env.REPORT_USER||'admin',password:process.env.REPORT_PASSWORD})});
const auth=(await authResponse.json()).data;assert.ok(auth?.token);
fs.writeFileSync(output+'/auth.private.json',JSON.stringify(auth));
const browser=await chromium.launch({executablePath:process.env.REPORT_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
const errors=[],responses=[];
try{
 const context=await browser.newContext({viewport:{width:1440,height:1100},locale:'zh-CN',timezoneId:'Asia/Shanghai'});
 await context.addInitScript(auth=>localStorage.setItem('chatchat.auth.session',JSON.stringify(auth)),auth);
 const page=await context.newPage();
 const waitForRenderedReport=async()=>page.waitForFunction(()=>{
  const bodies=[...document.querySelectorAll('.chat-message.assistant .enterprise-ui-artifact,.chat-message.assistant .message-markdown,.chat-message.assistant .python-visual-report')];
  return bodies.some(root=>root.textContent.length>100&&!root.querySelector('.artifact-resource-state,.artifact-shell-state'));
 },null,{timeout:60000});
 page.on('pageerror',error=>errors.push(error.name+': '+error.message));
 page.on('response',r=>{const url=new URL(r.url());if(url.pathname.includes('/api/'))responses.push({path:url.pathname,status:r.status()});});
 await page.goto(origin+'/#/w/zhice/chat',{waitUntil:'domcontentloaded',timeout:60000});
 await page.waitForFunction(()=>document.querySelector('#app')?._vnode?.component?.proxy?.$refs?.activeViewComponent?.agents?.length,null,{timeout:60000});
 let submitted;
 if(process.env.REPORT_REOPEN_ONLY==='1'){
  submitted=JSON.parse(fs.readFileSync(output+'/submission.json'));
  await page.evaluate(async id=>{const app=document.querySelector('#app')._vnode.component.proxy;await app.loadConversationHistory();const item=app.conversationHistory?.find(c=>c.id===id||c.conversationId===id);if(!item)throw Error('Persisted conversation missing');await app.selectConversation(item);},submitted.conversationId);
 }else{
 await page.evaluate(request=>{
  const view=document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent;
  view.selectedAgentId=request.skillId;
  window.__reportAcceptance=view.handleSend({query:request.query,reportMode:request.toolInput?.reportMode,resourceExplorationApproved:true});
 },request);
 await page.waitForFunction(()=>document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent.messages.some(m=>m.taskId),null,{timeout:60000});
 submitted=await page.evaluate(()=>{const v=document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent;return{taskId:v.messages.find(m=>m.taskId).taskId,historyId:v.historyId,conversationId:v.conversationId};});
 fs.writeFileSync(output+'/submission.json',JSON.stringify(submitted,null,2));
 console.log(JSON.stringify({event:'REAL_UI_SUBMITTED',...submitted}));
 }
 await page.waitForFunction(()=>{const v=document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent;return !v.loading&&!v.preparingComposer;},null,{timeout:1800000,polling:2000});
 await page.evaluate(()=>window.__reportAcceptance);
 const state=await page.evaluate(()=>{const v=document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent;return{error:v.errorMessage,status:v.conversationStatus,messages:v.messages.map(m=>({role:m.role,status:m.status,taskId:m.taskId,contentCharacters:m.content?.length,uiArtifact:m.uiArtifact,pythonVisualReport:m.pythonVisualReport}))};});
 fs.writeFileSync(output+'/browser-state.json',JSON.stringify(state,null,2));
 if(state.messages.some(m=>m.role==='assistant'&&m.contentCharacters>100))await waitForRenderedReport();
 let chartWaitError=null;
 if(request.toolInput?.reportMode==='visual'&&process.env.REPORT_REQUIRE_CHART==='1')try{await page.waitForFunction(()=>[...document.querySelectorAll('.report-renderer img')].some(i=>i.complete&&i.naturalWidth>0),null,{timeout:60000});}catch(error){chartWaitError=error.name;}
 await page.screenshot({path:output+'/runtime-report-full.png',fullPage:true});
 fs.writeFileSync(output+'/rendered-report.txt',await page.locator('.chat-view').innerText());
 const report=page.locator('.report-renderer').first();
 const reportCount=await report.count();
 if(reportCount)await report.screenshot({path:output+'/runtime-report-artifact.png'});
 const images=await page.locator('.report-renderer img').evaluateAll(images=>images.map(i=>({src:i.getAttribute('src'),loaded:i.complete&&i.naturalWidth>0,width:i.naturalWidth,height:i.naturalHeight})));
 await page.reload({waitUntil:'domcontentloaded'});
 await page.waitForFunction(()=>document.querySelector('#app')?._vnode?.component?.proxy?.$refs?.activeViewComponent,null,{timeout:60000});
 // Select the saved conversation through the application's normal history loader.
 await page.evaluate(async id=>{const app=document.querySelector('#app')._vnode.component.proxy;await app.loadConversationHistory();const item=app.conversationHistory?.find(c=>c.id===id||c.conversationId===id);if(!item)throw Error('Persisted conversation missing');await app.selectConversation(item);},submitted.conversationId);
 await page.waitForFunction(id=>document.querySelector('#app')._vnode.component.proxy.$refs.activeViewComponent.messages?.some(m=>m.taskId===id),submitted.taskId,{timeout:60000});
 if(reportCount)await page.locator('.report-renderer').first().waitFor({timeout:60000});
 if(state.messages.some(m=>m.role==='assistant'&&m.contentCharacters>100))await waitForRenderedReport();
 await page.screenshot({path:output+'/runtime-history-reopened.png',fullPage:true});
 const proof={...submitted,status:state.status,error:state.error,reportCount,images,chartWaitError,historyReopened:true,pageErrors:errors,responses};
 fs.writeFileSync(output+'/browser-proof.json',JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));
 assert.ok(!state.error,'UI reported an error');assert.ok(state.messages.some(m=>m.role==='assistant'&&m.contentCharacters>100),'No final report in actual conversation');
 if(request.toolInput?.reportMode==='visual'&&process.env.REPORT_REQUIRE_CHART==='1'){assert.ok(reportCount,'Report renderer missing');assert.ok(images.some(i=>i.loaded),'Real chart image failed to load');}
}finally{await browser.close();}
