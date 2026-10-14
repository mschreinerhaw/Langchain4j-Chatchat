import { chromium, request } from 'playwright-core';
import { access, mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const base = process.env.SQL_STUDIO_DEPLOY_BASE;
const username = process.env.SQL_STUDIO_DEPLOY_USER;
const password = process.env.SQL_STUDIO_DEPLOY_PASSWORD;
assert.ok(base && username && password, 'Supply deployment URL and credentials through environment variables');
const output = '../../target/sql-assistant-deploy';
await mkdir(output, { recursive: true });
const http = await request.newContext();
let headers, browser, projectId, taskId;
const states = [], errors = [];
async function api(path, options = {}) {
  const response = await http.fetch(`${base}/api/v1${path}`, { headers, ...options });
  assert.equal(response.status(), 200, `${options.method || 'GET'} ${path}`);
  const payload = await response.json();
  assert.equal(payload.code, 200, `${path}: ${payload.message}`);
  return payload.data;
}
try {
  const session = await api('/enterprise/auth/login', { method:'POST', data:{username,password} });
  headers = { Authorization:`Bearer ${session.token}`, 'X-Workspace-Id':'data-science' };
  const existing = await api('/data-science/sql/projects');
  const project = await api('/data-science/sql/projects', {method:'POST',data:{name:`__sql_assistant_smoke_${Date.now()}`,dialect:'spark'}});
  projectId = project.id;
  await api(`/data-science/sql/projects/${projectId}/folders`, {method:'POST',data:{path:'sql'}});
  await api(`/data-science/sql/projects/${projectId}/files`, {method:'PUT',data:{path:'sql/smoke.sql',kind:'SQL',content:'SELECT 1 AS smoke_test;',expectedVersion:0}});
  let executablePath;
  for (const path of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {
    try { await access(path); executablePath = path; break; } catch {}
  }
  assert.ok(executablePath, 'Local Chromium required');
  browser = await chromium.launch({executablePath,headless:true,args:['--no-proxy-server']});
  const page = await browser.newPage({viewport:{width:1600,height:1000}});
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', async response => {
    if (response.request().method() !== 'GET' || !response.url().includes(`/projects/${projectId}/generations/`)) return;
    try {
      const data = (await response.json()).data;
      if (data?.task) { taskId = data.task.taskId; states.push({status:data.task.status,eventCount:data.events?.length || 0,files:data.files?.length || 0}); }
    } catch {}
  });
  await page.addInitScript(({session,projectId}) => {
    localStorage.setItem('chatchat.auth.session',JSON.stringify(session));
    const owner = [session.user.tenantId,session.user.id,'data-science'].map(encodeURIComponent).join(':');
    localStorage.setItem(`sql-studio-selection:${owner}`,JSON.stringify({version:1,value:{projectId}}));
    localStorage.setItem(`sql-studio-ide-layout:${owner}`,JSON.stringify({version:1,value:{explorerWidth:230,assistantWidth:420,bottom:false}}));
  }, {session,projectId});
  await page.goto(`${base}/#/w/data-science/sqlStudio`,{waitUntil:'domcontentloaded'});
  await page.locator('.sql-current-context').filter({hasText:'smoke.sql'}).waitFor({timeout:45000});
  assert.equal(await page.locator('.sql-assistant-tabs,.sql-bottom-hint').count(),0);
  await page.getByLabel('发送给 AI 工程助手').fill('为界面冒烟验证生成 sql/smoke_result.sql，仅包含 SELECT 1 AS smoke_test;。不访问外部数据，不调用外部工具，不执行 SQL，返回一个文件即可。');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await page.locator('.sql-assistant-progress.is-running').waitFor({timeout:15000});
  await page.screenshot({path:`${output}/production-running.png`,fullPage:true});
  await page.waitForFunction(() => {
    const progress=document.querySelector('.sql-assistant-progress');
    return progress && !progress.classList.contains('is-running') && !progress.textContent.includes('准备就绪') && !progress.textContent.includes('任务尚未提交');
  }, null, {timeout:150000});
  await page.getByRole('button',{name:'确认并保存 1 个文件',exact:true}).waitFor({timeout:15000});
  assert.ok(states.some(state => !['SUCCESS','PARTIAL','PARTIAL_SUCCESS','FAILED','CANCELLED'].includes(state.status)), 'Observed real in-flight task');
  assert.ok(states.some(state => state.status === 'SUCCESS' && state.files === 1), 'Real model returned one valid SQL file');
  const stable = await page.evaluate(async () => {
    const values=[];
    for(let i=0;i<90;i++) { await new Promise(requestAnimationFrame); values.push([...document.querySelectorAll('.sql-studio,.sql-ide-shell,.sql-editor-pane,.sql-monaco-host')].map(el=>{
      const r=el.getBoundingClientRect();return [r.x,r.y,r.width,r.height];
    })); }
    return new Set(values.map(JSON.stringify)).size;
  });
  assert.equal(stable,1,'Page and editor keep stable bounds');
  assert.deepEqual(errors,[]);
  await page.screenshot({path:`${output}/production-completed.png`,fullPage:true});
  await page.getByRole('button',{name:'新建会话',exact:true}).click();
  assert.equal(await page.locator('.sql-chat-turn,.sql-chat-result').count(),0);
  assert.equal(await page.getByLabel('发送给 AI 工程助手').inputValue(),'');
  await page.reload({waitUntil:'domcontentloaded'});
  await page.locator('.sql-current-context').filter({hasText:'smoke.sql'}).waitFor({timeout:45000});
  assert.equal(await page.locator('.sql-chat-turn,.sql-chat-result').count(),0,'Fresh conversation survives reload');
  const previous=await api(`/data-science/sql/projects/${projectId}/generations`);
  const fresh=await api(`/data-science/sql/projects/${projectId}/generations`,{method:'POST',data:{prompt:'Fresh conversation isolation check',dialect:'spark',mappingIds:[],referenceIds:[]}});
  assert.notEqual(fresh.taskRequest.sessionId,previous[0].prepared.taskRequest.sessionId,'New request uses isolated Runtime memory');
  assert.equal(fresh.taskRequest.historyWindow,0);
  assert.ok(!fresh.taskRequest.query.includes('Previous project conversation:'),'New request has no previous dialogue');
  await page.screenshot({path:`${output}/production-new-conversation.png`,fullPage:true});
  const result={realTaskId:taskId,states,layoutStableFrames:90,browserErrors:errors,existingProjects:existing.length,temporaryProject:projectId};
  await writeFile(`${output}/verification.json`,JSON.stringify(result,null,2));
  console.log(JSON.stringify(result,null,2));
} finally {
  await browser?.close();
  if (projectId) {
    const history = await api(`/data-science/sql/projects/${projectId}/generations`);
    const running = history.filter(item=>item.taskId);
    for (const item of running) {
      const result=await api(`/data-science/sql/projects/${projectId}/generations/${item.id}`);
      if (result.task && !['SUCCESS','PARTIAL','PARTIAL_SUCCESS','FAILED','CANCELLED','KILLED','REJECTED','EMPTY','NO_PRESENTABLE_RESULT','TIME_BUDGET_EXHAUSTED','MODEL_BUDGET_EXHAUSTED','TIMEOUT_CANCELLED','DLQ'].includes(result.task.status)) {
        await api(`/agent/tasks/${item.taskId}/cancel`,{method:'POST'});
        for(let i=0;i<20;i++) { const current=await api(`/data-science/sql/projects/${projectId}/generations/${item.id}`);
          if(current.task?.status==='CANCELLED')break;
          await new Promise(resolve=>setTimeout(resolve,500));
        }
      }
    }
    await api(`/data-science/sql/projects/${projectId}`,{method:'DELETE'});
    console.log('Temporary SQL project and its files/generation records cleaned up; Runtime audit history retained.');
  }
  await http.dispose();
}
