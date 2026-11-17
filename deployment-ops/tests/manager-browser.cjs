// QA credentials are supplied on stdin and are never written to test artifacts.
const fs=require('fs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
const {strict:assert}=require('assert');
(async()=>{
 const secret=JSON.parse(fs.readFileSync(0,'utf8'));
 const base=process.env.OPS_QA_URL||'http://127.0.0.1:19231';
 const dir=process.env.OPS_TEST_ARTIFACTS;fs.mkdirSync(dir,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']});
 try{
  const context=await browser.newContext({viewport:{width:1600,height:1050}});const page=await context.newPage();const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto(base);await page.locator('#loginForm [name=username]').fill('admin');await page.locator('#loginForm [name=password]').fill(secret.password);await page.locator('#loginForm button').click();
  await page.locator('#content .stat-grid').waitFor({timeout:30000});await page.screenshot({path:dir+'/overview.png',fullPage:true});
  for(const view of ['containers','images','nodes','storage','applications','operations','events','audit','settings']){
   await page.locator(`[data-view=${view}]`).click();await page.waitForTimeout(1000);assert(await page.locator('#content').innerText(),view+' empty');assert(!await page.locator('#notice').isVisible()||!/(error|failed|404|Exception)/i.test(await page.locator('#notice').innerText()),view+' failed');
  }
  await page.locator('[data-view=containers]').click();await page.locator('[data-action=create]').click();await page.locator('#dialog [name=image] option').first().waitFor({state:'attached'});
  const name='ops-qa-browser-'+Date.now();
  await page.locator('#dialog [name=name]').fill(name);await page.locator('#dialog [name=image]').selectOption(secret.image);await page.locator('#dialog [name=runtimeUser]').fill('999:999');await page.locator('#dialog [name=command]').fill(JSON.stringify(['sh','-c','echo browser-qa-ready; sleep 600']));
  await page.locator('#dialog [type=submit]').click();
  try{await page.locator('#dialog [name=confirmation]').waitFor({timeout:10000});}catch(error){await page.screenshot({path:dir+'/browser-error.png',fullPage:true});console.error('Preview error:',await page.locator('#dialogContent').innerText());throw error;}
  await page.screenshot({path:dir+'/approval.png',fullPage:true});await page.locator('#dialog [name=confirmation]').fill(name);await page.locator('#dialog [type=submit]').click();await page.locator('#dialog').waitFor({state:'hidden'});
  async function containerRow(){await page.locator('[data-view=containers]').click();await page.locator('#refresh').click();const row=page.locator('tbody tr').filter({hasText:name});await row.waitFor({timeout:30000});return row;}
  let row=await containerRow();
  async function confirm(action){const row=await containerRow();await row.locator(`[data-action="${action}"]`).click();await page.locator('#dialog [name=confirmation]').fill(name);const response=page.waitForResponse(r=>r.url().endsWith('/manager/execute')&&r.request().method()==='POST');await page.locator('#dialog [type=submit]').click();const job=await (await response).json();await page.locator('#dialog').waitFor({state:'hidden'});for(let i=0;i<120;i++){const status=await (await page.request.get(base+'/api/nodes/1/manager/jobs/'+job.id)).json();if(status.status==='SUCCESS')return;if(!['PENDING','RUNNING'].includes(status.status))throw Error(JSON.stringify(status));await page.waitForTimeout(500);}throw Error('Browser operation timeout');}
  await confirm('container.start');row=await containerRow();await row.locator('[data-action=logs]').click();await page.locator('#logOutput').filter({hasText:'browser-qa-ready'}).waitFor({timeout:15000});await page.screenshot({path:dir+'/live-logs.png',fullPage:true});await page.locator('#closeDialog').click();
  await confirm('container.stop');await confirm('container.delete');
  await page.locator('[data-view=nodes]').click();await page.screenshot({path:dir+'/nodes.png',fullPage:true});
  await page.setViewportSize({width:1280,height:800});await page.locator('[data-view=overview]').click();await page.screenshot({path:dir+'/laptop.png',fullPage:true});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'horizontal overflow');
  assert.deepEqual(errors,[]);fs.writeFileSync(dir+'/browser-result.json',JSON.stringify({passed:['login','all resource pages','container create via approval','start','live SSE logs','stop','delete','1280px layout'],pageErrors:errors,qaContainer:name},null,2));
  console.log('PASS browser lifecycle and SSE; no JavaScript errors');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
