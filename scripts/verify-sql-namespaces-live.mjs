import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import assert from 'node:assert/strict';
const base='http://192.168.195.221:18100',output=process.env.CHATCHAT_BROWSER_OUTPUT||'target/sql-namespace-audit/browser';mkdirSync(output,{recursive:true});
const project=JSON.parse(readFileSync('target/sql-metadata-audit/project.json','utf8'));
const source='a7dd9e95-18af-40f5-8138-b1cf6e488fbf';
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
try{
 const context=await browser.newContext({viewport:{width:1600,height:1000},locale:'zh-CN'});
 const login=await (await context.request.post(base+'/api/v1/enterprise/auth/login',{data:{username:'admin',password:process.env.CHATCHAT_LOGIN_PASSWORD}})).json();assert.ok(login.data?.token);
 await context.addInitScript(s=>{if(window===window.top)localStorage.setItem('chatchat.auth.session',JSON.stringify(s));},login.data);
 const page=await context.newPage();page.setDefaultTimeout(60000);const responses=[],errors=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('response',async r=>{if(new URL(r.url()).pathname.endsWith('/operations')){try{const b=await r.json();responses.push({request:r.request().postDataJSON(),response:b});}catch{}}});
 await page.goto(base+'/#/w/data-science/sqlStudio',{waitUntil:'domcontentloaded'});
 await page.getByRole('combobox',{name:'SQL 工程',exact:true}).selectOption(project.id);
 const choose=page.getByRole('combobox',{name:'选择数据源',exact:true});await choose.waitFor();await choose.fill('db_query_tdh_dwd');
 const interrupted=process.env.NAMESPACE_INTERRUPT==='1';let delayed=false;
 if(interrupted)await page.route('**/operations',async route=>{
  if(!delayed && route.request().postDataJSON()?.arguments?.operation==='LIST_NAMESPACES'){
   delayed=true;await new Promise(r=>setTimeout(r,1500));
  }
  await route.continue();
 });
 const loading=interrupted?page.waitForRequest(r=>new URL(r.url()).pathname.endsWith('/operations') && r.postDataJSON()?.arguments?.operation==='LIST_NAMESPACES'):null;
 await page.getByRole('option',{name:'db_query_tdh_dwd',exact:true}).click();
 if(interrupted){
  await loading;
  await page.evaluate(()=>window.dispatchEvent(new Event('blur')));await new Promise(r=>setTimeout(r,600));await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
 }
 await page.waitForFunction(()=>document.querySelector('.sql-database-status')?.dataset.ready==='true');
 const namespaces=page.getByRole('combobox',{name:'选择数据库或 Schema',exact:true});await namespaces.click();
 const options=await page.getByRole('listbox',{name:'选择数据库或 Schema',exact:true}).getByRole('option').allTextContents();
 await page.screenshot({path:output+'/namespaces.png',fullPage:true});writeFileSync(output+'/browser.json',JSON.stringify({source,options,responses,errors},null,2));
 console.log('NAMESPACE_OPTIONS',JSON.stringify(options));assert.ok(options.some(x=>x.includes('gdp_dwd')));assert.equal(options.length,14);
 await page.getByRole('option',{name:'ods_a5_jzjy',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('.sql-database-status')?.dataset.ready==='true');
 await page.getByRole('button',{name:'展开或折叠数据源导航',exact:true}).click();
 const table=page.locator('.sql-schema-table-name').first();await table.waitFor();const tableName=(await table.textContent()).trim();await table.click();
 await page.locator('.sql-schema-explorer [role="group"] li button').first().waitFor();
 await page.screenshot({path:output+'/table-fields.png',fullPage:true});
 assert.ok(responses.some(x=>x.request.arguments?.operation==='DESCRIBE' && x.response.data?.metadata?.items?.[0]?.columns?.length>0));
 writeFileSync(output+'/browser.json',JSON.stringify({source,interrupted,options,tableName,responses,errors},null,2));assert.deepEqual(errors,[]);
}finally{await browser.close();}
