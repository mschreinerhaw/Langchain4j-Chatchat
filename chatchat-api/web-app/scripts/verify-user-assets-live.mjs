// Real deployed Vue, authenticated APIs and object transport. No HTTP fixtures or model submissions.
import {chromium} from 'playwright-core';
import {readFile,writeFile,mkdir,access,rm} from 'node:fs/promises';
import assert from 'node:assert/strict';
import path from 'node:path';
const [authFile,baseUrl,evidenceDirectory]=process.argv.slice(2);
if(!authFile||!baseUrl||!evidenceDirectory)throw new Error('AUTH_FILE BASE_URL EVIDENCE_DIRECTORY required');
const auth=JSON.parse(await readFile(authFile,'utf8'));await mkdir(evidenceDirectory,{recursive:true});
await rm(path.join(evidenceDirectory,'browser-acceptance.json'),{force:true});
await rm(path.join(evidenceDirectory,'browser-error-private.json'),{force:true});
let executablePath;
for(const candidate of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']){
  try{await access(candidate);executablePath=candidate;break;}catch{}
}
assert.ok(executablePath,'Chromium required');
const browser=await chromium.launch({executablePath,headless:true});const errors=[],failures=[];
try{
  const page=await browser.newPage({viewport:{width:1560,height:1050}});
  page.on('pageerror',error=>errors.push(error.message));
  page.on('response',response=>{if(response.url().includes('/user-assets')&&response.status()>=400)failures.push({status:response.status(),path:new URL(response.url()).pathname});});
  await page.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),auth);
  await page.goto(baseUrl+'/#/w/data-science/dataScienceEnvironment',{waitUntil:'domcontentloaded'});
  const library=page.locator('.user-asset-library');
  try{await library.locator('summary').waitFor({state:'visible',timeout:15000});}
  catch(error){await writeFile(path.join(evidenceDirectory,'browser-error-private.json'),JSON.stringify({errors,failures,url:page.url(),body:await page.locator('body').innerText()},null,2));throw error;}
  await library.locator('summary').click();
  await library.getByText(/已用/).waitFor({state:'visible',timeout:30000});
  await library.locator('select').selectOption('qwen3.7-plus');
  assert.equal(await library.locator('select').inputValue(),'qwen3.7-plus');
  const name='browser-assets-'+Date.now()+'.csv',bytes=Buffer.from('category,amount\nexample,1.25\n');
  await library.locator('input[type=file]').setInputFiles({name,mimeType:'text/csv',buffer:bytes});
  const row=library.locator('li').filter({hasText:name});await row.waitFor({state:'visible',timeout:30000});
  const [download]=await Promise.all([page.waitForEvent('download'),row.getByRole('button',{name:'下载',exact:true}).click()]);
  const saved=path.join(evidenceDirectory,'downloaded.csv');await download.saveAs(saved);
  assert.deepEqual(await readFile(saved),bytes,'The real object round trip must preserve uploaded bytes');
  await row.locator('input[type=checkbox]').check();await library.locator('textarea').fill('按 category 汇总 amount，保留小数精度。');
  assert.equal(await library.getByRole('button',{name:'分析选中文件',exact:true}).isEnabled(),true);
  await page.screenshot({path:path.join(evidenceDirectory,'user-assets-live.png'),fullPage:true});
  await row.getByRole('button',{name:'删除',exact:true}).click();await row.waitFor({state:'detached',timeout:30000});
  assert.deepEqual(errors,[]);assert.deepEqual(failures,[]);
  const report={status:'PASSED',realDeployment:true,upload:true,downloadBytesMatch:true,selection:true,delete:true,
    browserErrors:errors,assetApiFailures:failures,modelSelection:true,modelSubmitted:false};
  await writeFile(path.join(evidenceDirectory,'browser-acceptance.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}finally{await browser.close();}
