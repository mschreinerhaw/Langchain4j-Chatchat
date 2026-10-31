import {chromium} from 'playwright-core';
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
assert.ok(process.env.TPCDS_AUTH_FILE&&process.env.TPCDS_ORIGIN,'Set TPCDS_AUTH_FILE and TPCDS_ORIGIN');
const auth=JSON.parse(fs.readFileSync(process.env.TPCDS_AUTH_FILE)),origin=process.env.TPCDS_ORIGIN;
const out=path.resolve(process.env.PROJECT_SCREENSHOTS||'../../target/project-management-live');fs.mkdirSync(out,{recursive:true});
async function api(route,method='GET',body){const response=await fetch(origin+'/api/v1/data-science/explorations'+route,{method,headers:{Authorization:'Bearer '+auth.token,'X-Workspace-Id':'data-exploration','Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});const value=await response.json();assert.equal(response.status,200);assert.equal(value.code,200);return value.data;}
const title='项目管理真实验收 '+Date.now(),renamed=title+' 已修改';let fixture=await api('','POST',{title});
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try{
 const context=await browser.newContext({viewport:{width:1440,height:1000}});await context.addInitScript(a=>localStorage.setItem('chatchat.auth.session',JSON.stringify(a)),auth);
 const page=await context.newPage();await page.goto(origin+'/index.html#/w/data-exploration/dataExploration?exploration='+fixture.id);
 await page.getByRole('button',{name:'切换分析项目',exact:true}).click();
 let row=page.locator('.exploration-project-row').filter({hasText:title});await row.getByRole('button',{name:'管理分析项目：'+title,exact:true}).click();
 await row.getByRole('button',{name:'修改名称',exact:true}).waitFor();await page.screenshot({path:path.join(out,'project-actions.png')});
 await row.getByRole('button',{name:'修改名称',exact:true}).click();
 let dialog=page.getByRole('dialog',{name:'修改分析项目名称',exact:true});await dialog.waitFor();await dialog.getByLabel('分析项目名称',{exact:true}).fill(renamed);
 await page.screenshot({path:path.join(out,'project-rename.png')});await dialog.getByRole('button',{name:'保存修改',exact:true}).click();await dialog.waitFor({state:'detached'});
 fixture=await api('/'+fixture.id);assert.equal(fixture.title,renamed);assert.equal(fixture.nodes.length,0);assert.equal(fixture.version,1);
 await page.getByRole('button',{name:'切换分析项目',exact:true}).filter({hasText:renamed}).waitFor();
 row=page.locator('.exploration-project-row').filter({hasText:renamed});await row.getByRole('button',{name:'管理分析项目：'+renamed,exact:true}).click();await row.getByRole('button',{name:'删除项目',exact:true}).click();
 dialog=page.getByRole('dialog',{name:'删除分析项目',exact:true});await dialog.waitFor();await page.screenshot({path:path.join(out,'project-delete-confirmation.png')});
 await dialog.getByRole('button',{name:'取消',exact:true}).click();await dialog.waitFor({state:'detached'});assert.equal((await api('/'+fixture.id)).title,renamed);
 await row.getByRole('button',{name:'管理分析项目：'+renamed,exact:true}).click();await row.getByRole('button',{name:'删除项目',exact:true}).click();
 dialog=page.getByRole('dialog',{name:'删除分析项目',exact:true});await dialog.getByRole('button',{name:'确认删除',exact:true}).click();await dialog.waitFor({state:'detached'});await row.waitFor({state:'detached'});
 assert.ok(!(await api('')).some(s=>s.id===fixture.id));const deletedId=fixture.id;fixture=null;
 await page.reload();await page.getByRole('button',{name:'切换分析项目',exact:true}).click();assert.equal(await page.locator('.exploration-project-row').filter({hasText:renamed}).count(),0);
 fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'PASSED',origin,fixtureId:deletedId,renamedTitle:renamed,checks:['per-item actions','rename persisted','current project label updated','shared confirmation CSS','cancel retains project','confirmed deletion persisted','reload excludes deleted project'],capturedAt:new Date().toISOString()},null,2));
 console.log('Real browser/API checks passed: actions, rename, cancel, confirmed delete, reload.');
}finally{await browser.close();if(fixture){const latest=await api('/'+fixture.id);await api('/'+fixture.id,'DELETE',{version:latest.version});}}
