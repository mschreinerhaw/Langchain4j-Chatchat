import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import { readFile, access, mkdir } from 'node:fs/promises';
import assert from 'node:assert/strict';

const manifests = {};
for (const id of ['zhice','data-science']) manifests[id]=JSON.parse(await readFile(`../src/main/resources/workspace/${id}.json`,'utf8'));
const source=`#!/usr/bin/env python3\n# -*- coding: utf-8 -*-\n\nimport json\nfrom collections import Counter\n\ndef parse_log_line(line):\n    return {'message': line.strip()}\n\ndef analyze_errors(logs):\n    errors = [line for line in logs if 'ERROR' in line]\n    return {'total': len(logs), 'error_count': len(errors)}\n\ndef main():\n    result = analyze_errors(['INFO ready', 'ERROR Connection timeout'])\n    print(json.dumps(result, ensure_ascii=False))\n\nif __name__ == '__main__':\n    main()\n`;
const scripts=[{id:'log',assetId:'asset',folderId:'src',fileName:'log_analysis.py',title:'日志分析',sourceCode:source,status:'DRAFT',currentVersion:1},
  {id:'utils',assetId:'asset',folderId:'src',fileName:'utils.py',title:'辅助函数',sourceCode:'def normalize(value):\n    return value.strip()\n',status:'DRAFT',currentVersion:1}];
let executablePath;
for (const path of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {
  try { await access(path); executablePath=path; break; } catch {}
}
assert.ok(executablePath);
const server=await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
let browser;
try {
  await server.listen();
  browser=await chromium.launch({executablePath,headless:true});
  const page=await browser.newPage({viewport:{width:1680,height:1000}});
  const errors=[]; page.on('pageerror',error=>{ errors.push(error.message); console.error(error.stack); });
  const user={id:'python-user',username:'admin',tenantId:'python-tenant',permissionCodes:[]};
  await page.addInitScript(user=>localStorage.setItem('chatchat.auth.session',JSON.stringify({token:'fixture',user})),user);
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;
    let data=[];
    if(path.endsWith('/auth/me'))data=user;
    else if(path.endsWith('/workspaces'))data=Object.values(manifests).map(m=>({...m,defaultWorkspace:m.workspaceId==='zhice'}));
    else if(path.endsWith('/workspaces/preferences'))data={defaultWorkspaceId:'zhice'};
    else if(path.endsWith('/manifest')) {const manifest=manifests[path.split('/').at(-2)];data={manifest,menus:[{id:'workspace',title:'数据科学',children:manifest.pages.map(p=>({id:p.menuCode,title:p.id==='dataScienceDevelop'?'Python 开发':p.id,type:'menu',children:[]}))}]};}
    else if(path.endsWith('/python/workbench'))data={assets:[{id:'asset',name:'workspace',status:'READY',pythonVersion:'3.11.6'}],scripts,folders:[{id:'src',name:'src'}],executions:[],dataFiles:[],systemExamples:[]};
    else if(path.endsWith('/python/environments'))data=[{id:'env',name:'Python 3.11',pythonVersion:'3.11.6'}];
    else if(path.endsWith('/python/models'))data=[{value:'code-model',label:'Code Model',defaultModel:true}];
    else if(path.endsWith('/python/assist/skills'))data=[];
    else if(path.endsWith('/code-tools/ruff-check'))data={exitCode:1,stdout:JSON.stringify([{code:'F401',message:'unused import',location:{row:5,column:1},end_location:{row:5,column:30}}])};
    await route.fulfill({json:{code:200,message:'OK',data}});
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/w/data-science/dataScienceDevelop`,{waitUntil:'domcontentloaded'});
  await page.locator('.python-studio .monaco-editor').waitFor({timeout:60000});
  await page.locator('.script-open').filter({hasText:'utils.py'}).click();
  assert.equal(await page.locator('.editor-tab').count(),2);
  await page.locator('.editor-tab').filter({hasText:'log_analysis.py'}).locator('.tab-close').click();
  assert.equal(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.form.fileName),'utils.py');
  // Closing the final file must leave a persistent empty editor, with safe actions.
  while (await page.locator('.editor-tab .tab-close').count()) {
    await page.locator('.editor-tab .tab-close').last().click();
    const confirm = page.getByRole('dialog', {name:'关闭未保存脚本'});
    if (await confirm.isVisible()) await confirm.getByRole('button', {name:'仍然关闭'}).click();
  }
  await page.getByText('没有打开的脚本', {exact:true}).waitFor();
  assert.equal(await page.getByLabel('保存 Python 文件', {exact:true}).isDisabled(),true);
  assert.equal(await page.getByRole('button',{name:'运行',exact:true}).isDisabled(),true);
  assert.equal(await page.getByLabel('发送 Python AI 请求', {exact:true}).isDisabled(),true);
  assert.equal(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.getModel()),null);
  await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.load(true));
  assert.equal(await page.locator('.editor-tab').count(),0,'Background refresh preserves closed tabs');
  await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.persistWorkspaceDraft());
  await mkdir('../../target/python-studio-editor',{recursive:true});
  await page.screenshot({path:'../../target/python-studio-editor/python-studio-empty.png',fullPage:true});
  await page.reload({waitUntil:'domcontentloaded'});
  await page.getByText('没有打开的脚本', {exact:true}).waitFor();
  assert.equal(await page.locator('.editor-tab').count(),0,'Reload preserves all tabs closed');
  await page.locator('.python-editor-empty').getByRole('button',{name:'新建脚本',exact:true}).click();
  await page.locator('.editor-tab.active').waitFor();
  await page.locator('.ds-page').evaluate(el=>{
    const vm=el.__vueParentComponent.proxy;
    vm.editor.setValue('print("unsaved")\n');
  });
  await page.locator('.editor-tab .tab-close').click();
  const closeDialog=page.getByRole('dialog',{name:'关闭未保存脚本'});
  await closeDialog.getByRole('button',{name:'取消',exact:true}).click();
  assert.equal(await page.locator('.editor-tab').count(),1,'Cancel keeps unsaved script open');
  await page.locator('.editor-tab .tab-close').click();
  await closeDialog.getByRole('button',{name:'仍然关闭',exact:true}).click();
  await page.getByText('没有打开的脚本', {exact:true}).waitFor();
  await page.locator('.script-open').filter({hasText:'log_analysis.py'}).click();
  await page.locator('.editor-tab.active').filter({hasText:'log_analysis.py'}).waitFor();
  assert.equal(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.getValue()),source);
  assert.deepEqual(errors,[]);
  console.log('PASS: close all Python tabs, empty-state actions, background refresh, reload, unsaved confirmation and reopen.');
} finally { await browser?.close(); await server.close(); }
