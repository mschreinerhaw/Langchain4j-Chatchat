/** Read-only history, refresh, offline/reconnect and UNKNOWN-state browser checks. */
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const origin=process.env.NATIVE_MONITOR_ORIGIN??'http://192.168.195.221:18346',output=resolve(process.env.NATIVE_MONITOR_EVIDENCE??'target/native-monitor/history');await mkdir(output,{recursive:true});
const {chromium}=await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE??'../Langchain4j-Chatchat/chatchat-api/web-app/node_modules/playwright-core/index.mjs')));
const evidence={scope:'REAL_READ_ONLY_BROWSER_HISTORY',cases:[],postControls:[],errors:[]};const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH??'C:/Program Files/Google/Chrome/Application/chrome.exe'});
try{
 const context=await browser.newContext({viewport:{width:1650,height:1120}}),page=await context.newPage();page.on('pageerror',e=>evidence.errors.push(e.message));page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname!=='/api/login')evidence.postControls.push(new URL(r.url()).pathname);});
 await page.goto(origin+'/monitor/');await page.locator('#username').fill(process.env.NATIVE_MONITOR_USERNAME??'admin');await page.locator('#password').fill(process.env.NATIVE_MONITOR_PASSWORD??'');await page.locator('#login-form button').click();await page.locator('#console').waitFor({state:'visible'});
 async function api(path){const r=await context.request.get(origin+'/api'+path);assert.equal(r.status(),200);return r.json();}
 const tasks=(await api('/sessions')).items;
 const cases=[tasks.find(t=>t.id==='native:0486017b-9798-4d81-bf18-fb07accd40c5'),tasks.find(t=>t.taskId?.startsWith('analysis-coding-30e2')),tasks.find(t=>t.id==='runtime:438c3d10-f99e-4ec4-a23b-e8495d594fb7')];
 for(const [index,item] of cases.entries()){
  assert(item,'Real historical session missing');await page.goto(origin+'/monitor/#'+encodeURIComponent(item.id),{waitUntil:'domcontentloaded'});await page.locator('#connection').filter({hasText:'实时连接'}).waitFor({state:'visible',timeout:60000});const before=await api('/sessions/'+encodeURIComponent(item.id));
  await page.locator('[data-tab=diagnostics]').click();await page.screenshot({path:resolve(output,'history-'+index+'.png'),fullPage:true});
  await page.reload();await page.locator('#connection').filter({hasText:'实时连接'}).waitFor({state:'visible',timeout:60000});const after=await api('/sessions/'+encodeURIComponent(item.id));assert.equal(after.status,before.status);assert.equal(after.runtimeStatus,before.runtimeStatus);
  if(index===0){assert.equal(after.status,'recoverable');assert.equal(await page.locator('#send').isEnabled(),false);}
  if(index===1)assert.equal(after.status,'UNKNOWN');
  const ids=await page.locator('.message[data-event-id]').evaluateAll(nodes=>nodes.map(n=>n.dataset.eventId));assert.equal(new Set(ids).size,ids.length);
  if(index===2){await context.setOffline(true);await page.waitForTimeout(2200);await context.setOffline(false);await page.locator('#connection').filter({hasText:'实时连接'}).waitFor({state:'visible',timeout:60000});}
  evidence.cases.push({id:item.id,taskId:before.taskId,nativeStatus:before.status,runtimeStatus:before.runtimeStatus,authority:before.authority,refreshPreservedStatus:true,uniqueVisibleMessageIds:true,visibleMessages:ids.length});
 }
 assert.deepEqual(evidence.postControls,[]);assert.deepEqual(evidence.errors,[]);evidence.status='PASS';
}catch(e){evidence.status='FAIL';evidence.error=e.message;throw e;}
finally{await writeFile(resolve(output,'browser-history.json'),JSON.stringify(evidence,null,2));await browser.close();}
