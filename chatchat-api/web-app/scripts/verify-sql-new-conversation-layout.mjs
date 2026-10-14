import { chromium, request } from 'playwright-core';
import assert from 'node:assert/strict';
const base=process.env.SQL_STUDIO_DEPLOY_BASE;
const http=await request.newContext();
let browser;
try {
  const response=await http.post(`${base}/api/v1/enterprise/auth/login`,{data:{username:process.env.SQL_STUDIO_DEPLOY_USER,password:process.env.SQL_STUDIO_DEPLOY_PASSWORD}});
  assert.equal(response.status(),200);
  const session=(await response.json()).data;
  browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
  const page=await browser.newPage({viewport:{width:1600,height:1000}});
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(session=>localStorage.setItem('chatchat.auth.session',JSON.stringify(session)),session);
  await page.goto(`${base}/#/w/data-science/sqlStudio`,{waitUntil:'domcontentloaded'});
  const button=page.getByRole('button',{name:'新建会话',exact:true});
  await button.waitFor({timeout:45000});
  await page.waitForFunction(()=>!document.querySelector('.sql-new-conversation').disabled);
  const bounds=await button.locator('svg').boundingBox();
  assert.equal(bounds.width,18);assert.equal(bounds.height,18);
  assert.equal(await button.evaluate(el=>getComputedStyle(el).paddingLeft),'0px');
  await button.click();
  assert.equal(await page.locator('.sql-chat-turn,.sql-chat-result').count(),0);
  await page.reload({waitUntil:'domcontentloaded'});
  await button.waitFor({timeout:45000});
  assert.equal(await page.locator('.sql-chat-turn,.sql-chat-result').count(),0);
  assert.deepEqual(errors,[]);
  await page.screenshot({path:'../../target/new-conversation-final-deploy/production-new-conversation.png',fullPage:true});
  console.log('PASS: production + icon 18 x 18, zero padding, fresh conversation after reload, no browser errors.');
} finally {await browser?.close();await http.dispose();}
