import { chromium, request } from 'playwright-core';
import assert from 'node:assert/strict';
import { writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const artifactUrl = new URL('../../../target/sql-prototype-20261013/', import.meta.url);
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
  const mapping = await api(`${prefix}/files`,{path:'mappings/customer.csv',kind:'MAPPING',content:'source_table,source_column,target_table,target_column,type\ndim_customer,customer_id,dws_customer_profile,customer_id,BIGINT\ndim_customer,customer_name,dws_customer_profile,customer_name,STRING',expectedVersion:0},'PUT');
  const file = await api(`${prefix}/files`,{path:'scripts/customer_asset_profile.sql',kind:'SQL',content:'-- SQL Studio verification sample\n-- Temporary project; no database execution\nSELECT\n  customer_id,\n  customer_name\nFROM dim_customer;\n',expectedVersion:0},'PUT');
  const stale = await client.delete(`/api/v1${prefix}/files/${file.id}?expectedVersion=99`,{headers:{Authorization:`Bearer ${session.token}`}});
  assert.equal(stale.status(),409); check('File version conflict',409);
  const library = await api('/data-science/sql/library-files?kind=MAPPING'); check('Authorized library', {choices:library.length});
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
  await page.getByRole('button',{name:'解释这段 SQL 的作用',exact:true}).click();
  assert.ok((await page.getByLabel('发送给 AI 工程助手').inputValue()).includes('scripts/customer_asset_profile.sql'));
  assert.equal(await page.getByLabel('SQL 方言').inputValue(),'spark');
  assert.ok(await page.getByLabel('SQL 方言').evaluate(el => parseFloat(getComputedStyle(el).fontSize)) >= 12);
  await page.getByLabel('搜索工程文件').fill('customer_asset');
  assert.equal(await page.locator('.sql-tree-file').count(),1);
  await page.getByLabel('搜索工程文件').fill('');
  await page.getByRole('button',{name:'格式化',exact:true}).first().click();
  await page.getByRole('button',{name:'保存文件',exact:true}).click();
  await page.locator('.sql-studio-notice').filter({hasText:'文件已保存'}).waitFor();
  await page.locator('.sql-studio-notice button').click();
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

  const prepared = await api(`${prefix}/generations`,{prompt:'Generate exactly one Spark SQL file scripts/generated_customer.sql that selects customer_id and customer_name from dim_customer according to the supplied Mapping. Return the required sql-studio.output.v1 JSON contract. Do not call tools or execute SQL.',dialect:'spark',mappingIds:[mapping.id],referenceIds:[]});
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
  check('Real model generation and confirmed application',{status:result.task.status,files:applied.map(file=>file.path),eventCount:result.events.length,validation:result.validation});
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
