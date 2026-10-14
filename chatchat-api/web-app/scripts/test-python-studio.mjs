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
  const aiActions=[];
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
    else if(path.endsWith('/code-tools/ruff-format'))data={exitCode:0,stdout:request.postDataJSON().source};
    else if(path.endsWith('/python/scripts')&&request.method()==='POST') {
      const body=request.postDataJSON(); const index=scripts.findIndex(s=>s.id===body.id);
      data={...body,id:body.id||`new-${Date.now()}`,currentVersion:(body.expectedVersion||0)+1};
      if(index>=0)scripts[index]=data;else scripts.push(data);
    }
    else if(path.endsWith('/execute')) { await new Promise(resolve=>setTimeout(resolve,400));data={status:'SUCCEEDED',stdout:'{"total":23,"error_count":5}',stderr:'',exitCode:0,durationMs:72}; }
    else if(path.endsWith('/python/assist')) {
      const body=request.postDataJSON();aiActions.push(body.action);await new Promise(resolve=>setTimeout(resolve,600));
      data={action:body.action,code:['explain','ask'].includes(body.action)?'这个脚本逐行分析日志，汇总错误信息。未执行代码。':body.action==='test'?'import unittest\nclass TestLogs(unittest.TestCase):\n    def test_empty(self):\n        self.assertEqual(0, 0)\n':'print("AI suggestion")\n',replaceSelection:false,modelName:'code-model',appliedSkills:[]};
    }
    await route.fulfill({json:{code:200,message:'OK',data}});
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/w/data-science/dataScienceDevelop`,{waitUntil:'domcontentloaded'});
  await page.locator('.python-studio .monaco-editor').waitFor({timeout:60000});
  await page.locator('.studio-outline-list button').filter({hasText:'analyze_errors()'}).click();
  await page.waitForFunction(()=>document.querySelector('.ds-page').__vueParentComponent.proxy.cursorPosition.lineNumber===10);
  await page.getByRole('button',{name:/问题1/}).count();
  await page.locator('.ide-bottom nav button').filter({hasText:'问题'}).click();
  await page.locator('.studio-problems button').filter({hasText:'F401'}).waitFor();
  await page.locator('.studio-problems button').click();
  assert.equal(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.getPosition().lineNumber),5);
  await page.locator('.studio-global-search').click();
  await page.getByLabel('搜索文件、函数或命令').fill('normalize');
  await page.locator('.studio-palette-results button').first().click();
  await page.locator('.editor-tab.active').filter({hasText:'utils.py'}).waitFor();
  await page.locator('.script-open').filter({hasText:'log_analysis.py'}).click();
  await page.locator('.ds-page').evaluate(el=>{const vm=el.__vueParentComponent.proxy;vm.editor.pushUndoStop();vm.editor.executeEdits('test',[{range:{startLineNumber:1,startColumn:1,endLineNumber:1,endColumn:1},text:'# unsaved edit\n'}]);vm.editor.pushUndoStop();});
  await page.locator('.script-open').filter({hasText:'utils.py'}).click();
  await page.locator('.script-open').filter({hasText:'log_analysis.py'}).click();
  assert.ok(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.getValue().startsWith('# unsaved edit')));
  await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.trigger('test','undo'));
  assert.equal(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.getValue()),source);
  await page.getByRole('button',{name:'运行',exact:true}).hover();
  assert.notEqual(await page.getByRole('button',{name:'运行',exact:true}).evaluate(el=>getComputedStyle(el).backgroundImage),'none','Run keeps a colored hover background');
  await page.getByRole('button',{name:'运行',exact:true}).click();
  await page.getByText('执行成功',{exact:true}).waitFor();
  await page.locator('.ide-bottom nav button').filter({hasText:'输出'}).click();
  assert.ok((await page.locator('.studio-output').textContent()).includes('"total":23'));
  await page.getByRole('button',{name:'代码解释',exact:true}).click();
  await page.getByLabel('Python AI 提示词').fill('解释当前代码');
  await page.getByRole('button',{name:'发送 Python AI 请求'}).hover();
  assert.notEqual(await page.getByRole('button',{name:'发送 Python AI 请求'}).evaluate(el=>getComputedStyle(el).backgroundImage),'none','Send keeps a colored hover background');
  await page.getByRole('button',{name:'发送 Python AI 请求'}).click();
  await page.locator('.studio-text-answer').filter({hasText:'逐行分析日志'}).waitFor();
  assert.equal(await page.locator('.ai-result .apply').count(),0,'Explanations cannot replace source');
  await page.getByRole('button',{name:'生成测试',exact:true}).click();
  await page.getByLabel('Python AI 提示词').fill('生成单元测试');
  await page.getByRole('button',{name:'发送 Python AI 请求'}).click();
  await page.getByRole('button',{name:'审查代码变更',exact:true}).click();
  await page.getByRole('dialog',{name:'审查代码变更'}).waitFor();
  assert.ok(parseInt((await page.locator('.review-added').textContent()).replace('+',''))>0);
  assert.equal(await page.locator('.review-deleted').textContent(),'−0 行删除');
  await page.getByRole('button',{name:'统一视图',exact:true}).click();
  await page.getByRole('button',{name:'新建测试文件',exact:true}).click();
  await page.locator('.editor-tab.active').filter({hasText:'test_log_analysis.py'}).waitFor();
  assert.ok(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editorTabs.find(t=>t.form.id==='log').form.sourceCode.startsWith('#!')));
  await page.locator('.script-open').filter({hasText:'log_analysis.py'}).click();
  await page.getByRole('button',{name:'代码修复',exact:true}).click();
  await page.getByLabel('Python AI 提示词').fill('修复异常处理');
  await page.getByRole('button',{name:'发送 Python AI 请求'}).click();
  await page.getByText('代码修复 · 待审查',{exact:true}).waitFor();
  await page.getByRole('button',{name:'审查代码变更',exact:true}).click();
  await page.getByRole('dialog',{name:'审查代码变更'}).waitFor();
  assert.ok(parseInt((await page.locator('.review-deleted').textContent()).replace('−',''))>0);
  await mkdir('../../target/python-studio-editor',{recursive:true});
  await page.screenshot({path:'../../target/python-studio-editor/python-review.png',fullPage:true});
  await page.getByRole('button',{name:'替换当前脚本',exact:true}).click();
  assert.equal(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.getValue()),'print("AI suggestion")\n');
  await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.trigger('test','undo'));
  assert.equal(await page.locator('.ds-page').evaluate(el=>el.__vueParentComponent.proxy.editor.getValue()),source,'Accepted change is one undoable edit');
  await page.getByRole('button',{name:'代码生成',exact:true}).click();
  await page.getByLabel('Python AI 提示词').fill('生成一个建议');
  await page.getByRole('button',{name:'发送 Python AI 请求'}).click();
  await page.locator('.ai-progress').waitFor();
  await page.locator('.script-open').filter({hasText:'utils.py'}).click();
  await page.getByText('文件或内容已变化，请基于当前版本重新生成，避免覆盖已有编辑。').waitFor();
  assert.equal(await page.locator('.ai-result .apply').isDisabled(),true);
  await page.locator('.script-open').filter({hasText:'log_analysis.py'}).click();
  await page.locator('.ide-bottom nav button').filter({hasText:'console'}).count();
  await page.locator('.ide-bottom nav button').filter({hasText:'运行日志'}).click();
  await mkdir('../../target/python-studio-editor',{recursive:true});
  await page.screenshot({path:'../../target/python-studio-editor/python-studio.png',fullPage:true});
  await page.getByRole('button',{name:'沉浸编辑',exact:true}).click();
  await page.screenshot({path:'../../target/python-studio-editor/python-studio-immersive.png',fullPage:true});
  assert.deepEqual(errors,[]);
  assert.deepEqual(aiActions,['explain','test','fix','generate']);
  console.log('PASS: outline, authorized search, per-file draft and undo, execution/output, read-only explanations, separate test file and stale suggestion protection.');
} finally { await browser?.close(); await server.close(); }
