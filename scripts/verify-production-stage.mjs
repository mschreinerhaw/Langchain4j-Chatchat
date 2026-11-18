// Authenticated, read-only deployment checks against public production entries.
import assert from 'node:assert/strict';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';

const origin=process.env.VERIFY_ORIGIN||'http://192.168.195.221:18100';
const mcpOrigin=process.env.VERIFY_MCP_ORIGIN||'http://192.168.195.221:18101';
const output=resolve(process.env.VERIFY_OUTPUT||'../target/production-server-20261118/browser');
assert.ok(process.env.VERIFY_PASSWORD,'Provide the authorized deployment verification password');
await mkdir(output,{recursive:true});
const report={passed:false,origin,mcpOrigin,apis:[],pages:[],pageErrors:[],assets:[]};
let browser;
async function api(base,path,body,token){
 const response=await fetch(base+'/api/v1'+path,{method:body===undefined?'GET':'POST',
  headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
  body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});
 report.apis.push({path,status:response.status});
 assert.equal(response.status,200,path);
 const value=await response.json();assert.equal(value.code,200,path+': '+value.message);return value.data;
}
try{
 const credentials={username:process.env.VERIFY_USER||'admin',password:process.env.VERIFY_PASSWORD};
 const auth=await api(origin,'/enterprise/auth/login',credentials);
 assert.ok(auth.token);await api(origin,'/enterprise/auth/me',undefined,auth.token);
 const recovery=await api(origin,'/mcp/center/recovery/status',undefined,auth.token);
 assert.equal(recovery.state,'HEALTHY','API-side MCP registry must be ready');
 const tools=await api(origin,'/mcp/tools',undefined,auth.token);
 assert.ok(tools.some(t=>t.serviceId==='chatchat-mcp-server'&&t.description&&t.remoteToolName));
 report.toolCount=tools.length;
 const models=await api(origin,'/platform/models',undefined,auth.token);
 report.models=models.map(m=>({name:m.name,type:m.type,published:m.published}));
 const mcpAuth=await api(mcpOrigin,'/admin/auth/login',{...credentials,password:process.env.VERIFY_MCP_PASSWORD||credentials.password});
 const me=await api(mcpOrigin,'/admin/auth/me',undefined,mcpAuth.token);
 assert.equal(me.authenticated,true);
 const publications=await api(mcpOrigin,'/database-query/studio-publications',undefined,mcpAuth.token);
 report.sqlPublicationCount=publications.length;
 if(process.env.VERIFY_ARTIFACTS){
  const artifacts=JSON.parse(await readFile(process.env.VERIFY_ARTIFACTS,'utf8'));
  report.sourceCommit=artifacts.sourceCommit;
  for(const prefix of ['index.html','assets/AgentWorkshopView-','assets/SqlStudioView-']){
   const entry=Object.keys(artifacts.apiStaticFiles).find(n=>n.startsWith('BOOT-INF/classes/static/'+prefix)&&(prefix==='index.html'||n.endsWith('.js')));
   assert.ok(entry,'Compiled frontend entry missing: '+prefix);
   const path=entry.replace('BOOT-INF/classes/static','');const response=await fetch(origin+path);
   assert.equal(response.status,200);const sha256=createHash('sha256').update(Buffer.from(await response.arrayBuffer())).digest('hex');
   assert.equal(sha256,artifacts.apiStaticFiles[entry],'Served frontend differs from the server source build');
   report.assets.push({path,sha256});
  }
 }
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--no-proxy-server']});
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 await context.addInitScript(session=>localStorage.setItem('chatchat.auth.session',JSON.stringify(session)),auth);
 const page=await context.newPage();page.setDefaultTimeout(60000);page.on('pageerror',e=>report.pageErrors.push(e.message));
 for(const [name,path,selector] of [['agents','/index.html#/agents','.agent-workshop-view'],['sql-studio','/#/w/data-science/sqlStudio','.sql-studio']]){
  await page.goto(origin+path,{waitUntil:'domcontentloaded'});await page.locator(selector).waitFor();
  await page.screenshot({path:resolve(output,name+'.png'),fullPage:true});report.pages.push({name,url:page.url()});
 }
 const mcpContext=await browser.newContext({viewport:{width:1440,height:1000}});
 await mcpContext.addInitScript(a=>{sessionStorage.setItem('chatchat.mcp.admin.token',a.token);sessionStorage.setItem('chatchat.mcp.admin.user',a.username);sessionStorage.setItem('chatchat.mcp.admin.expiresAt',String(a.expiresAt));sessionStorage.setItem('chatchat.mcp.admin.lastActivityAt',String(Date.now()));},mcpAuth);
 const mcpPage=await mcpContext.newPage();mcpPage.on('pageerror',e=>report.pageErrors.push(e.message));
 await mcpPage.goto(mcpOrigin+'/admin/index.html',{waitUntil:'domcontentloaded'});await mcpPage.locator('.el-menu').first().waitFor({timeout:60000});
 await mcpPage.screenshot({path:resolve(output,'mcp-admin.png'),fullPage:true});report.pages.push({name:'mcp-admin',url:mcpPage.url()});
 assert.deepEqual(report.pageErrors,[]);report.passed=true;
}catch(error){report.error=String(error);throw error;}
finally{await browser?.close();await writeFile(resolve(output,'verification.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({passed:report.passed,toolCount:report.toolCount,pages:report.pages.length,error:report.error}));}
