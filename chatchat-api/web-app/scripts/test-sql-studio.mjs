import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
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
let browser,page;
try {
  await server.listen();
  browser = await chromium.launch({ executablePath, headless: true });
  page = await browser.newPage({ viewport: { width: 1560, height: 980 } });
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
  let taskStatus = 'WAIT_MODEL', submissionCount = 0, failNextPoll = false, prepareCount=0,failNextPrepare=false;
  const generation = { id: 'generation-1', taskId: 'task-1', applied: false, prepared:{taskRequest:{toolInput:{sqlStudioAction:'generate'}}} };
  const metadataTable={database:'analytics',schema:'public',tableName:'orders',tableRef:'orders-ref'};
  let metadataDenied=false,descriptions=0,metadataPaged=false;
  const pagedTables=Array.from({length:125},(_,i)=>({...metadataTable,tableName:i?'table_'+i:'orders',tableRef:'table-ref-'+i}));
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
    else if(path.endsWith('/sql/agent-options'))data=[{id:'published-sql-agent',name:'Published SQL assistant',marketStatus:'published'}];
    else if(path.endsWith('/metadata/assets'))data=metadata([{assetRef:'source',name:'Authorized source',sourceDialect:'trino'}]);
    else if(path.endsWith('/metadata/contexts'))data={discoveryId:'discovery',bindings:[{...request.postDataJSON().bindings[0],bindingId:'binding',sourceDialect:'trino'}]};
    else if(path.endsWith('/metadata/contexts/discovery/operations')){
      if(metadataDenied){await route.fulfill({status:403,json:{code:403,message:'Metadata permission revoked'}});return;}
      const args=request.postDataJSON().arguments;
      if(args.operation==='DESCRIBE')descriptions++;
      if(metadataPaged && args.operation==='SEARCH'){
        const offset=(args.page-1)*args.pageSize;
        data=metadata(pagedTables.slice(offset,offset+args.pageSize));data.metadata.totalMatched=pagedTables.length;data.metadata.hasMore=offset+args.pageSize<pagedTables.length;
      }else data=metadata(args.operation==='LIST_NAMESPACES'?[{database:'analytics',schema:'public'}]:args.operation==='DESCRIBE'?[{...metadataTable,columns:[{name:'order_id',dataType:'bigint'}]}]:[metadataTable]);
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
      if(failNextPrepare){failNextPrepare=false;await route.fulfill({status:504,json:{code:504,message:'代码计划等待超时。请求编号：studio-code-fixture-timeout'}});return;}
      const body = request.postDataJSON();
      assert.deepEqual(body.mappingIds, []);
      assert.deepEqual(body.mappingDocumentIds, ['mapping-1']);
      assert.ok(body.prompt.includes('sql/new.sql'));
      generation.id='generation-'+(++prepareCount);generation.applied=false;generation.prepared.taskRequest.toolInput.sqlStudioAction=body.action;
      generation.prepared.taskRequest.toolInput.studioCodePlan={planId:'sql-plan-'+prepareCount,interpretationPlan:{intent:{goal:'结合当前工程形成可审查的 SQL 建议'},context:{missing_info:[]},plan:{steps:[{id:1,input:{description:'分析已授权工程上下文'}},{id:2,input:{description:'生成结果并声明未执行的验证'}}]}}};
      generation.baseVersions=Object.fromEntries(filesByProject.project.map(file=>[file.path.toLowerCase(),file.version]));
      data = { generationId: generation.id, taskRequest: { query: body.prompt, sessionId: 'session',toolInput:{...generation.prepared.taskRequest.toolInput} } };
    }
    else if (path === '/api/v1/agent/tasks' && method === 'POST') { assert.equal(request.postDataJSON().historyWindow, 0); await new Promise(resolve => setTimeout(resolve, 350)); submissionCount++; data = { taskId: 'task-1', status: taskStatus }; }
    else if (path.endsWith('/task-1/cancel')) { taskStatus = 'CANCELLED'; data = true; }
    else if (path.endsWith('/generations/'+generation.id+'/task')) data = generation;
    else if (path.endsWith('/generations/'+generation.id)) {
      if (failNextPoll) { failNextPoll = false; await route.fulfill({ status: 503, json: {code:503,message:'Temporary interruption'} }); return; }
      data = { generation, task: { taskId: 'task-1', status: taskStatus, createTime: new Date(Date.now() - 3000).toISOString() },
        events: [{ eventId: 'model-event', sequence: 1, type: 'RUNTIME_OBSERVATION', payload: JSON.stringify({payload:{metadata:{eventKind:'MODEL_INFERENCE',eventState:'STARTED'}}}) }],
        files: taskStatus==='SUCCESS' && generation.prepared.taskRequest.toolInput.sqlStudioAction!=='explain' ? [{path:'sql/new.sql',content:'SELECT 42 AS repaired;'}, {path:'sql/added.sql',content:'SELECT 7 AS added;'}] : [],
        rawAnswer:taskStatus==='SUCCESS' ? JSON.stringify({notes:'解释完成，SELECT 返回常量。'}) : '', outputError: '' };
    }
    else if(path.endsWith('/generations/'+generation.id+'/apply')) {
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
  assert.equal(await page.getByLabel('展开或折叠数据源导航').getAttribute('aria-expanded'),'false','Datasource navigation starts collapsed');
  await page.getByRole('combobox',{name:'选择数据源',exact:true}).click();
  await page.getByRole('option',{name:'Authorized source',exact:true}).click();
  await page.getByRole('combobox',{name:'选择数据库或 Schema',exact:true}).click();
  await page.getByRole('option').filter({hasText:'analytics'}).click();
  await page.getByLabel('展开或折叠数据源导航').click();
  await page.getByLabel('SQL 方言',{exact:true}).selectOption('trino');
  await page.getByLabel('展开 orders',{exact:true}).waitFor();
  assert.equal(descriptions,0,'Table listing never requests field descriptions');
  await page.getByLabel('展开 orders',{exact:true}).click();
  await page.locator('.sql-schema-explorer [role=group]').filter({hasText:'order_id'}).waitFor();
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
  for(const width of [1920,1366,1024]) {
    await page.setViewportSize({width,height:900});
    for(let cycle=0;cycle<3;cycle++) {
      await page.getByLabel('切换工程文件',{exact:true}).click();
      assert.equal(await page.locator('.sql-resource-sidebar').boundingBox(),null,'Collapsed sidebar leaves no visible layout box');
      assert.equal(await page.locator('.sql-ide-shell').evaluate(el=>el.style.getPropertyValue('--sql-explorer-grip')),'0px','Collapsed divider has zero width');
      await page.getByLabel('切换工程文件',{exact:true}).click();
      assert.ok(await page.locator('.sql-resource-sidebar').isVisible());
    }
  }
  await page.setViewportSize({width:1560,height:980});
  await mkdir('../../target/sql-studio-ide-audit',{recursive:true});
  await page.screenshot({path:'../../target/sql-studio-ide-audit/sql-schema-explorer.png',fullPage:true});
  metadataPaged=true;
  const explorer=page.locator('.sql-schema-explorer');
  await page.getByLabel('刷新数据库上下文',{exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.sql-schema-explorer [role=treeitem]').length===50);
  const pager=explorer.getByLabel('元数据分页');
  await page.setViewportSize({width:1280,height:720});
  const listBounds=await explorer.getByRole('tree').boundingBox(),footerBounds=await pager.boundingBox();
  assert.ok(listBounds.height>0 && listBounds.y+listBounds.height<=footerBounds.y,'Table rows scroll above the fixed pager');
  assert.ok(footerBounds.y+footerBounds.height<=720,'Pager stays inside a short viewport');
  assert.ok(await explorer.getByRole('tree').evaluate(el=>el.scrollHeight>el.clientHeight),'Large metadata lists have their own scrollbar');
  await pager.getByRole('button',{name:'下一页',exact:true}).click();
  await page.getByLabel('展开 table_50',{exact:true}).waitFor();
  assert.ok((await pager.textContent()).includes('2 / 3 页'));
  await page.getByLabel('展开 table_50',{exact:true}).click();
  await explorer.getByRole('group').waitFor();
  await explorer.getByRole('tree').evaluate(el=>{el.scrollTop=160;});
  await page.locator('.monaco-editor').first().click({position:{x:180,y:80}});
  assert.equal(await explorer.getByRole('treeitem').count(),50,'Editor clicks preserve loaded metadata');
  await page.evaluate(()=>window.dispatchEvent(new Event('blur')));
  assert.equal(await explorer.getByRole('treeitem').count(),50,'Window blur preserves displayed metadata');
  await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
  await page.waitForFunction(()=>document.querySelector('.sql-schema-tables')?.getAttribute('aria-busy')==='false');
  assert.ok((await pager.textContent()).includes('2 / 3 页'),'Focus does not reset the current page');
  assert.ok((await explorer.getByRole('group').textContent()).includes('order_id'),'Focus preserves expanded fields');
  assert.equal(await explorer.getByRole('tree').evaluate(el=>el.scrollTop),160,'Focus preserves scroll position');
  await page.screenshot({path:'../../target/sql-studio-ide-audit/sql-schema-pagination.png',fullPage:true});
  await pager.getByRole('button',{name:'下一页',exact:true}).click();
  await page.getByLabel('展开 table_100',{exact:true}).waitFor();
  assert.equal(await explorer.getByRole('treeitem').count(),25);
  assert.ok(await pager.getByRole('button',{name:'下一页',exact:true}).isDisabled(),'Final page has no next page');
  await pager.getByRole('button',{name:'上一页',exact:true}).click();
  await page.getByLabel('展开 table_50',{exact:true}).waitFor();
  await explorer.getByLabel('每页表数量').selectOption('20');
  await page.getByLabel('展开 orders',{exact:true}).waitFor();
  assert.equal(await explorer.getByRole('treeitem').count(),20);
  assert.ok((await pager.textContent()).includes('1 / 7 页'));
  await page.setViewportSize({width:1560,height:980});
  metadataDenied=true;
  await page.evaluate(()=>{window.dispatchEvent(new Event('blur'));window.dispatchEvent(new Event('focus'));});
  await page.locator('.sql-schema-explorer [role=alert]').waitFor();
  assert.equal(await page.locator('.sql-schema-explorer [role=treeitem]').count(),0,'Revocation removes cached visible fields');
  metadataDenied=false;
  assert.ok(await page.locator('.sql-resource-sidebar').isVisible(),'Project tree and metadata coexist in the unified explorer');
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
  await page.locator('.sql-database-current-file').filter({ hasText: 'new.sql' }).waitFor();
  await page.getByRole('button', { name: /分析当前 SQL/ }).click();
  assert.equal(await page.getByLabel('发送给 AI 工程助手').inputValue(), '请解释当前 SQL 的逻辑，并检查潜在问题。');
  assert.ok(await page.locator('.sql-context-file-link').filter({hasText:'new.sql'}).isVisible());
  assert.equal(await page.getByRole('button', { name: '智能生成', exact: true }).count(), 0);
  assert.equal(await page.getByRole('button', { name: '对话', exact: true }).count(), 0);
  await page.getByLabel('发送给 AI 工程助手').press('Enter');
  assert.equal(submissionCount, 0, 'Enter must insert a newline without submitting a task');
  assert.equal(await page.locator('.sql-chat-composer .sql-primary').isDisabled(), false, 'Mapping is optional');
  assert.equal(await page.getByRole('button', { name: '从文档库选 SQL 案例' }).count(), 0);
  assert.equal(await page.locator('.sql-project-tree > header button').count(),0);
  assert.equal(await page.getByRole('button', { name: '选择 Mapping（可选）' }).count(),0);
  await page.getByLabel('高级设置',{exact:true}).click();
  await page.locator('.sql-user-documents > summary').click();
  await page.getByRole('button', { name: '选择知识库文档', exact:true }).click();
  await page.locator('.sql-library-list input').check();
  await page.getByRole('button', { name: '确认上下文' }).click();
  await page.getByLabel('高级设置',{exact:true}).click();
  await page.locator('.sql-selection-chip').filter({ hasText:'customer.csv' }).waitFor();
  assert.equal(await page.locator('.sql-tree-file button[title="mapping/customer.csv"]').count(), 0);
  assert.equal(filesByProject.project.length, 2, 'Document selection must not import editor files');
  await page.getByRole('button', { name: '模型', exact: true }).click();
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: 'Agent' }).count(), 0);
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: '模型' }).count(), 1);
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  await page.locator('.sql-chat-settings .sql-agent-choice select').selectOption('published-sql-agent');
  assert.equal(await page.locator('.sql-chat-settings .sql-agent-choice').filter({ hasText: 'Agent' }).count(), 1);
  await page.getByLabel('关闭高级设置',{exact:true}).click();
  await page.getByLabel('SQL 助手方式',{exact:true}).selectOption('generate');
  await page.getByRole('button',{name:'发送',exact:true}).hover();
  assert.equal(await page.getByRole('button',{name:'发送',exact:true}).evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(29, 78, 216)','SQL send retains blue on hover');
  await page.getByLabel('发送给 AI 工程助手').press('Control+Enter');
  await page.locator('.sql-assistant-progress').filter({ hasText: '正在制定代码协作计划' }).waitFor();
  assert.equal(await page.locator('.sql-assistant-panel > .sql-assistant-progress').count(),0,'Progress never overlays chat messages');
  assert.equal(await page.locator('.sql-chat-turn.assistant .sql-assistant-progress').count(),1,'Progress belongs to the active reply');
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
  assert.equal(await page.locator('.sql-send-question').count(), 0, 'Running Runtime task replaces send with stop to block duplicate submission');
  assert.ok(await page.getByLabel('停止任务',{exact:true}).isEnabled());
  assert.equal(await page.getByRole('button', {name:'新建会话',exact:true}).isDisabled(), true);
  await page.getByLabel('停止任务',{exact:true}).click();
  await page.locator('.sql-task-status').filter({ hasText: '任务已取消' }).waitFor();
  await page.getByLabel('发送给 AI 工程助手').fill('继续修改');
  assert.equal(await page.locator('.sql-chat-composer .sql-primary').isDisabled(), false, 'Runtime terminal state unlocks submission');
  taskStatus='SUCCESS';
  await page.getByLabel('SQL 助手方式',{exact:true}).selectOption('fix');
  await page.getByLabel('发送给 AI 工程助手').fill('@sql/new.sql 修复当前文件');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await page.getByRole('button',{name:'审查 2 个文件变更',exact:true}).waitFor();
  assert.ok(await page.locator('.sql-chat-turn.assistant').count()>=2,'Previous replies remain in the conversation');
  assert.equal(await page.locator('.sql-chat-turn.assistant .sql-chat-result').count(),1,'Only the current reply has active review controls');
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
  await page.getByLabel('SQL 助手方式',{exact:true}).selectOption('explain');
  await page.getByLabel('发送给 AI 工程助手').fill('@sql/new.sql 解释当前 SQL');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await page.getByText('代码解释 · 回答完成',{exact:true}).waitFor();
  assert.equal(await page.locator('.sql-apply').count(),0,'Explanations cannot apply code');
  await page.getByRole('button',{name:'新建会话',exact:true}).click();
  assert.equal(await page.locator('.sql-chat-turn,.sql-chat-result').count(), 0);
  assert.equal(await page.getByLabel('发送给 AI 工程助手').inputValue(), '');
  assert.equal(await page.getByLabel('发送给 AI 工程助手').evaluate(el=>el===document.activeElement), true);
  await page.locator('.sql-database-current-file').filter({hasText:'new.sql'}).waitFor();
  const submittedBeforeFailure=submissionCount;failNextPrepare=true;
  await page.getByLabel('SQL 助手方式',{exact:true}).selectOption('generate');
  await page.getByLabel('发送给 AI 工程助手').fill('验证计划失败反馈');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await page.locator('.sql-chat-turn.assistant').filter({hasText:'studio-code-fixture-timeout'}).waitFor();
  assert.equal(submissionCount,submittedBeforeFailure,'A failed plan never submits a Runtime task');
  await page.screenshot({path:'../../target/sql-studio-ide-audit/sql-chat-error.png',fullPage:true});
  await page.getByRole('button',{name:'新建会话',exact:true}).click();
  await page.reload({waitUntil:'domcontentloaded'});
  await page.locator('.sql-database-current-file').filter({hasText:'new.sql'}).waitFor();
  assert.equal(await page.locator('.sql-chat-turn,.sql-chat-result').count(), 0, 'Reload must retain the fresh conversation despite existing history');
  await page.getByLabel('历史会话',{exact:true}).click();
  await page.locator('.sql-assistant-history button').first().click();
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
  await page.getByRole('button',{name:'保存文件夹',exact:true}).click();
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
  console.log('PASS: authorized Schema Explorer, scrollable 125-table pagination, page sizes, focus/page/field/scroll retention, lazy fields, actual Monaco alias completion, revocation cache clearing, responsive toolbar, file search, current-file context, quick actions, Ctrl+Enter submission, Runtime running/cancelled states, Diff accept/reject, file creation/deletion, authorized library selection, Agent/model exclusivity, project deletion (HTTP fixtures only).');
} catch(error) {
  await mkdir('../../target/sql-studio-ide-audit',{recursive:true});
  if(page){await page.screenshot({path:'../../target/sql-studio-ide-audit/failure.png',fullPage:true});await writeFile('../../target/sql-studio-ide-audit/failure.html',await page.content());}
  throw error;
} finally { await browser?.close(); await server.close(); }
