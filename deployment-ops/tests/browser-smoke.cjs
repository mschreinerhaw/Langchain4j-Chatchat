const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
const fs=require('fs');
const path=require('path');
const artifacts=process.env.OPS_TEST_ARTIFACTS || 'test-artifacts';fs.mkdirSync(artifacts,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']});
 const page=await browser.newPage({viewport:{width:1500,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(process.env.OPS_URL || 'http://127.0.0.1:19220',{waitUntil:'networkidle'});
 await page.locator('#login').waitFor({state:'visible'});
 if(await page.locator('h1').innerText()!=='Sign in')throw Error('Incorrect login title');
 if(await page.locator('#notice').innerText())throw Error('Unexpected initial login error');
 await page.screenshot({path:path.join(artifacts,'login-english.png'),fullPage:true});
 await page.locator('[name=username]').fill('admin');await page.locator('[name=password]').fill(process.env.OPS_TEST_PASSWORD);await page.locator('#loginForm button').click();
 await page.locator('#content .cards').waitFor();
 const nav=await page.locator('[data-page]').evaluateAll(bs=>bs.map(b=>({id:b.dataset.page,label:b.textContent})));
 if(nav.length!==10||new Set(nav.map(n=>n.id)).size!==10||new Set(nav.map(n=>n.label)).size!==10)throw Error('Duplicate navigation');
 const report=[];
 for(const item of nav){
  await page.locator(`[data-page="${item.id}"]`).click();
  let ready=false;
  for(let attempt=0;attempt<300;attempt++){
   if(await page.locator('#title').innerText()===item.label && await page.locator('#content h2').count()){ready=true;break;}
   await new Promise(resolve=>setTimeout(resolve,100));
  }
  if(!ready)throw Error('Page did not load: '+item.id);
  const text=await page.locator('body').innerText();
  if(/\?{2,}|\uFFFD/.test(text))throw Error('Corrupted text in '+item.id);
  if(!(await page.locator('#content h2').count()))throw Error('Missing content heading in '+item.id);
  report.push({page:item.label,heading:await page.locator('#content h2').first().innerText(),notice:await page.locator('#notice').innerText()});
 }
 await page.locator('[data-page=overview]').click();await page.locator('.cards').waitFor();
 await page.screenshot({path:path.join(artifacts,'overview-english.png'),fullPage:true});
 await page.locator('#logout').click();await page.locator('#login').waitFor({state:'visible'});
 if(errors.length)throw Error(errors.join('\n'));
 fs.writeFileSync(path.join(artifacts,'browser-report.json'),JSON.stringify({passed:true,pages:report,consoleErrors:errors},null,2));console.log(JSON.stringify(report,null,2));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
