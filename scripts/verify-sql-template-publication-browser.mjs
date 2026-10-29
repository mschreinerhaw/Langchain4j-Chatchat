import {createRequire} from 'node:module';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
const require=createRequire(resolve('chatchat-api/web-app/package.json'));
const {chromium}=require('playwright-core');
const required=name=>{if(!process.env[name])throw new Error(`Set ${name}`);return process.env[name];};
const session=JSON.parse(readFileSync(required('SQL_PUBLICATION_SESSION_FILE'),'utf8'));
const auth=session.data||session,base=required('SQL_PUBLICATION_BASE_URL');
const project=required('SQL_PUBLICATION_PROJECT_ID'),revision=required('SQL_PUBLICATION_REVISION_ID');
const output=resolve(required('SQL_PUBLICATION_OUTPUT_DIR'));mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
try {
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 await context.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),auth);
 const page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 async function open(){
  await page.goto(base+'/#/w/data-science/sqlStudio');
  await page.getByLabel('SQL 工程',{exact:true}).selectOption(project);
  await page.getByRole('button',{name:process.env.SQL_PUBLICATION_FILE_NAME||'publication.sql',exact:false}).first().click();
  await page.getByRole('button',{name:'发布为 MCP 模板',exact:true}).click();
  const panel=page.getByRole('dialog',{name:'发布 MCP SQL 模板'});
  await panel.locator('select').first().selectOption(revision);
  await panel.getByText('SQL 与发布历史',{exact:true}).waitFor();return panel;
 }
 let panel=await open();
 await page.screenshot({path:resolve(output,'publication-overview.png'),fullPage:true});
 await panel.locator('details').first().locator('summary').click();
 const initial=await panel.innerText();
 if(!initial.includes('SQL_DATABASE_EXECUTION')||!initial.includes('PUBLISHED'))throw new Error('Missing persisted SQL test / publication state');
 await page.screenshot({path:resolve(output,'published-sql-history.png'),fullPage:true});
 await page.reload();panel=await open();
 const restored=await panel.innerText();
 if(!restored.includes('SQL_DATABASE_EXECUTION'))throw new Error('History did not survive reload');
 await page.setViewportSize({width:1024,height:800});
 await page.screenshot({path:resolve(output,'publication-reloaded.png'),fullPage:true});
 const result={status:errors.length?'FAILED':'PASSED',realBackend:true,project,revision,checks:{persistedPublication:initial.includes('PUBLISHED'),realTestEvidence:initial.includes('SQL_DATABASE_EXECUTION'),historyRecovered:restored.includes('SQL_DATABASE_EXECUTION')},pageErrors:errors};
 writeFileSync(resolve(output,'browser-verification.json'),JSON.stringify(result,null,2));
 if(errors.length)throw new Error(errors.join('\n'));console.log(JSON.stringify(result));
} finally {await browser.close();}
