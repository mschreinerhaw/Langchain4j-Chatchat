import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import assert from 'node:assert/strict';
const state=JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE,'utf8'));
const output=process.env.CHATCHAT_BROWSER_OUTPUT;mkdirSync(output,{recursive:true});
const base=process.env.CHATCHAT_STAGE_URL||'http://192.168.195.221:18100';
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
const checks={},errors=[],contexts=[];let uploaded;
async function open(session){
 const context=await browser.newContext({viewport:{width:1680,height:1050},locale:'zh-CN'});contexts.push(context);
 await context.addInitScript(session=>localStorage.setItem('chatchat.auth.session',JSON.stringify(session)),session);
 const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
 page.on('request',request=>{if(request.url().includes('/enterprise/agent-options'))errors.push('SQL requested the administrative Agent catalogue');});
 await page.goto(base+'/#/w/data-science/sqlStudio');const panel=page.locator('.sql-ide-assistant');
 await panel.getByRole('textbox',{name:'发送给 AI 工程助手'}).waitFor({timeout:45000});
 await page.locator('.sql-tree-scroll').getByText('reference.sql',{exact:true}).waitFor();
 return {page,panel,context};
}
try{
 const {page,panel,context}=await open(state.withAgent);
 assert.equal(await page.locator('.sql-resource-sidebar .sql-user-documents').count(),0);
 assert.equal(await panel.locator('.sql-ai-modes,.sql-composer-tools,.sql-chat-mention-list,.sql-assistant-suggestions').count(),0);
 checks.scriptExplorerAndSimpleComposer=true;
 const settings=panel.locator('.sql-chat-settings');assert.equal(await settings.getAttribute('open'),null);
 await settings.locator('summary').first().click();
 await settings.getByRole('button',{name:'Agent',exact:true}).click();
 const select=settings.locator('.sql-agent-choice select').first();
 assert.deepEqual(await select.locator('option').evaluateAll(options=>options.map(option=>option.value)),['',state.publishedAgentId]);
 await select.selectOption(state.publishedAgentId);checks.grantedPublishedAgentOnly=true;
 await settings.locator('.sql-user-documents>summary').click();
 const documentName='sql-settings-'+Date.now()+'.sql';
 const response=page.waitForResponse(r=>r.url().endsWith('/api/v1/data-science/user-assets')&&r.request().method()==='POST');
 await settings.locator('input[type=file]').setInputFiles({name:documentName,mimeType:'text/plain',buffer:Buffer.from('-- Reference convention\nSELECT 1 AS required_alias;\n')});
 uploaded=(await (await response).json()).data;
 const checked=settings.getByRole('checkbox',{name:'引用文档 '+documentName,exact:true});await checked.waitFor();assert.ok(await checked.isChecked());
 await settings.getByLabel('文档用途 '+documentName,{exact:true}).selectOption('EVIDENCE');
 checks.documentsAndReferenceScriptsInSettings=true;
 await settings.getByRole('button',{name:'@sql/reference.sql',exact:true}).click();
 assert.match(await panel.getByRole('textbox').inputValue(),/@sql\/reference.sql/);checks.projectReferenceInSettings=true;
 await page.screenshot({path:output+'/documents-in-settings.png',fullPage:true});
 await settings.locator('.sql-user-documents>summary').click();
 await page.screenshot({path:output+'/settings-with-agent.png',fullPage:true});
 await settings.locator('summary').first().click();
 assert.equal(await panel.locator('.sql-user-documents').isVisible(),false);
 await page.screenshot({path:output+'/simple-conversation.png',fullPage:true});checks.settingsCollapsedByDefault=true;
 const other=await open(state.withoutAgent);await other.panel.locator('.sql-chat-settings>summary').click();
 assert.equal(await other.panel.locator('.sql-execution-modes').count(),0);
 assert.ok((await other.panel.locator('.sql-agent-choice').first().innerText()).startsWith('模型'));
 checks.noAgentPermissionHidesAgentOption=true;
 await other.panel.getByRole('textbox').fill('解释当前 SQL 的作用');
 assert.ok(await other.panel.getByRole('button',{name:'发送',exact:true}).isEnabled());checks.modelConversationStillAvailable=true;
 await other.page.screenshot({path:output+'/settings-without-agent.png',fullPage:true});
 assert.deepEqual(errors,[]);checks.noPageErrors=true;
 console.log(JSON.stringify({checks,errors,modelCallsSubmitted:0}));
}finally{
 if(uploaded){const response=await contexts[0].request.delete(base+'/api/v1/data-science/user-assets/'+uploaded.id,{headers:{Authorization:'Bearer '+state.withAgent.token,'X-Workspace-Id':'data-science'}});assert.equal(response.status(),200);}
 writeFileSync(output+'/browser-acceptance.json',JSON.stringify({checks,errors,temporaryUploadsRemoved:true,modelCallsSubmitted:0,scriptsExecuted:0},null,2));
 await browser.close();
}
