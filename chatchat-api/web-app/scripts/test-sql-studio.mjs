import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import { readFile, mkdir, access } from 'node:fs/promises';
import assert from 'node:assert/strict';

// Real Vue/Monaco in Chromium; HTTP fixtures only, no model or production access.
const manifests = {};
for (const id of ['zhice', 'data-science']) manifests[id] = JSON.parse(await readFile(`../src/main/resources/workspace/${id}.json`, 'utf8'));
let executablePath;
for (const candidate of ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {
  try { await access(candidate); executablePath = candidate; break; } catch {}
}
assert.ok(executablePath, 'Local Chromium required');
const server = await createServer({ server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1560, height: 980 } });
  const errors = [];
  page.on('pageerror', error => { errors.push(error.message); console.error(error.stack); });
  page.on('dialog', dialog => dialog.accept());
  const user = { id: 'sql-browser-user', username: 'admin', tenantId: 'sql-browser-tenant', permissionCodes: [] };
  await page.addInitScript(value => {
    Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable:true, value:undefined });
    localStorage.setItem('chatchat.auth.session', JSON.stringify({ token: 'fixture-token', user: value }));
    const key = `sql-studio-ide-layout:${value.tenantId}:${value.id}:data-science`;
    if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify({version:1,value:{explorerWidth:260,assistantWidth:380,bottom:false}}));
  }, user);
  let projects = [{ id: 'project', name: 'Spark customer ETL', dialect: 'spark', sessionId: 'session', tenantId: user.tenantId, ownerId: user.id }];
  const filesByProject = { project: [{ id: 'a', projectId: 'project', path: 'sql/a.sql', kind: 'SQL', content: 'SELECT 1;', version: 1, editable: true }] };
  const foldersByProject = {project:['sql']};
  const library = [{ docId: 'mapping-1', fileName: 'customer.csv', title: 'Customer Mapping', version: 1, latestVersion: true }];
  let taskStatus = 'WAIT_MODEL', submissionCount = 0, failNextPoll = false;
  const generation = { id: 'generation-1', taskId: 'task-1', applied: false, prepared:{taskRequest:{toolInput:{sqlStudioAction:'generate'}}} };
  const metadataTable={database:'analytics',schema:'public',tableName:'orders',tableRef:'orders-ref'};
  let metadataDenied=false,descriptions=0;
  const metadata=items=>({serviceId:'registered',metadata:{contractVersion:'sql-studio.metadata.v2',status:'SUCCEEDED',snapshotId:'schema-v1',doesNotExecuteSql:true,items,totalMatched:items.length,hasMore:false}});
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method();
    let data = [];
    if (path.endsWith('/auth/me')) data = user;
    else if (path.endsWith('/workspaces') || path.endsWith('/workspaces/catalog')) data = Object.values(manifests).map(m => ({ ...m, defaultWorkspace: m.workspaceId === 'zhice' }));
    else if (path.endsWith('/workspaces/preferences')) data = { lastWorkspaceId: 'data-science' };
    else if (path.endsWith('/manifest')) { const manifest = manifests[path.split('/').at(-2)]; data = { manifest, menus: [{ id: 'work', title: 'Workspace', type: 'directory', children: manifest.pages.map(item => ({ id: item.menuCode, title: item.id, type: 'menu', children: [] })) }] }; }
    else if (path === '/api/v1/data-science/sql/projects') {
      if (method === 'POST') { const body = request.postDataJSON(); data = { id: 'new-project', name: body.name, dialect: body.dialect, sessionId: 'new-session', tenantId: user.tenantId, ownerId: user.id }; projects.unshift(data); filesByProject[data.id] = []; foldersByProject[data.id]=[]; }
      else data = projects;
    }
    else if(path.endsWith('/metadata/providers'))data=[{serviceId:'registered',name:'Registered MCP'}];
    else if(path.endsWith('/metadata/assets'))data=metadata([{assetRef:'source',name:'Authorized source',sourceDialect:'trino'}]);
    else if(path.endsWith('/metadata/contexts'))data={discoveryId:'discovery',bindings:[{...request.postDataJSON().bindings[0],bindingId:'binding',sourceDialect:'trino'}]};
    else if(path.endsWith('/metadata/contexts/discovery/operations')){
      if(metadataDenied){await route.fulfill({status:403,json:{code:403,message:'Metadata permission revoked'}});return;}
      const args=request.postDataJSON().arguments;
      if(args.operation==='DESCRIBE')descriptions++;
      data=metadata(args.operation==='LIST_NAMESPACES'?[{database:'analytics',schema:'public'}]:args.operation==='DESCRIBE'?[{...metadataTable,columns:[{name:'order_id',dataType:'bigint'}]}]:[metadataTable]);
    }
    else if (path.endsWith('/sql/reference-files')) data = Object.values(filesByProject).flat().filter(file => ['SQL', 'REFERENCE'].includes(file.kind));
    else if (path.endsWith('/sql/library-files')) data = library.filter(item => url.searchParams.get('kind') === 'MAPPING' || item.fileName.endsWith('.sql'));
    else if (/\/sql\/projects\/[^/]+$/.test(path) && method === 'DELETE') { const id = path.split('/').at(-1); projects = projects.filter(item => item.id !== id); delete filesByProject[id]; data = true; }
    else if (/\/sql\/projects\/[^/]+\/folders$/.test(path)) {
      const id=path.split('/').at(-2);
      if(method==='POST'){data=request.postDataJSON().path;foldersByProject[id].push(data);}else data=foldersByProject[id];
    }
    else if (/\/sql\/projects\/[^/]+\/files$/.test(path)) {
      const id = path.split('/').at(-2), files = filesByProject[id];
      if (method === 'PUT') {
        const body = request.postDataJSON(); const current = files.find(file => file.path === body.path);
        if (current) { assert.equal(body.expectedVersion, current.version); Object.assign(current, { content: body.content, version: current.version + 1 }); data = current; }
        else { assert.equal(body.expectedVersion, 0); data = { id: `file-${files.length}`, projectId: id, path: body.path, kind: body.kind, content: body.path==='sql/new.sql' ? 'SELECT broken;' : body.content, version: 1, editable: true }; files.push(data); }
      } else data = files;
    }
    else if (/\/sql\/projects\/[^/]+\/library-files$/.test(path) && method === 'POST') {
      const id = path.split('/').at(-2), { kind, documentIds } = request.postDataJSON();
      assert.deepEqual(documentIds, ['mapping-1']);
      data = [{ id: 'mapped', projectId: id, path: 'mapping/customer.csv', kind, content: 'source,target\na,b', version: 1, editable: true }];
      filesByProject[id].push(...data);
    }
    else if (/\/sql\/projects\/[^/]+\/files\/[^/]+$/.test(path) && method === 'DELETE') {
      const id = path.split('/').at(-3), fileId = path.split('/').at(-1), files = filesByProject[id];
      const file = files.find(item => item.id === fileId); assert.equal(Number(url.searchParams.get('expectedVersion')), file.version);
      filesByProject[id] = files.filter(item => item.id !== fileId); data = true;
    }
    else if (path.endsWith('/project/generations') && method === 'POST') {
      await new Promise(resolve => setTimeout(resolve, 450));
      const body = request.postDataJSON();
      assert.deepEqual(body.mappingIds, []);
      assert.deepEqual(body.mappingDocumentIds, ['mapping-1']);
      assert.ok(body.prompt.includes('sql/new.sql'));
      generation.applied=false;generation.prepared.taskRequest.toolInput.sqlStudioAction=body.action;
      generation.baseVersions=Object.fromEntries(filesByProject.project.map(file=>[file.path.toLowerCase(),file.version]));
      data = { generationId: generation.id, taskRequest: { query: body.prompt, sessionId: 'session' } };
    }
    else if (path === '/api/v1/agent/tasks' && method === 'POST') { assert.equal(request.postDataJSON().historyWindow, 0); await new Promise(resolve => setTimeout(resolve, 350)); submissionCount++; data = { taskId: 'task-1', status: taskStatus }; }
    else if (path.endsWith('/task-1/cancel')) { taskStatus = 'CANCELLED'; data = true; }
    else if (path.endsWith('/generations/generation-1/task')) data = generation;
    else if (path.endsWith('/generations/generation-1')) {
      if (failNextPoll) { failNextPoll = false; await route.fulfill({ status: 503, json: {code:503,message:'Temporary interruption'} }); return; }
      data = { generation, task: { taskId: 'task-1', status: taskStatus, createTime: new Date(Date.now() - 3000).toISOString() },
        events: [{ eventId: 'model-event', sequence: 1, type: 'RUNTIME_OBSERVATION', payload: JSON.stringify({payload:{metadata:{eventKind:'MODEL_INFERENCE',eventState:'STARTED'}}}) }],
        files: taskStatus==='SUCCESS' && generation.prepared.taskRequest.toolInput.sqlStudioAction!=='explain' ? [{path:'sql/new.sql',content:'SELECT 42 AS repaired;'}, {path:'sql/added.sql',content:'SELECT 7 AS added;'}] : [],
        rawAnswer:taskStatus==='SUCCESS' ? JSON.stringify({notes:'解释完成，SELECT 返回常量。'}) : '', outputError: '' };
    }
    else if(path.endsWith('/generations/generation-1/apply')) {
      assert.deepEqual(request.postDataJSON().paths,['sql/new.sql','sql/added.sql']);generation.applied=true;
      data=[{path:'sql/new.sql',content:'SELECT 42 AS repaired;'}, {path:'sql/added.sql',content:'SELECT 7 AS added;'}].map(candidate=>{
        let file=filesByProject.project.find(item=>item.path===candidate.path);
        if(file){file.content=candidate.content;file.version++;}else {file={...candidate,id:'added',projectId:'project',kind:'SQL',version:1,editable:true};filesByProject.project.push(file);}
        return file;
      });
    }
    else if (path.endsWith('/project/generations')) data = submissionCount ? [generation] : [];
    else if (path.endsWith('/new-project/generations')) data = [];
    await route.fulfill({ json: { code: 200, message: 'Success', data } });
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/w/data-science/sqlStudio`, { waitUntil: 'domcontentloaded' });
  await page.locator('.sql-ide-shell').waitFor({ timeout: 90000 });
  assert.ok((await page.getByRole('button',{name:'新建会话',exact:true}).locator('svg').boundingBox()).width >= 18,'New conversation icon is not squeezed by global button padding');
  assert.equal(await page.locator('.sql-ide-shell').evaluate(el => el.style.getPropertyValue('--sql-assistant-width')), '380px', 'Saved panel widths are present on initial render');
  assert.equal(await page.locator('.sql-ide-titlebar .sql-activity-bar').count(), 1, 'panel buttons belong in top bar');
  await page.setViewportSize({ width: 1024, height: 900 });
  const saveBounds = await page.getByRole('button', { name: '保存文件', exact: true }).boundingBox();
  assert.ok(saveBounds.x + saveBounds.width <= 1024, 'Save remains visible on smaller desktop screens');
  await page.setViewportSize({ width: 1560, height: 980 });
  await page.locator('.sql-tree-file button[title="sql/a.sql"]').click();
  await page.getByRole('button',{name:'数据库',exact:true}).click();
  await page.locator('.sql-schema-explorer select').nth(1).selectOption('source');
  await page.getByLabel('展开 orders',{exact:true}).waitFor();
  assert.equal(descriptions,0,'Table listing never requests field descriptions');
  await page.getByLabel('展开 orders',{exact:true}).click();
  await page.locator('.sql-schema-explorer [role=group]').filter({hasText:'order_id'}).waitFor();
  await page.getByRole('button',{name:'用于编辑器',exact:true}).click();
  await page.evaluate(async()=>{
    const source=await (await fetch('/src/js/components/sql-studio/SqlEditorPane.js')).text();
    const modulePath=/import \* as monaco from "([^"]+)"/.exec(source)[1];
    const monaco=await import(modulePath);
    const model=monaco.editor.getModels().find(m=>m.uri.toString().includes('/sql-studio/') && m.uri.toString().endsWith('a'));
    if(!model)throw new Error('SQL Monaco Model missing: '+monaco.editor.getModels().map(m=>m.uri.toString()).join(','));
    model.setValue('SELECT o. FROM analytics.public.orders o');
    const editor=monaco.editor.getEditors().find(e=>e.getModel()===model);editor.setPosition({lineNumber:1,column:10});editor.focus();editor.trigger('audit','editor.action.triggerSuggest',{});
  });
  await page.locator('.suggest-widget.visible').filter({hasText:'order_id'}).waitFor({timeout:30000});
  await page.keyboard.press('Escape');
  await page.getByRole('button',{name:'保存文件',exact:true}).click();
  assert.equal(filesByProject.project[0].content,'SELECT o. FROM analytics.public.orders o');
  await mkdir('../../target/sql-studio-ide-audit',{recursive:true});
  await page.screenshot({path:'../../target/sql-studio-ide-audit/sql-schema-explorer.png',fullPage:true});
  metadataDenied=true;await page.locator('.sql-schema-explorer header button').click();
  await page.locator('.sql-schema-explorer [role=alert]').waitFor();
  assert.equal(await page.locator('.sql-schema-explorer [role=treeitem]').count(),0,'Revocation removes cached visible fields');
  metadataDenied=false;
  await page.getByRole('button',{name:'工程文件',exact:true}).click();
  await page.locator('.sql-tree-scroll').click({ button: 'right', position: { x: 180, y: 120 } });
  await page.getByRole('menuitem', { name: '新建 SQL 文件…' }).click();
  await page.getByLabel('SQL 文件名').fill('new.sql');
  assert.equal(await page.getByLabel('目标文件夹').inputValue(),'sql');
  await page.getByRole('button', { name: '创建并打开' }).click();
  await page.locator('.sql-tree-file button[title="sql/new.sql"]').waitFor();
  await page.locator('.app-notice-success').filter({hasText:'文件已创建'}).waitFor();
  const indent=await page.getByLabel('编辑器缩进空格').boundingBox();
  assert.ok(indent.width<=44 && indent.height<=24,`Compact indent selector ${JSON.stringify(indent)}`);
  await page.getByLabel('搜索工程文件').fill('new.sql');
  assert.equal(await page.locator('.sql-tree-file button[title="sql/a.sql"]').count(), 0);
  await page.getByLabel('搜索工程文件').fill('missing');
  await page.getByText('没有匹配的工程文件。').waitFor();
  await page.getByLabel('搜索工程文件').fill('');
  await page.locator('.sql-current-context').filter({ hasText: 'new.sql' }).waitFor();
  await page.getByRole('button', { name: '解释这段 SQL 的作用', exact: true }).click();
  assert.equal(await page.getByLabel('发送给 AI 工程助手').inputValue(), '@sql/new.sql 解释这段 SQL 的作用');
  assert.equal(await page.getByRole('button', { name: '智能生成', exact: true }).count(), 0);
  assert.equal(await page.getByRole('button', { name: '对话', exact: true }).count(), 0);
  await page.getByRole('button', { name: '基于 Mapping 生成目标表建表与 ETL SQL', exact: true }).waitFor();
  await page.getByLabel('发送给 AI 工程助手').press('Enter');
  assert.equal(submissionCount, 0, 'Enter must insert a newline without submitting a task');
  assert.equal(await page.locator('.sql-chat-composer .sql-primary').isDisabled(), false, 'Mapping is optional');
  assert.equal(await page.getByRole('button', { name: '从文档库选 SQL 案例' }).count(), 0);
  assert.equal(await page.locator('.sql-project-tree > header button').count(),0);
  assert.equal(await page.getByRole('button', { name: '选择 Mapping（可选）' }).count(),0);
  await page.getByRole('button', { name: '关联 Mapping 文档', exact:true }).click();
  await page.locator('.sql-library-list input').check();
  await page.getByRole('button', { name: '确认上下文' }).click();
  await page.locator('.sql-selection-chip').filter({ hasText:'customer.csv' }).waitFor();
  assert.equal(await page.locator('.sql-tree-file button[title="mapping/customer.csv"]').count(), 0);
  assert.equal(filesByProject.project.length, 2, 'Document selection must not import editor files');
  await page.locator('.sql-chat-settings summary').click();
  await page.getByRole('button', { name: '模型', exact: true }).click();
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: 'Agent / Skills' }).count(), 0);
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: '模型' }).count(), 1);
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  await page.getByRole('button',{name:'代码生成',exact:true}).click();
  await page.getByRole('button',{name:'发送',exact:true}).hover();
  assert.equal(await page.getByRole('button',{name:'发送',exact:true}).evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(29, 78, 216)','SQL send retains blue on hover');
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: 'Agent / Skills' }).count(), 1);
  await page.getByLabel('发送给 AI 工程助手').press('Control+Enter');
  await page.locator('.sql-assistant-progress').filter({ hasText: '正在制定代码协作计划' }).waitFor();
  await page.locator('.sql-task-status').filter({ hasText: '模型分析中' }).waitFor();
  assert.equal(await page.getByRole('button', { name: '查看任务与输出', exact: true }).count(), 0);
  const progressBounds = await page.locator('.sql-assistant-progress').boundingBox();
  const panelBounds = await page.locator('.sql-assistant-panel').boundingBox();
  assert.ok(progressBounds.y >= panelBounds.y && progressBounds.y + progressBounds.height < panelBounds.y + panelBounds.height, 'Progress stays inside the visible assistant');
  failNextPoll = true;
  await page.getByText('进度更新暂时中断，正在重试；任务可能仍在运行。').waitFor();
  await page.getByText('进度更新暂时中断，正在重试；任务可能仍在运行。').waitFor({ state: 'detached' });
  taskStatus = 'WAIT_TOOL';
  await page.locator('.sql-task-status').filter({ hasText: '正在执行工具' }).waitFor();
  const stable = await page.evaluate(async () => {
    const values = [];
    for (let i = 0; i < 90; i++) { await new Promise(requestAnimationFrame);
      values.push([...document.querySelectorAll('.sql-studio,.sql-ide-shell,.sql-editor-pane,.sql-monaco-host')].map(el => {
        const r = el.getBoundingClientRect(); return [r.x, r.y, r.width, r.height];
      }));
    }
    return new Set(values.map(JSON.stringify)).size;
  });
  assert.equal(stable, 1, 'Studio and Monaco keep identical bounds across polling and elapsed-time updates');
  await mkdir('../../target/sql-studio-ide-audit', { recursive: true });
  await page.screenshot({ path: '../../target/sql-studio-ide-audit/sql-assistant-running.png', fullPage: true });
  assert.equal(submissionCount, 1);
  assert.equal(await page.locator('.sql-chat-composer .sql-primary').isDisabled(), true, 'Running Runtime task blocks duplicate submission');
  assert.equal(await page.getByRole('button', {name:'新建会话',exact:true}).isDisabled(), true);
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.locator('.sql-task-status').filter({ hasText: '任务已取消' }).waitFor();
  await page.getByLabel('发送给 AI 工程助手').fill('继续修改');
  assert.equal(await page.locator('.sql-chat-composer .sql-primary').isDisabled(), false, 'Runtime terminal state unlocks submission');
  await page.getByRole('button', {name:'新建会话',exact:true}).click();
  taskStatus='SUCCESS';
  await page.getByRole('button',{name:'代码修复',exact:true}).click();
  await page.getByLabel('发送给 AI 工程助手').fill('@sql/new.sql 修复当前文件');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await page.getByRole('button',{name:'审查 2 个文件变更',exact:true}).waitFor();
  const beforeReview=filesByProject.project.find(file=>file.path==='sql/new.sql').content;
  await page.getByRole('button',{name:'审查 2 个文件变更',exact:true}).click();
  await page.getByRole('dialog',{name:'审查代码变更'}).waitFor();
  assert.ok(parseInt((await page.locator('.review-deleted').textContent()).replace('−',''))>0);
  await page.getByRole('dialog',{name:'审查代码变更'}).getByRole('button',{name:'新增 · sql/added.sql',exact:true}).click();
  assert.equal(await page.locator('.review-deleted').textContent(),'−0 行删除');
  assert.equal(filesByProject.project.find(file=>file.path==='sql/new.sql').content,beforeReview,'Review does not write files');
  await page.screenshot({path:'../../target/sql-studio-ide-audit/sql-review.png',fullPage:true});
  await page.getByRole('button',{name:'确认并保存 2 个文件',exact:true}).click();
  await page.getByRole('dialog',{name:'审查代码变更'}).waitFor({state:'detached'});
  assert.equal(filesByProject.project.find(file=>file.path==='sql/new.sql').content,'SELECT 42 AS repaired;');
  await page.getByRole('button',{name:'新建会话',exact:true}).click();
  await page.getByRole('button',{name:'代码解释',exact:true}).click();
  await page.getByLabel('发送给 AI 工程助手').fill('@sql/new.sql 解释当前 SQL');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await page.getByText('代码解释 · 回答完成',{exact:true}).waitFor();
  assert.equal(await page.locator('.sql-apply').count(),0,'Explanations cannot apply code');
  await page.getByRole('button',{name:'新建会话',exact:true}).click();
  assert.equal(await page.locator('.sql-chat-turn,.sql-chat-result').count(), 0);
  assert.equal(await page.getByLabel('发送给 AI 工程助手').inputValue(), '');
  assert.equal(await page.getByLabel('发送给 AI 工程助手').evaluate(el=>el===document.activeElement), true);
  await page.locator('.sql-current-context').filter({hasText:'new.sql'}).waitFor();
  await page.reload({waitUntil:'domcontentloaded'});
  await page.locator('.sql-current-context').filter({hasText:'new.sql'}).waitFor();
  assert.equal(await page.locator('.sql-chat-turn,.sql-chat-result').count(), 0, 'Reload must retain the fresh conversation despite existing history');
  await page.locator('.sql-chat-settings summary').click();
  const history=page.locator('.sql-chat-settings label').filter({hasText:'任务历史'}).locator('select');
  await history.selectOption('generation-1');
  await page.locator('.sql-chat-result').waitFor();
  await page.getByRole('button', {name:'新建会话',exact:true}).click();
  await page.locator('.sql-tree-file button[title="sql/new.sql"]').click({ button: 'right' });
  await page.getByRole('menuitem', { name: '删除文件…' }).click();
  await page.getByRole('dialog',{name:'删除文件',exact:true}).waitFor();
  await page.getByRole('button',{name:'取消',exact:true}).click();
  assert.equal(await page.locator('.sql-tree-file button[title="sql/new.sql"]').count(),1);
  await page.locator('.sql-tree-file button[title="sql/new.sql"]').click({button:'right'});
  await page.getByRole('menuitem',{name:'删除文件…'}).click();
  await page.getByRole('button',{name:'确认删除'}).click();
  await page.locator('.sql-tree-file button[title="sql/new.sql"]').waitFor({ state: 'detached' });
  await page.getByRole('button', { name: /新建工程/ }).first().click();
  await page.locator('.sql-dialog input').fill('Delete me');
  await page.getByRole('button', { name: '创建工程' }).click();
  await page.locator('.sql-studio-bar select[aria-label="SQL 工程"]').selectOption('new-project');
  await page.locator('.sql-tree-scroll').click({button:'right'});
  assert.equal(await page.getByRole('menuitem',{name:'新建 SQL 文件…'}).isDisabled(),true,'Empty project needs a folder first');
  await page.getByRole('menuitem',{name:'新建文件夹…',exact:true}).click();
  await page.getByLabel('文件夹路径').fill('scripts');
  await page.getByRole('button',{name:'创建文件夹',exact:true}).click();
  await page.locator('summary[data-folder="scripts"]').waitFor();
  assert.equal(filesByProject['new-project'].length,0,'Empty folder is not an editor file');
  await page.reload({waitUntil:'domcontentloaded'});
  await page.locator('summary[data-folder="scripts"]').waitFor();
  await page.locator('summary[data-folder="scripts"]').click({button:'right'});
  await page.getByRole('menuitem',{name:'新建 SQL 文件…'}).click();
  assert.equal(await page.getByLabel('目标文件夹').inputValue(),'scripts');
  await page.getByLabel('SQL 文件名').fill('job.sql');
  await page.getByRole('button',{name:'创建并打开'}).click();
  await page.locator('.sql-tree-file button[title="scripts/job.sql"]').waitFor();
  await page.getByRole('button', { name: '删除工程' }).click();
  await page.getByRole('button',{name:'确认删除'}).click();
  await page.waitForFunction(() => !document.querySelector('.sql-studio-bar select[aria-label="SQL 工程"] option[value="new-project"]'));
  assert.equal(projects.length, 1);
  const login = await browser.newPage();
  await login.route('**/api/v1/**', route => route.fulfill({ json:{code:200,data:Object.values(manifests)} }));
  await login.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/login`,{waitUntil:'domcontentloaded'});
  await login.locator('.login-submit').waitFor();
  assert.equal(await login.locator('.login-form select').count(),0,'Login must not contain a workspace dropdown');
  for (const viewport of [{width:1920,height:1080},{width:1366,height:768},{width:1280,height:720},{width:1024,height:768},{width:390,height:844}]) {
    await login.setViewportSize(viewport);
    await login.locator('.login-submit').scrollIntoViewIfNeeded();
    const bounds=await login.locator('.login-submit').boundingBox();
    assert.ok(bounds.x>=0 && bounds.x+bounds.width<=viewport.width && bounds.y>=0 && bounds.y+bounds.height<=viewport.height,`Login button reachable ${JSON.stringify(viewport)}`);
    const size=await login.locator('.login-view').evaluate(el=>({width:el.clientWidth,scroll:el.scrollWidth,height:el.clientHeight}));
    assert.ok(size.scroll<=size.width+1,`Login has no horizontal clipping: ${JSON.stringify({viewport,size})}`);
    assert.equal(size.height,viewport.height,'Login owns a scrollable viewport');
    if(viewport.width>1080) {
      const card=await login.locator('.login-card').boundingBox();
      assert.ok(card.y>=0 && card.y+card.height<=viewport.height,`Full login form fits ${JSON.stringify(viewport)}: ${JSON.stringify(card)}`);
    }
  }
  await login.close();
  await mkdir('../../target/sql-studio-ide-audit', { recursive: true });
  await page.screenshot({ path: '../../target/sql-studio-ide-audit/sql-studio-ide.png', fullPage: true });
  assert.deepEqual(errors, []);
  console.log('PASS: authorized Schema Explorer, lazy fields, actual Monaco alias completion, revocation cache clearing, responsive toolbar, file search, current-file context, quick actions, Ctrl+Enter submission, Runtime running/cancelled states, Diff accept/reject, file creation/deletion, authorized library selection, Agent/model exclusivity, project deletion (HTTP fixtures only).');
} finally { await browser?.close(); await server.close(); }
