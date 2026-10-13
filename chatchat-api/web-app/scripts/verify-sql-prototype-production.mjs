import { chromium, request } from 'playwright-core';
import assert from 'node:assert/strict';
import { writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const artifactUrl = new URL('../../../target/sql-folders-feedback-20261013/', import.meta.url);
await mkdir(artifactUrl, { recursive: true });
const base = process.env.SQL_STUDIO_DEPLOY_BASE;
assert.ok(base && process.env.SQL_STUDIO_DEPLOY_USER && process.env.SQL_STUDIO_DEPLOY_PASSWORD, 'Supply deployment URL and credentials through environment variables');
const client = await request.newContext({ baseURL: base });
const report = { checks: [], browserErrors: [] };
let browser, project, taskId, generationId, session;
const check = (name, detail) => { report.checks.push({name,detail}); console.log(`PASS ${name}: ${JSON.stringify(detail)}`); };
async function api(path, data, method = data ? 'POST' : 'GET') {
  const response = await client.fetch(`/api/v1${path}`, { method, data, headers: session ? {Authorization:`Bearer ${session.token}`,'X-Workspace-Id':'data-science'} : {} });
  const payload = await response.json();
  assert.equal(response.status(), 200, `${path}: ${payload.message}`);
  assert.equal(payload.code, 200, `${path}: ${payload.message}`);
  return payload.data;
}
try {
  session = await api('/enterprise/auth/login', {username:process.env.SQL_STUDIO_DEPLOY_USER,password:process.env.SQL_STUDIO_DEPLOY_PASSWORD});
  check('API health', (await api('/health')).status);
  project = await api('/data-science/sql/projects', {name:`__prototype_verification_${Date.now()}`,dialect:'spark'});
  report.projectId = project.id;
  const prefix = `/data-science/sql/projects/${project.id}`;
  assert.deepEqual(await api(`${prefix}/folders`),[]);
  await api(`${prefix}/folders`,{path:'scripts'});
  assert.deepEqual(await api(`${prefix}/files`),[]);
  assert.deepEqual(await api(`${prefix}/folders`),['scripts']);
  check('Persistent empty folder before file creation','scripts');
  const file = await api(`${prefix}/files`,{path:'scripts/customer_asset_profile.sql',kind:'SQL',content:'-- SQL Studio verification sample\n-- Temporary project; no database execution\nSELECT\n  customer_id,\n  customer_name\nFROM dim_customer;\n',expectedVersion:0},'PUT');
  const stale = await client.delete(`/api/v1${prefix}/files/${file.id}?expectedVersion=99`,{headers:{Authorization:`Bearer ${session.token}`}});
  assert.equal(stale.status(),409); check('File version conflict',409);
  const library = await api('/data-science/sql/library-files?kind=MAPPING'); check('Authorized library', {choices:library.length});
  if (library.length) {
    const context=await api(`${prefix}/generations`,{prompt:'Prepare SQL context only',dialect:'spark',mappingDocumentIds:[library[0].docId]});
    assert.ok(context.taskRequest.query.includes(library[0].fileName));
    assert.equal((await api(`${prefix}/files`)).length,1);
    check('Library Mapping is task context only',{editorFiles:1});
  }
  browser = await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
  const page = await browser.newPage({viewport:{width:1665,height:928}});
  page.on('pageerror', error => report.browserErrors.push(error.message));
  await page.addInitScript(({session,projectId}) => {
    localStorage.setItem('chatchat.auth.session',JSON.stringify(session));
    const key = `sql-studio-selection:${[session.user.tenantId,session.user.id,'data-science'].map(encodeURIComponent).join(':')}`;
    localStorage.setItem(key,JSON.stringify({version:1,value:{projectId}}));
  },{session,projectId:project.id});
  await page.goto(`${base}/#/w/data-science/sqlStudio`,{waitUntil:'domcontentloaded'});
  await page.locator('.sql-current-context').filter({hasText:'customer_asset_profile.sql'}).waitFor({timeout:60000});
  await page.getByRole('button',{name:'新建文件夹',exact:true}).click();
  await page.getByLabel('文件夹路径').fill('daily');
  await page.getByRole('button',{name:'创建文件夹',exact:true}).click();
  await page.locator('summary[data-folder="daily"]').waitFor();
  await page.reload({waitUntil:'domcontentloaded'});
  await page.locator('summary[data-folder="daily"]').waitFor({timeout:60000});
  assert.equal((await api(`${prefix}/files`)).length,1);
  await page.locator('summary[data-folder="daily"]').click({button:'right'});
  await page.getByRole('menuitem',{name:'新建 SQL 文件…'}).click();
  assert.equal(await page.getByLabel('目标文件夹').inputValue(),'daily');
  await page.getByLabel('SQL 文件名').fill('draft.sql');
  await page.getByRole('button',{name:'创建并打开'}).click();
  await page.locator('.sql-tree-file button[title="daily/draft.sql"]').waitFor();
  const indent=await page.getByLabel('编辑器缩进空格').boundingBox();
  assert.ok(indent.width<=44 && indent.height<=24);
  await page.locator('.sql-tree-file button[title="daily/draft.sql"]').click({button:'right'});
  await page.getByRole('menuitem',{name:'删除文件…'}).click();
  await page.getByRole('dialog',{name:'删除文件',exact:true}).waitFor();
  await page.getByRole('button',{name:'取消',exact:true}).click();
  assert.equal((await api(`${prefix}/files`)).length,2);
  await page.locator('.sql-tree-file button[title="daily/draft.sql"]').click({button:'right'});
  await page.getByRole('menuitem',{name:'删除文件…'}).click();
  await page.getByRole('button',{name:'确认删除'}).click();
  await page.locator('.sql-tree-file button[title="daily/draft.sql"]').waitFor({state:'detached'});
  assert.equal((await api(`${prefix}/files`)).length,1);
  await page.locator('.sql-tree-file button[title="scripts/customer_asset_profile.sql"]').click();
  check('Folder-first UI, compact indent, styled confirmation',{indent,emptyFolderSurvivesReload:true});
  await page.getByRole('button',{name:'解释这段 SQL 的作用',exact:true}).click();
  assert.ok((await page.getByLabel('发送给 AI 工程助手').inputValue()).includes('scripts/customer_asset_profile.sql'));
  assert.equal(await page.getByLabel('SQL 方言').inputValue(),'spark');
  assert.ok(await page.getByLabel('SQL 方言').evaluate(el => parseFloat(getComputedStyle(el).fontSize)) >= 12);
  await page.getByLabel('搜索工程文件').fill('customer_asset');
  assert.equal(await page.locator('.sql-tree-file').count(),1);
  await page.getByLabel('搜索工程文件').fill('');
  await page.getByRole('button',{name:'格式化',exact:true}).first().click();
  await page.getByRole('button',{name:'保存文件',exact:true}).click();
  await page.locator('.sql-page-notice.app-notice-success').filter({hasText:'文件已保存'}).waitFor();
  await page.locator('.sql-page-notice button').click();
  await page.screenshot({path:fileURLToPath(new URL('deployed-sql-studio.png',artifactUrl)),fullPage:true});
  for (const width of [1280,1024]) {
    await page.setViewportSize({width,height:900});
    const bounds = await page.locator('.sql-ide-main').boundingBox();
    assert.ok(bounds.width >= 200, `Editor width at ${width}: ${bounds.width}`);
    check(`Desktop layout ${width}`,{editorWidth:Math.round(bounds.width)});
  }
  await page.setViewportSize({width:1665,height:928});
  await page.goto(`${base}/#/w/data-science/dataScienceDevelop`,{waitUntil:'domcontentloaded'});
  await page.locator('.ds-develop-pane').waitFor({timeout:30000});
  await page.goto(`${base}/#/w/zhice/chat`,{waitUntil:'domcontentloaded'});
  await page.locator('.chat-view').waitFor({timeout:30000});
  assert.deepEqual(report.browserErrors,[]); check('SQL / Python / Zhice browser', 'no page errors');

  const login=await browser.newPage();
  await login.goto(`${base}/#/login`,{waitUntil:'domcontentloaded'});
  await login.locator('.login-submit').waitFor();
  for (const viewport of [{width:1920,height:1080},{width:1366,height:768},{width:1280,height:720},{width:1024,height:768},{width:390,height:844}]) {
    await login.setViewportSize(viewport);
    await login.locator('.login-submit').scrollIntoViewIfNeeded();
    const box=await login.locator('.login-submit').boundingBox();
    assert.ok(box.y>=0 && box.y+box.height<=viewport.height && box.x>=0 && box.x+box.width<=viewport.width);
    const sizes=await login.locator('.login-view').evaluate(el=>({width:el.clientWidth,scroll:el.scrollWidth,height:el.clientHeight}));
    assert.ok(sizes.scroll<=sizes.width+1);assert.equal(sizes.height,viewport.height);
    if(viewport.width>1080) {
      const card=await login.locator('.login-card').boundingBox();
      assert.ok(card.y>=0 && card.y+card.height<=viewport.height);
    }
    check('Login viewport',viewport);
  }
  await login.setViewportSize({width:1366,height:768});
  await login.screenshot({path:fileURLToPath(new URL('deployed-login-1366.png',artifactUrl))});
  await login.close();

  const prepared = await api(`${prefix}/generations`,{prompt:'Generate exactly one Spark SQL file scripts/generated_customer.sql that selects customer_id and customer_name from dim_customer. Return the required sql-studio.output.v1 JSON contract. Do not call tools or execute SQL.',dialect:'spark',mappingIds:[],mappingDocumentIds:[],referenceIds:[]});
  assert.equal(prepared.taskRequest.mode,'llm_chat');
  assert.ok(prepared.taskRequest.systemPrompt.includes('sql-studio.output.v1'));
  generationId = prepared.generationId;
  const task = await api('/agent/tasks',prepared.taskRequest); taskId = task.taskId; report.taskId=taskId;
  await api(`${prefix}/generations/${generationId}/task`,{taskId},'PUT');
  check('Runtime OS submission', {taskId, status:task.status});
  const terminal = new Set(['SUCCESS','PARTIAL','PARTIAL_SUCCESS','FAILED','CANCELLED','KILLED','REJECTED','EMPTY','NO_PRESENTABLE_RESULT','TIME_BUDGET_EXHAUSTED','MODEL_BUDGET_EXHAUSTED','TIMEOUT_CANCELLED','DLQ']);
  const deadline = Date.now()+240000;
  let result;
  while (Date.now()<deadline) {
    result = await api(`${prefix}/generations/${generationId}`);
    if (terminal.has(result.task?.status)) break;
    await new Promise(resolve=>setTimeout(resolve,2000));
  }
  await writeFile(new URL('runtime-result.json',artifactUrl),JSON.stringify(result,null,2));
  assert.equal(result.task?.status,'SUCCESS',result.task?.errorMessage || 'Runtime task did not succeed');
  assert.equal(result.outputError,''); assert.ok(result.files.length>0);
  assert.equal(result.events.filter(event => ['TOOL_CALL','TOOL_RESULT'].includes(event.type)).length,0,'Model-only generation must not execute tools');
  const applied = await api(`${prefix}/generations/${generationId}/apply`,{paths:result.files.map(file=>file.path)});
  assert.equal(applied.length,result.files.length);
  check('Real model generation without Mapping or examples',{status:result.task.status,files:applied.map(file=>file.path),eventCount:result.events.length,validation:result.validation});
} finally {
  await browser?.close();
  if (taskId && project) {
    try {
      const view = await api(`/data-science/sql/projects/${project.id}/generations/${generationId}`);
      if (!['SUCCESS','PARTIAL','PARTIAL_SUCCESS','FAILED','CANCELLED','KILLED','REJECTED','EMPTY','NO_PRESENTABLE_RESULT','TIME_BUDGET_EXHAUSTED','MODEL_BUDGET_EXHAUSTED','TIMEOUT_CANCELLED','DLQ'].includes(view.task?.status))
        await api(`/agent/tasks/${taskId}/cancel?tenantId=${encodeURIComponent(session.user.tenantId)}`,{},'POST');
    } catch(error) { report.cleanupError=error.message; }
  }
  if (project) {
    try {await api(`/data-science/sql/projects/${project.id}`,undefined,'DELETE');report.cleanedUp=true;} catch(error) {report.cleanupError=error.message;}
  }
  await writeFile(new URL('verification.json',artifactUrl),JSON.stringify(report,null,2));
  await client.dispose();
}
assert.equal(report.cleanedUp, true, report.cleanupError || 'Temporary verification project was not cleaned up');
