/** Real browser/native test. Credentials are environment inputs, never exported. */
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const origin=process.env.NATIVE_MONITOR_ORIGIN??'http://192.168.195.221:18346';
const {chromium}=await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE??'../Langchain4j-Chatchat/chatchat-api/web-app/node_modules/playwright-core/index.mjs')));
const output=resolve(process.env.NATIVE_MONITOR_EVIDENCE??'target/native-monitor/browser');await mkdir(output,{recursive:true});
const evidence={source:'REAL_BROWSER_REAL_NATIVE_MODEL',origin,startedAt:new Date().toISOString(),pageErrors:[],requests:[],screenshots:[]};
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH??'C:/Program Files/Google/Chrome/Application/chrome.exe'});
try{
 const context=await browser.newContext({viewport:{width:1650,height:1120}}),page=await context.newPage();page.on('pageerror',e=>evidence.pageErrors.push(e.message));
 page.on('request',r=>{const u=new URL(r.url());if(r.method()==='POST'&&u.pathname!=='/api/login')evidence.requests.push({path:u.pathname,method:r.method(),timestamp:new Date().toISOString()});});
 await page.goto(origin+'/monitor/');await page.locator('#username').fill(process.env.NATIVE_MONITOR_USERNAME??'admin');await page.locator('#password').fill(process.env.NATIVE_MONITOR_PASSWORD??'');await page.locator('#login-form button[type=submit]').click();await page.locator('#console').waitFor({state:'visible',timeout:30000});
 async function api(path){const r=await context.request.get(origin+'/api'+path);assert.equal(r.status(),200);return r.json();}
 if(process.env.NATIVE_MONITOR_EXISTING_SESSION){await page.goto(origin+'/monitor/#'+encodeURIComponent(process.env.NATIVE_MONITOR_EXISTING_SESSION));}
 else{
  await page.locator('#new').click();await page.locator('#mode').selectOption('native');await page.locator('#create-form button[type=submit]').click();
  await page.waitForURL(/#native/, {timeout:120000});
 }
 const key=decodeURIComponent(new URL(page.url()).hash.slice(1));evidence.sessionKey=key;
 await page.locator('#send').waitFor({state:'visible'});
 if(!process.env.NATIVE_MONITOR_EXISTING_SESSION||(await api('/sessions/'+encodeURIComponent(key))).status==='idle'){
  await page.locator('#send:enabled').waitFor({state:'visible',timeout:120000});
  evidence.originalRequest='检查当前项目代码，找到一个可复现问题，修复后运行测试。先运行现有测试获取真实失败；不要修改测试预期。修复源码后再次运行测试，并报告实际结果。';
  await page.locator('#message').fill(evidence.originalRequest);await page.locator('#send').click();
 }
 const deadline=Date.now()+12*60000;let final,approvals=[];
 while(Date.now()<deadline){
  if(evidence.pageErrors.length)throw Error(evidence.pageErrors.join('; '));final=await api('/sessions/'+encodeURIComponent(key));
  for(const p of final.pendingPermissions??[]){
   if(approvals.includes(p.id))continue;
   // Existing controlled fixture workspace; no unrelated command, network or external read is approved.
   const input=JSON.stringify(p.input??{});if(/curl |wget |ssh |rm -rf|\/etc\/|https?:\/\//.test(input))throw Error('Unexpected external operation in controlled acceptance workspace');
   const buttons=page.locator('#permissions button').filter({hasText:'批准'});if(await buttons.count()){await buttons.first().click();approvals.push(p.id);}
  }
  console.log(JSON.stringify({status:final.status,pendingPermissions:final.pendingPermissions?.length??0,nativeSessionId:final.nativeSessionId}));
  if(['turn_completed','failed','cancelled','recoverable'].includes(final.status))break;
  await page.waitForTimeout(2000);
 }
 evidence.final=final;evidence.approvals=approvals;
 await page.locator('[data-tab=tools]').click();await page.screenshot({path:resolve(output,'native-tools.png'),fullPage:true});evidence.screenshots.push('native-tools.png');
 await page.locator('[data-tab=code]').click();await page.waitForTimeout(1000);await page.screenshot({path:resolve(output,'native-code-diff.png'),fullPage:true});evidence.screenshots.push('native-code-diff.png');
 evidence.diff=await api('/sessions/'+encodeURIComponent(key)+'/code');
 const before=await api('/sessions/'+encodeURIComponent(key));await page.reload();await page.locator('#session-heading').waitFor({state:'visible'});await page.waitForTimeout(4000);const after=await api('/sessions/'+encodeURIComponent(key));assert.equal(after.nativeSessionId,before.nativeSessionId);assert.equal(after.status,before.status);evidence.refreshPreservesIdentity=true;
 await context.setOffline(true);await page.waitForTimeout(2200);await context.setOffline(false);await page.locator('#connection').filter({hasText:'实时连接'}).waitFor({state:'visible',timeout:30000});evidence.reconnectObserved=(await page.locator('#connection').innerText()).includes('实时连接');
 evidence.visibleEvents=await page.locator('.message[data-event-id]').count();assert(evidence.visibleEvents>0);evidence.persistedMessagesVisible=true;
 assert.deepEqual(evidence.pageErrors,[]);assert.equal(final.status,'turn_completed');assert(evidence.diff.patch.includes('calculator.'));evidence.status='PASS_NATIVE_FIRST_TURN';
}catch(e){evidence.status='FAIL';evidence.error=e.message;throw e;}
finally{evidence.finishedAt=new Date().toISOString();await writeFile(resolve(output,'browser-native.json'),JSON.stringify(evidence,null,2));await browser.close();}
