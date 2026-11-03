import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';

const base=process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100';
const output=process.env.CHATCHAT_BROWSER_OUTPUT || 'target/question-ideas-verification';
assert.ok(process.env.CHATCHAT_LOGIN_PASSWORD,'Set CHATCHAT_LOGIN_PASSWORD');
mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHATCHAT_BROWSER || 'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});
const errors=[],mutations=[],checks=[];
try{
 const response=await context.request.post(base+'/api/v1/enterprise/auth/login',{data:{username:process.env.CHATCHAT_LOGIN_USERNAME || 'admin',password:process.env.CHATCHAT_LOGIN_PASSWORD}});
 const login=await response.json();assert.ok(login.data?.token,'Login failed');
 await context.addInitScript(s=>{if(window===window.top)localStorage.setItem('chatchat.auth.session',JSON.stringify(s));},login.data);
 const page=await context.newPage();page.setDefaultTimeout(60000);
 page.on('pageerror',e=>errors.push(e.message));
 page.on('request',r=>{if(r.method()==='POST' && /\/(?:chat(?:\/stream)?|agent\/tasks|nodes(?:\/[^/]+\/execute)?)$/.test(new URL(r.url()).pathname))mutations.push(new URL(r.url()).pathname);});
 for(const [name,route,endpoint] of [
  ['zhice','/#/w/zhice/chat','/api/v1/interactions/ideas'],
  ['exploration','/#/w/data-science/dataExploration','/api/v1/data-science/explorations/ideas']
 ]){
  await page.goto(base+route,{waitUntil:'networkidle'});
  if(name==='exploration')await page.locator('.exploration-workspace').waitFor();
  const textarea=page.locator('.prompt-card textarea');
  await textarea.waitFor();
  const draft='请围绕客户资产变动和资金流入给出可核实的问题方向。';
  await textarea.fill(draft);
  const requests=[];
  const listener=r=>{if(new URL(r.url()).pathname===endpoint)requests.push(r);};
  page.on('request',listener);
  const rounds=[];
  for(let i=0;i<2;i++){
   const returned=page.waitForResponse(r=>new URL(r.url()).pathname===endpoint && r.request().method()==='POST');
   const started=Date.now();
   await (i===0?page.getByRole('button',{name:'提问灵感',exact:true}):page.getByRole('button',{name:'换一组建议',exact:true})).click();
   if(i===0)assert.match(await page.locator('.composer-idea-status').innerText(),/已请求模型/);
   const result=await returned;const payload=await result.json();
   assert.equal(result.status(),200);assert.equal(payload.code,200);assert.equal(payload.data.ideas.length,3);
   await page.locator('.composer-idea-option').first().waitFor();
   assert.equal(await page.locator('.composer-idea-option').count(),3);
   assert.equal(await textarea.inputValue(),draft);
   rounds.push({status:result.status(),elapsedMs:Date.now()-started,ideas:payload.data.ideas});
   console.log(name+' round '+(i+1)+' passed in '+rounds.at(-1).elapsedMs+'ms');
  }
  assert.equal(requests.length,2,'Each click must send exactly one backend request');
  await page.screenshot({path:output+'/'+name+'.png',fullPage:true});
  await page.locator('.composer-idea-option').first().click();
  assert.ok((await textarea.inputValue()).includes(draft));
  assert.ok((await textarea.inputValue()).includes(rounds[1].ideas[0].prompt));
  await page.getByRole('button',{name:'提问灵感',exact:true}).waitFor();
  assert.equal(mutations.length,0,'Selecting an idea must not submit analysis');
  page.off('request',listener);
  checks.push({workspace:name,backendRequests:requests.length,rounds,draftPreserved:true,selectedSuggestionEditable:true,noAutomaticAnalysis:true});
 }
 assert.deepEqual(errors,[]);
 writeFileSync(output+'/browser.json',JSON.stringify({status:'PASSED',base,checks,errors,mutations},null,2));
 console.log('Question ideas live verification PASSED');
}finally{await browser.close();}
