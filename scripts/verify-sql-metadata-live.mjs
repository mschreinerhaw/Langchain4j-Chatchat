import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {mkdirSync,writeFileSync,readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const base=process.env.CHATCHAT_STAGE_URL||'http://192.168.195.221:18100';
const output=process.env.CHATCHAT_BROWSER_OUTPUT||'target/sql-metadata-release';
const project=JSON.parse(readFileSync('target/sql-metadata-audit/project.json','utf8'));
const generation=JSON.parse(readFileSync('target/sql-metadata-release/acceptance-prepared.json','utf8')).generationId;
assert.ok(process.env.CHATCHAT_LOGIN_PASSWORD);mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
try{
 const context=await browser.newContext({viewport:{width:1600,height:1000},locale:'zh-CN'});
 const response=await context.request.post(base+'/api/v1/enterprise/auth/login',{data:{username:'admin',password:process.env.CHATCHAT_LOGIN_PASSWORD}});
 const login=await response.json();assert.ok(login.data?.token);
 await context.addInitScript(s=>{if(window===window.top)localStorage.setItem('chatchat.auth.session',JSON.stringify(s));},login.data);
 const page=await context.newPage();page.setDefaultTimeout(60000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/#/w/data-science/sqlStudio',{waitUntil:'networkidle'});
 await page.locator('.sql-studio').waitFor();
 await page.getByRole('combobox',{name:'SQL 工程'}).selectOption(project.id);
 await page.waitForLoadState('networkidle');
 await page.waitForFunction(()=>!document.querySelector('select[aria-label="SQL 工程"]')?.disabled);
 await page.getByRole('button',{name:'历史会话',exact:true}).click();
 const selected=page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/generations/'+generation));
 await page.locator('.sql-assistant-history button').filter({hasText:'如何查询 ld_dataservice_api'}).first().click();
 assert.equal((await (await selected).json()).data.generation.id,generation);
 await page.waitForLoadState('networkidle');
 await page.waitForFunction(()=>document.querySelector('.sql-assistant-scroll')?.textContent.includes('已发现 20 个候选对象'));
 await page.locator('.sql-metadata-operations').waitFor();
 try{await page.waitForFunction(()=>document.querySelector('.sql-metadata-operations')?.textContent.includes('发现相关表与字段'),{},{timeout:10000});}
 catch(error){await page.screenshot({path:output+'/browser-failed.png',fullPage:true});writeFileSync(output+'/browser-failed.txt',await page.locator('body').innerText());throw error;}
 const progress=page.locator('.sql-metadata-operations');
 for(const title of ['发现相关表与字段','推导表关联关系','生成 SQL','验证 SQL 结构'])assert.ok((await progress.innerText()).includes(title),title);
 await progress.scrollIntoViewIfNeeded();
 await progress.screenshot({path:output+'/metadata-stages.png'});
 await page.screenshot({path:output+'/sql-studio-acceptance.png',fullPage:true});
 writeFileSync(output+'/browser.json',JSON.stringify({route:page.url(),progress:await progress.innerText(),pageErrors:errors,sqlExecuted:false},null,2));
 assert.deepEqual(errors,[]);console.log('Four metadata stages and latest task rendered without page errors');
}finally{await browser.close();}
