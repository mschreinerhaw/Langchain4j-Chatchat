/* Real SQL Studio acceptance. No route interception or synthetic API responses.
 * Required: SQL_UI_AUTH_FILE (normal login JSON, {data:session}), SQL_UI_STATE_FILE
 * ({projectId,source,namespace}; use an authorized controlled SQL project).
 * Optional: SQL_UI_URL, SQL_UI_MODEL, SQL_UI_EVIDENCE, SQL_UI_EDGE,
 * SQL_UI_LAYOUT_ONLY=1 or SQL_UI_TEMPLATE_ONLY=1 (no model requests).
 * SQL_UI_TEMPLATE_TEST=1: create/test a revision ONLY in the controlled fixture
 * whose SQL is SELECT {{value}} + 1 AS acceptance_value. Never publishes it.
 * SQL_UI_EXPECT_TEMPLATE_DENIAL=1: assert an existing allowlist rejection instead
 * of claiming the SQL query ran. Never changes grants/allowlists.
 * Only editor drafts are changed/undone; no business SQL or source file is saved.
 */
const { chromium } = require('../chatchat-api/web-app/node_modules/playwright-core');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const normalize = text => text.replace(/\r\n/g, '\n'); // Windows clipboard uses CRLF.
async function until(fn, timeout = 90000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return; await delay(150); }
  throw Error('Real SQL Studio condition timed out');
}
async function choose(page, label, value) {
  const input = page.getByRole('combobox', { name: label, exact: true });
  await until(() => input.isEnabled()); await input.click();
  const list = page.getByRole('listbox', { name: label, exact: true });
  await list.waitFor();
  for (const option of await list.getByRole('option').elementHandles()) {
    if (await option.getAttribute('data-value') === value) { await option.click(); return; }
  }
  throw Error('Required authorized option unavailable: ' + label);
}
(async () => {
  const auth = JSON.parse(fs.readFileSync(process.env.SQL_UI_AUTH_FILE)).data;
  const state = JSON.parse(fs.readFileSync(process.env.SQL_UI_STATE_FILE));
  const out = process.env.SQL_UI_EVIDENCE || 'target/sql-assistant-browser';
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({
    executablePath: process.env.SQL_UI_EDGE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true, args: ['--no-proxy-server'],
  });
  const proof = { realVuePage: true, mockedRequests: false, checks: {}, errors: [], generations: [] };
  try {
    const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, permissions: ['clipboard-read', 'clipboard-write'] });
    await context.addInitScript(a => localStorage.setItem('chatchat.auth.session', JSON.stringify(a)), auth);
    const page = await context.newPage();
    page.on('pageerror', e => proof.errors.push(e.message));
    const posts = [], snapshots = new Map(), preparedIds = new Map(), mutations = [];
    page.on('request', r => {
      const path = new URL(r.url()).pathname;
      if (r.method() === 'POST' && path.endsWith('/generations')) posts.push(r.postDataJSON());
      if (r.method() !== 'GET' && (/\/files$/.test(path) || /\/execute$|\/apply$/.test(path))) mutations.push(path);
    });
    page.on('response', async r => {
      if (r.request().method() === 'POST' && /\/generations$/.test(new URL(r.url()).pathname)) {
        try { const body = await r.json(), data = body.data || body; preparedIds.set(r.request().postDataJSON().prompt, data.generationId); } catch { /* reported by UI */ }
      }
      if (r.request().method() !== 'GET' || !/\/generations\/[^/]+$/.test(new URL(r.url()).pathname)) return;
      try { const body = await r.json(), data = body.data || body; if (data.generation?.id) snapshots.set(data.generation.id, data); } catch { /* a navigation may abort polling */ }
    });
    await page.goto(process.env.SQL_UI_URL || 'http://127.0.0.1:28901/#/w/data-science/sqlStudio');
    await page.getByLabel('SQL 工程', { exact: true }).selectOption(state.projectId);
    await page.getByRole('button', { name: state.fileName || 'publication.sql', exact: false }).first().click();
    await page.getByLabel('新建会话', { exact: true }).click();
    const withoutMetadata=process.env.SQL_UI_NO_METADATA==='1';
    if(withoutMetadata)assert.equal(process.env.SQL_UI_ENGINEERING_REPLAY,'1','Only tool-free engineering may omit metadata');
    else { await choose(page, '选择数据源', state.source);await choose(page, '选择数据库或 Schema', state.namespace); }
    const refresh = page.getByLabel('刷新数据库上下文', { exact: true });
    await until(() => refresh.isEnabled());
    const panel = page.locator('.sql-ide-assistant');
    const grip = await page.getByLabel('调整 AI 助手宽度', { exact: true }).boundingBox(), old = await panel.boundingBox();
    await page.mouse.move(grip.x + 2, grip.y + 25); await page.mouse.down();
    await page.mouse.move(grip.x + 2 + old.width - 480, grip.y + 25); await page.mouse.up(); await page.mouse.move(10, 10);
    assert.equal(await panel.locator(':scope > *').count(), 3);
    assert.equal(await panel.locator('.sql-current-context,.sql-schema-controls,.sql-assistant-workbench').count(), 0);
    assert.equal(await panel.locator('[aria-label="数据库上下文"]').getByRole('combobox').count(), 2);
    proof.checks.threeRegionsAndNoLegacyCards = true;
    proof.widths = [];
    for (const width of [480, 336, 240, 180, 480]) {
      const g = await page.getByLabel('调整 AI 助手宽度', { exact: true }).boundingBox(), previous = await panel.boundingBox();
      await page.mouse.move(g.x + 2, g.y + 25); await page.mouse.down();
      await page.mouse.move(g.x + 2 + previous.width - width, g.y + 25); await page.mouse.up(); await page.mouse.move(10, 10); await delay(200);
      const metrics = await panel.evaluate(el => ({ width:el.getBoundingClientRect().width, overflow:el.scrollWidth > el.clientWidth + 1,
        regions:el.children.length, contextHeight:el.querySelector('[aria-label="数据库上下文"]').getBoundingClientRect().height,
        chatOverflow:getComputedStyle(el.querySelector('.sql-assistant-scroll')).overflowY,
        footerOverflow:getComputedStyle(el.querySelector('footer')).overflowY }));
      assert.equal(metrics.overflow, false); assert.equal(metrics.regions, 3);
      assert.equal(metrics.chatOverflow, 'auto'); assert.ok(!['auto','scroll'].includes(metrics.footerOverflow));
      proof.widths.push(metrics);
      await panel.screenshot({ path:out+'/after-assistant-'+width+'.png' });
      if (width === 480) await page.screenshot({ path:out+'/after-workbench.png' });
    }
    if (process.env.SQL_UI_LAYOUT_ONLY === '1') {
      fs.writeFileSync(out+'/layout-browser-proof.json', JSON.stringify(proof,null,2));
      console.log(JSON.stringify(proof)); return;
    }
    async function inspectTemplates() {
      await page.getByRole('button', { name:'模板管理与发布', exact:true }).click();
      let templates = page.getByRole('dialog', { name:'发布 MCP SQL 模板', exact:true });
      await until(async () => (await templates.locator(':scope > label select option').count()) > 1);
      const options = await templates.locator(':scope > label select option').allTextContents();
      const activeIndex = options.findIndex(x => x.includes('当前线上'));
      assert.ok(activeIndex > 0);
      const selected = templates.locator(':scope > label select');
      await selected.selectOption({index:activeIndex});
      const activeId = await selected.inputValue();
      await templates.getByText('查看修订正文和参数', {exact:true}).click();
      assert.ok((await templates.innerText()).includes('MCP 工具'));
      proof.checks.realTemplateHistoryViaMainWorkspace = true;
      await templates.screenshot({path:out+'/real-template-history.png'});
      if (process.env.SQL_UI_TEMPLATE_TEST === '1') {
        assert.equal((await templates.locator('details pre').first().innerText()).trim(), 'SELECT {{value}} + 1 AS acceptance_value');
        const draftIndex=options.findIndex(x=>x.includes('DRAFT'));
        if(draftIndex>0)await selected.selectOption({index:draftIndex});
        else await templates.getByRole('button', {name:'从当前保存文件创建下一修订',exact:true}).click();
        await until(async () => (await selected.inputValue()) !== activeId && (await templates.getByLabel('模板测试参数').isEnabled()));
        await templates.getByLabel('模板测试参数').fill('{"value":7}');
        assert.equal(await templates.getByRole('button',{name:'确认发布 MCP 模板',exact:true}).isDisabled(), true);
        const receipt = page.waitForResponse(r => r.request().method()==='POST' && new URL(r.url()).pathname.endsWith('/test'));
        await templates.getByRole('button',{name:'执行真实 SQL 测试',exact:true}).click();
        const response = await receipt, body = await response.json(), row = body.data || body;
        if(!response.ok() && process.env.SQL_UI_EXPECT_TEMPLATE_DENIAL==='1') {
          assert.equal(response.status(),403);assert.match(body.message,/binding|allowlist/i);
          await until(async () => (await templates.locator('[role=alert]').allTextContents()).some(x=>x.includes(body.message)));
          assert.equal(await templates.getByRole('button',{name:'确认发布 MCP 模板',exact:true}).isDisabled(),true);
          proof.templateTest={success:false,httpStatus:403,diagnostic:body.message,queryExecuted:false};
          proof.checks.templateAuthorizationDenialVisibleAndPublishBlocked=true;
          await templates.evaluate(el=>{el.scrollTop=0;});
          await templates.screenshot({path:out+'/real-template-authorization-denial.png'});
        } else {
          assert.equal(response.ok(),true,JSON.stringify(body)); assert.equal(row.testEvidence?.success,true); assert.equal(row.testEvidence.rowCount,1);
          proof.templateTest = {success:true,type:row.testEvidence.type,rowCount:row.testEvidence.rowCount,durationMs:row.testEvidence.durationMs,testId:row.testEvidence.testId};
          await until(async () => (await templates.innerText()).includes('真实 SQL 测试成功'));
          await templates.screenshot({path:out+'/real-template-sql-test.png'});
        }
        await templates.getByRole('button',{name:'关闭',exact:true}).click();
        await page.getByRole('button',{name:'模板管理与发布',exact:true}).click();
        templates=page.getByRole('dialog',{name:'发布 MCP SQL 模板',exact:true});
        await until(async () => (await templates.locator(':scope > label select option').allTextContents()).some(x=>x.includes('当前线上')));
        const active = templates.locator(':scope > label select option').filter({hasText:'当前线上'});
        assert.equal(await active.getAttribute('value'),activeId);
        proof.checks.activePublishedRevisionPreserved=true;
      }
      await templates.getByRole('button',{name:'关闭',exact:true}).click();
    }
    if (process.env.SQL_UI_TEMPLATE_ONLY === '1') {
      await inspectTemplates();assert.equal(proof.errors.length,0);
      fs.writeFileSync(out+'/template-browser-proof.json',JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));return;
    }
    // Rebind the real authorized asset, ensuring the previous namespace disappears.
    if(!withoutMetadata){
    await choose(page, '选择数据源', '');
    assert.equal(await page.getByRole('combobox', { name: '选择数据库或 Schema', exact: true }).isDisabled(), true);
    await choose(page, '选择数据源', state.source);
    await choose(page, '选择数据库或 Schema', state.namespace);
    await until(() => refresh.isEnabled());
    await refresh.click(); await until(() => refresh.isEnabled());
    assert.equal(await page.getByRole('combobox', { name: '选择数据库或 Schema', exact: true }).getAttribute('data-selected-value'), state.namespace);
    proof.checks.sourceSwitchClearsNamespaceAndRefreshPreservesAuthorizedSelection = true;
    }else proof.checks.noMetadataPermissionBypass=true;
    const editor = await page.locator('.sql-monaco-host .monaco-editor').elementHandle();
    await page.getByLabel('切换 AI 工程助手', { exact: true }).click();
    await page.getByLabel('切换 AI 工程助手', { exact: true }).click();
    assert.equal(await editor.evaluate(el => el === document.querySelector('.sql-monaco-host .monaco-editor')), true);
    proof.checks.sidebarTogglePreservesMonaco = true;
    await page.getByLabel('高级设置', { exact: true }).click();
    const settings = page.getByRole('dialog', { name: 'AI 助手高级设置', exact: true });
    if (await settings.getByRole('button', { name: '模型', exact: true }).count()) await settings.getByRole('button', { name: '模型', exact: true }).click();
    await settings.locator('.sql-agent-choice select').selectOption(process.env.SQL_UI_MODEL || 'qwen3.8-flash');
    await settings.getByLabel('关闭高级设置', { exact: true }).click();
    proof.checks.advancedSettingsOverlay = true;
    if(process.env.SQL_UI_RATIO_READONLY==='1'){
      await page.getByLabel('历史会话',{exact:true}).click();
      await page.getByRole('dialog',{name:'SQL 历史会话',exact:true}).locator('.sql-assistant-history button').filter({hasText:'维持担保比例'}).first().click();
      await until(async()=>[...snapshots.values()].some(x=>x.metadataExploration?.diagnostics?.modelResponses?.length));
      await panel.locator('.sql-assistant-scroll').evaluate(el=>{el.scrollTop=0;});await panel.screenshot({path:out+'/ratio-answer-restored.png'});
      const restored=[...snapshots.values()].at(-1);assert.equal(restored.files.length,0);assert.equal(posts.length,0);
      await page.getByLabel('高级设置',{exact:true}).click();await page.getByRole('button',{name:'查看本次运行诊断',exact:true}).click();
      const diagnostics=page.locator('[aria-label="SQL AI 运行诊断"]');await diagnostics.getByText(/OUTPUT_EVIDENCE_VALIDATION_FAILED/).click();
      await page.screenshot({path:out+'/ratio-diagnostics-restored.png'});
      fs.writeFileSync(out+'/ratio-restore-proof.json',JSON.stringify({realPage:true,mockedRequests:false,generationId:restored.generation.id,taskStatus:restored.task.status,sqlFiles:0,newGenerationRequests:posts.length,diagnostics:restored.metadataExploration.diagnostics},null,2));return;
    }
    async function submit(question, cancel = false) {
      const before = posts.length;
      await page.getByLabel('发送给 AI 工程助手', { exact: true }).fill(question);
      await page.getByLabel('发送', { exact: true }).click();
      await until(() => posts.length > before);
      await until(() => preparedIds.has(posts[before].prompt));
      const generationId = preparedIds.get(posts[before].prompt);
      console.log('Real generation submitted: ' + generationId);
      if (cancel) { await until(() => page.getByLabel('停止任务', { exact: true }).isEnabled()); await page.getByLabel('停止任务', { exact: true }).click(); }
      let result;
      await until(() => { result = snapshots.get(generationId);
        return result && ['SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED','KILLED','TIMEOUT_CANCELLED','TIME_BUDGET_EXHAUSTED'].includes(result.task?.status);
      }, 300000);
      await until(() => page.getByLabel('发送', { exact: true }).isVisible());
      proof.generations.push({ id: result.generation.id, taskId: result.task?.taskId, status: result.task?.status, stats: result.metadataExploration?.stats, candidateCount: result.metadataExploration?.candidates?.length || 0 });
      console.log('Real generation finished: ' + result.task?.status);
      return { result, request: posts[before] };
    }
    if(process.env.SQL_UI_METADATA_EVIDENCE==='1'){
      await page.getByLabel('历史会话',{exact:true}).click();
      await page.getByRole('dialog',{name:'SQL 历史会话',exact:true}).locator('.sql-assistant-history button').filter({hasText:'查找当前数据库中能够分析操作日志'}).first().click();
      await until(async()=>await page.locator('.sql-metadata-candidate').count()>0);
      await page.locator('.sql-metadata-candidate').first().locator('button').first().click();
      const detail=page.locator('.sql-metadata-evidence details');await until(()=>detail.isVisible());
      await detail.scrollIntoViewIfNeeded();await panel.screenshot({path:out+'/real-fields-and-relations.png'});
      assert.equal(posts.length,0);assert.ok(await detail.locator('tbody tr').count()>0);
      fs.writeFileSync(out+'/metadata-detail-proof.json',JSON.stringify({realVuePage:true,mockedRequests:false,newGenerationRequests:0,visibleFields:await detail.locator('tbody tr').count(),
        relationshipStatement:(await detail.textContent()).includes('尚未采集')?'NOT_COLLECTED':(await detail.textContent()).includes('JDBC 外键元数据')?'JDBC_IMPORTED_KEYS':'NO_VISIBLE_COLLECTED_KEYS'},null,2));return;
    }
    if(process.env.SQL_UI_BENCHMARK_RESTORE==='1'){
      const expected=process.env.SQL_UI_GENERATION;assert.ok(expected,'SQL_UI_GENERATION is required');
      await page.getByLabel('历史会话',{exact:true}).click();
      await page.getByRole('dialog',{name:'SQL 历史会话',exact:true}).locator('.sql-assistant-history button').filter({hasText:'在当前工程文件路径 publication.sql'}).first().click();
      await until(()=>snapshots.has(expected));const restored=snapshots.get(expected);
      assert.equal(restored.task.status,'SUCCESS');assert.equal(posts.length,0);
      await page.getByLabel('高级设置',{exact:true}).click();await page.getByRole('button',{name:'查看本次运行诊断',exact:true}).click();
      const evidence=page.getByLabel('工程 SQL 元数据证据',{exact:true});await evidence.locator('summary').click();
      const source=restored.generation.prepared.taskRequest.toolInput.sqlCodingMetadata.tables[0];
      assert.ok((await evidence.textContent()).includes(source.snapshotId));assert.ok((await evidence.textContent()).includes(source.discoveryId));
      await page.screenshot({path:out+'/restored-coding-evidence.png'});
      fs.writeFileSync(out+'/benchmark-restore-proof.json',JSON.stringify({realVuePage:true,mockedRequests:false,generationId:expected,status:restored.task.status,newGenerationRequests:posts.length,
        metadataProvenance:{discoveryId:source.discoveryId,bindingId:source.bindingId,tableRef:source.tableRef,snapshotId:source.snapshotId}},null,2));return;
    }
    if(process.env.SQL_UI_BENCHMARK==='1'){
      const before=posts.length;
      await page.getByLabel('AI 探索当前数据库',{exact:true}).click();
      assert.equal(await page.getByLabel('SQL 助手方式',{exact:true}).inputValue(),'explore');
      assert.equal(posts.length,before);proof.checks.sourceEntryDoesNotSubmit=true;
      await page.getByLabel('历史会话',{exact:true}).click();
      await page.getByRole('dialog',{name:'SQL 历史会话',exact:true}).locator('.sql-assistant-history button').filter({hasText:'查找当前数据库中能够分析操作日志'}).first().click();
      await until(async()=>await page.locator('.sql-metadata-candidate').count()>0);
      const card=page.locator('.sql-metadata-candidate').first(),visibleTable=await card.locator('button').first().textContent();
      const tableLabel=c=>[c.database,c.schema,c.tableName].filter(Boolean).join('.');
      // Restored conversations may contain several older answers. Match the actual
      // visible card, not the first result ever observed by this browser session.
      const restored=[...snapshots.values()].reverse().find(x=>x.metadataExploration?.candidates?.some(c=>tableLabel(c)===visibleTable));
      assert.ok(restored);const candidate=restored.metadataExploration.candidates.find(c=>tableLabel(c)===visibleTable);
      await card.locator('button').first().click();
      const detail=page.locator('.sql-metadata-evidence details');await until(()=>detail.isVisible());
      assert.match(await detail.textContent(),/物理约束|尚未采集/);await panel.screenshot({path:out+'/candidate-evidence.png'});
      // Real metadata completion, exercising the existing grammar and authenticated MCP projection.
      const projectUrl='/api/v1/data-science/sql/projects/'+state.projectId;
      const filesResponse=await context.request.get(new URL(projectUrl+'/files',page.url()).href,{headers:{Authorization:'Bearer '+auth.token,'X-Workspace-Id':'data-science'}});
      const fileData=await filesResponse.json(),files=fileData.data||fileData;
      const currentFile=files.find(f=>f.path.endsWith(state.fileName||'publication.sql'));assert.ok(currentFile);
      const editor=page.locator('.sql-monaco-host .monaco-editor').first();
      const quote=value=>'`'+value.replaceAll('`','``')+'`';
      const probe='WITH unrelated_cte AS (SELECT 1 AS sample) SELECT a. FROM '+quote(candidate.tableName)+' a';
      await editor.click();await page.keyboard.press('Control+a');await page.keyboard.insertText(probe);
      await page.keyboard.press('Control+Home');for(let i=0;i<probe.indexOf('a.')+2;i++)await page.keyboard.press('ArrowRight');
      await page.keyboard.press('Control+Space');
      await until(async()=>await editor.locator('.suggest-widget.visible .monaco-list-row').count()>0,60000);
      const proposals=await editor.locator('.suggest-widget.visible .monaco-list-row').allTextContents();
      assert.ok(proposals.some(text=>candidate.keyColumns.some(column=>text.includes(column))),JSON.stringify(proposals));
      await page.screenshot({path:out+'/real-scope-completion.png'});await page.keyboard.press('Escape');
      await page.keyboard.press('Control+a');await page.keyboard.insertText(currentFile.content);
      await card.getByRole('button',{name:'引用到工程 SQL',exact:true}).click();
      await until(async()=>await page.getByLabel('SQL 助手方式',{exact:true}).inputValue()==='generate');
      assert.equal(posts.length,before);assert.ok(await page.getByLabel('查看本次工程元数据引用',{exact:true}).isVisible());
      const columns=candidate.keyColumns.slice(0,2);assert.ok(columns.length);
      const sql='SELECT '+columns.map(quote).join(', ')+' FROM '+[candidate.schema||candidate.database,candidate.tableName].filter(Boolean).map(quote).join('.')+' LIMIT 5;';
      const generated=await submit('在当前工程文件路径 '+currentFile.path+' 提供以下只读 SQL 的完整文件提案：\n'+sql+'\n仅使用刚引用的表字段，保持查询含义与列名不变，不增加统计或筛选口径，不执行 SQL，不修改其他文件。');
      assert.equal(generated.result.task.status,'SUCCESS');assert.equal(generated.result.outputError,'');assert.ok(generated.result.files.length);
      assert.match(generated.result.validation,/FIELDS_CHECKED/);assert.match(generated.result.validation,/SQL_NOT_EXECUTED/);
      const selection=generated.request.codingMetadataSelection.sources[0];
      const delivered=generated.result.generation.prepared.taskRequest.toolInput.sqlCodingMetadata.tables[0];
      for(const key of ['discoveryId','bindingId'])assert.equal(delivered[key],selection[key]);
      assert.equal(delivered.tableRef,candidate.tableRef);assert.equal(delivered.tableName,candidate.tableName);
      assert.equal(delivered.snapshotId,selection.expectedSnapshotId);assert.equal(mutations.length,0);
      await panel.screenshot({path:out+'/real-evidence-bound-coding.png'});
      proof.benchmark={restoredGeneration:restored.generation.id,candidate:{tableName:candidate.tableName,tableRef:candidate.tableRef,keyColumns:columns},
        scopeCompletion:{withClause:true,realAuthorizedFields:true,proposals},
        coding:{generationId:generated.result.generation.id,status:generated.result.task.status,validation:generated.result.validation,files:generated.result.files.map(f=>f.path),
          provenance:{discoveryId:delivered.discoveryId,bindingId:delivered.bindingId,snapshotId:delivered.snapshotId,tableRef:delivered.tableRef}},
        automaticSqlExecution:false,savedFiles:mutations.length};
      fs.writeFileSync(out+'/benchmark-browser-proof.json',JSON.stringify(proof,null,2));console.log(JSON.stringify(proof.benchmark));return;
    }
    if(process.env.SQL_UI_ENGINEERING_REPLAY==='1'){
      await page.getByLabel('SQL 助手方式',{exact:true}).selectOption('explain');
      await page.getByLabel('附加当前 SQL',{exact:true}).click();
      const explanation=await submit('解释当前 SQL 的含义和返回列，不修改任何文件，不执行 SQL。');
      assert.equal(explanation.result.task.status,'SUCCESS');assert.equal(explanation.result.outputError,'');assert.equal(explanation.result.files.length,0);
      await panel.screenshot({path:out+'/real-explain.png'});
      await page.getByLabel('SQL 助手方式',{exact:true}).selectOption('optimize');
      const optimization=await submit('只优化当前 SQL 文件的格式与可读性，保持常量和结果列名称完全一致。请提供当前文件的完整 SQL 提案，不执行 SQL，不修改其他文件。');
      assert.equal(optimization.result.task.status,'SUCCESS');assert.equal(optimization.result.outputError,'');assert.ok(optimization.result.files.length>0);
      assert.ok(optimization.result.validation.includes('SQL_NOT_EXECUTED'));assert.equal(mutations.length,0);
      proof.engineering={explain:{status:explanation.result.task.status,files:0,validation:explanation.result.validation},optimize:{status:optimization.result.task.status,files:optimization.result.files.length,validation:optimization.result.validation}};
      await panel.screenshot({path:out+'/real-optimize.png'});
      fs.writeFileSync(out+'/engineering-browser-proof.json',JSON.stringify(proof,null,2));console.log(JSON.stringify(proof.engineering));return;
    }
    if(process.env.SQL_UI_RATIO_REPLAY==='1'){
      await page.getByLabel('历史会话',{exact:true}).click();
      await page.getByRole('dialog',{name:'SQL 历史会话',exact:true}).locator('.sql-assistant-history button').filter({hasText:'查找当前数据库中能够分析操作日志'}).first().click();
      await until(async()=>(await page.locator('.sql-metadata-candidate').count())>0);
      const question='请基于上述已确认的元数据生成 SQL。统计口径与筛选条件：编写维持担保比例计算脚本';
      const replay=await submit(question);assert.equal(replay.result.task.status,'SUCCESS');
      assert.equal(replay.result.files.length,0,'The controlled metadata has no confirmed maintenance-guarantee business definition');
      const notes=JSON.parse(replay.result.rawAnswer).notes;assert.match(notes,/担保/);assert.match(notes,/缺|不足|无法|不能|确认|补充/);
      proof.ratioReplay={question,sourceScope:'Authorized controlled metadata, not the unrecovered original task',status:replay.result.task.status,sqlProposalCount:0,notes};
      proof.diagnostics=replay.result.metadataExploration.diagnostics;assert.ok(proof.diagnostics?.modelResponses?.length);
      await panel.screenshot({path:out+'/ratio-replay.png'});
      await page.getByLabel('高级设置',{exact:true}).click();await page.getByRole('button',{name:'查看本次运行诊断',exact:true}).click();
      await page.locator('[aria-label="SQL AI 运行诊断"] details').last().locator('summary').click();
      await page.screenshot({path:out+'/ratio-diagnostics.png'});
      fs.writeFileSync(out+'/ratio-browser-proof.json',JSON.stringify(proof,null,2));console.log(JSON.stringify({status:replay.result.task.status,sqlProposalCount:0,modelCalls:proof.diagnostics.modelResponses.length}));return;
    }
    const first = await submit('查找当前数据库中能够分析操作日志的真实表，列出关键字段和证据。不执行业务 SQL，不编造关系。');
    assert.equal(first.result.task.status, 'SUCCESS');
    assert.ok(first.result.metadataExploration?.candidates?.length > 0);
    assert.equal(first.request.codingMetadataSelection, null);
    assert.ok(!first.request.prompt.includes('acceptance_value'));
    proof.checks.realMetadataExplorationWithoutPreselectedTables = true;
    const candidate = page.locator('.sql-metadata-candidate button').first(); await candidate.click();
    await until(async () => (await page.locator('.sql-metadata-evidence table tbody tr').count()) > 0);
    proof.checks.candidateAuthorizedFields = true;
    await panel.screenshot({ path: out + '/real-metadata-conversation.png' });
    await page.getByLabel('附加当前 SQL', { exact: true }).click();
    const second = await submit('根据上一轮已确认的操作日志表和可见字段，输出一个只读 MySQL 查询。仅选取两个已确认字段，LIMIT 1。使用 SQL 代码块，不执行查询。当前文件只是语法上下文，不作为数据库结构证据。');
    assert.equal(second.result.task.status, 'SUCCESS');
    assert.ok(second.request.prompt.includes('acceptance_value'));
    assert.ok(second.request.metadataDiscoveryId);
    assert.equal(second.request.metadataBindingContextId, first.request.metadataBindingContextId);
    proof.checks.explicitEditorContextAndDiscoveryReuse = true;
    const codeCard = page.locator('.sql-assistant-code').last(); await codeCard.waitFor();
    const generated = await codeCard.locator('code').innerText(); assert.match(generated, /select/i);
    await codeCard.getByRole('button', { name: '复制', exact: true }).click();
    assert.equal(normalize(await page.evaluate(() => navigator.clipboard.readText())), normalize(generated));
    proof.checks.sqlCopy = true;
    const monaco = page.locator('.sql-monaco-host .monaco-editor');
    async function source() {
      await monaco.click(); await page.keyboard.press('Control+KeyA'); await page.keyboard.press('Control+KeyC');
      return normalize(await page.evaluate(() => navigator.clipboard.readText()));
    }
    const original = await source(); await page.keyboard.press('ArrowRight');
    await codeCard.getByRole('button', { name: '插入编辑器', exact: true }).click();
    const inserted = await source(); assert.ok(inserted.includes(original) && inserted.includes(normalize(generated)));
    await page.keyboard.press('Control+KeyZ'); assert.equal(await source(), original);
    await until(() => codeCard.getByRole('button', { name: '替换选中 SQL', exact: true }).isEnabled());
    await codeCard.getByRole('button', { name: '替换选中 SQL', exact: true }).click();
    assert.equal(await source(), normalize(generated));
    await page.keyboard.press('Control+KeyZ'); assert.equal(await source(), original);
    assert.equal(mutations.length, 0);
    proof.checks.realMonacoInsertReplaceUndoWithoutSaveOrExecution = true;
    proof.checks.sqlHighlight = (await codeCard.locator('.hljs-keyword').count()) > 0;
    await panel.screenshot({ path: out + '/real-sql-conversation.png' });
    // Cancel an actual submitted metadata task; no fabricated progress or SQL execution.
    await page.getByLabel('移除 SQL 上下文', { exact: true }).click();
    const stopped = await submit('继续探索当前授权数据库的日志相关表，核对字段和已采集的关联证据，不执行业务 SQL。', true);
    assert.ok(['CANCELLED','KILLED'].includes(stopped.result.task.status));
    assert.ok(!stopped.request.prompt.includes('acceptance_value'));
    proof.checks.realCancellationAndContextRemoval = true;
    await until(() => page.getByRole('button', { name: '重试请求', exact: true }).isVisible());
    proof.checks.terminalRetryEntry = true;
    await panel.screenshot({ path: out + '/real-cancelled-conversation.png' });
    await page.getByLabel('历史会话', { exact: true }).click();
    const history = page.getByRole('dialog', { name: 'SQL 历史会话', exact: true });
    await history.locator('.sql-assistant-history button').filter({ hasText: '查找当前数据库中能够分析操作日志' }).first().click();
    await until(async () => (await page.locator('.sql-metadata-candidate button').count()) > 0);
    proof.checks.realHistoryRestore = true;
    await inspectTemplates();
    assert.equal(proof.errors.length, 0);
    fs.writeFileSync(out + '/interaction-browser-proof.json', JSON.stringify(proof, null, 2));
    console.log(JSON.stringify(proof));
  } catch (error) {
    proof.failure = error.message;
    const pages = browser.contexts().flatMap(c => c.pages());
    if (pages.length) await pages[0].screenshot({path:out+'/interaction-failure.png'}).catch(()=>{});
    fs.writeFileSync(out + '/interaction-browser-failure.json', JSON.stringify(proof, null, 2));
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
