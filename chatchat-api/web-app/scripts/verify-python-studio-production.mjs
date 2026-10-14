import { chromium, request } from 'playwright-core';
import { access, mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const base=process.env.PYTHON_STUDIO_DEPLOY_BASE,username=process.env.PYTHON_STUDIO_DEPLOY_USER,password=process.env.PYTHON_STUDIO_DEPLOY_PASSWORD;
assert.ok(base&&username&&password,'Supply deployment URL and credentials through environment variables');
const output='../../target/python-studio-deploy'; await mkdir(output,{recursive:true});
const http=await request.newContext();let browser,headers,script;
async function api(path,options={}) {
  const response=await http.fetch(`${base}/api/v1${path}`,{headers,...options});
  assert.equal(response.status(),200,`${path}: ${await response.text()}`);
  const body=await response.json();assert.equal(body.code,200,body.message);return body.data;
}
try {
  const session=await api('/enterprise/auth/login',{method:'POST',data:{username,password}});
  headers={Authorization:`Bearer ${session.token}`,'X-Workspace-Id':'data-science'};
  const workbench=await api('/data-science/python/workbench');
  const asset=workbench.assets.find(item=>item.status==='READY');assert.ok(asset,'Existing READY Python environment required');
  const source='import json\n\ndef summarize(values):\n    return {"total": len(values), "sum": sum(values)}\n\nprint(json.dumps(summarize([1, 2, 3])))\n';
  script=await api('/data-science/python/scripts',{method:'POST',data:{assetId:asset.id,fileName:`ide_smoke_${Date.now()}.py`,title:'Temporary Python Studio UI verification',sourceCode:source,status:'DRAFT',expectedVersion:0}});
  let executablePath;
  for(const path of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']) {try{await access(path);executablePath=path;break;}catch{}}
  assert.ok(executablePath);browser=await chromium.launch({executablePath,headless:true,args:['--no-proxy-server']});
  const page=await browser.newPage({viewport:{width:1600,height:1000}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(({session,script})=>{
    localStorage.setItem('chatchat.auth.session',JSON.stringify(session));
    const owner=[session.user.tenantId,session.user.id,'data-science'].map(encodeURIComponent).join(':');
    const key=`script-${script.id}`;
    localStorage.setItem(`chatchat.pythonDraft.v1:${owner}`,JSON.stringify({version:1,value:{tabs:[{key,form:script,parametersText:'{}',inputSchemaText:'{"type":"object","properties":{},"required":[]}'}],activeKey:key}}));
    localStorage.setItem(`chatchat.python-studio.layout.v1:${owner}`,JSON.stringify({explorer:220,ai:360,bottom:210,explorerOpen:true,aiOpen:true,bottomOpen:true}));
  },{session,script});
  await page.goto(`${base}/#/w/data-science/dataScienceDevelop`,{waitUntil:'domcontentloaded'});
  await page.locator('.editor-tab.active').filter({hasText:script.fileName}).waitFor({timeout:45000});
  await page.locator('.python-studio .monaco-editor').waitFor({timeout:45000});
  await page.locator('.studio-outline-list button').filter({hasText:'summarize()'}).click();
  await page.getByRole('button',{name:'运行',exact:true}).hover();
  assert.notEqual(await page.getByRole('button',{name:'运行',exact:true}).evaluate(el=>getComputedStyle(el).backgroundImage),'none');
  await page.getByRole('button',{name:'运行',exact:true}).click();
  await page.getByText('执行成功',{exact:true}).waitFor({timeout:90000});
  await page.locator('.ide-bottom nav button').filter({hasText:'输出'}).click();
  const stdout=await page.locator('.studio-output').textContent();assert.ok(stdout.includes('"sum": 6'),stdout);
  await page.locator('.studio-global-search').click();
  await page.getByLabel('搜索文件、函数或命令').fill('summarize');
  await page.locator('.studio-palette-results button').filter({hasText:script.fileName}).first().click();
  await page.getByRole('button',{name:'代码解释',exact:true}).click();
  await page.getByLabel('Python AI 提示词').fill('用中文简要解释当前脚本的 summarize 函数、输入输出；不要执行任何代码。');
  await page.getByRole('button',{name:'发送 Python AI 请求'}).hover();
  assert.notEqual(await page.getByRole('button',{name:'发送 Python AI 请求'}).evaluate(el=>getComputedStyle(el).backgroundImage),'none');
  await page.getByRole('button',{name:'发送 Python AI 请求'}).click();
  await page.locator('.studio-text-answer').waitFor({timeout:120000});
  const answer=await page.locator('.studio-text-answer').textContent();assert.ok(answer.length>15);
  assert.equal(await page.locator('.ai-result .apply').count(),0);
  const current=await api('/data-science/python/workbench');
  assert.equal(current.scripts.find(item=>item.id===script.id).sourceCode,source,'AI explanation leaves the saved script unchanged');
  await page.screenshot({path:`${output}/python-studio-production.png`,fullPage:true});
  await page.getByRole('button',{name:'代码修复',exact:true}).click();
  await page.getByLabel('Python AI 提示词').fill('修复 summarize(values)：先过滤 None，total 和 sum 都只计算剩余数字。保留 import、函数名称和 print 示例。返回完整脚本，不运行代码。');
  await page.getByRole('button',{name:'发送 Python AI 请求'}).click();
  await page.getByRole('button',{name:'审查代码变更',exact:true}).waitFor({timeout:120000});
  await page.getByRole('button',{name:'审查代码变更',exact:true}).click();
  await page.getByRole('dialog',{name:'审查代码变更'}).waitFor();
  assert.ok(parseInt((await page.locator('.review-added').textContent()).replace('+',''))>0);
  assert.ok(parseInt((await page.locator('.review-deleted').textContent()).replace('−',''))>0);
  await page.screenshot({path:`${output}/python-studio-production-review.png`,fullPage:true});
  await page.getByRole('button',{name:'替换当前脚本',exact:true}).click();
  const renderedSource=async()=>await page.locator('.monaco-host .view-line').evaluateAll(lines=>lines.map(line=>line.textContent.replace(/\u00a0/g,' ').replace(/\u200b/g,'').trimEnd()).join('\n').trimEnd());
  assert.notEqual(await renderedSource(),source.trimEnd());
  assert.equal((await api('/data-science/python/workbench')).scripts.find(item=>item.id===script.id).sourceCode,source,'Review apply stays in unsaved editor draft');
  await page.locator('.monaco-host .view-lines').click();
  await page.keyboard.press('Control+z');
  await page.waitForTimeout(150);
  assert.equal(await renderedSource(),source.trimEnd());
  await page.getByRole('button',{name:'沉浸编辑',exact:true}).click();
  const stable=await page.evaluate(async()=>{
    const samples=[];for(let i=0;i<90;i++){await new Promise(requestAnimationFrame);samples.push([...document.querySelectorAll('.python-studio,.python-studio .ide-main,.python-studio .monaco-host')].map(el=>{const r=el.getBoundingClientRect();return [r.x,r.y,r.width,r.height];}));}
    return new Set(samples.map(JSON.stringify)).size;
  });
  assert.equal(stable,1);assert.deepEqual(errors,[]);
  await page.screenshot({path:`${output}/python-studio-production-immersive.png`,fullPage:true});
  const result={execution:'SUCCEEDED',outputContainsSum6:true,explanationReadOnly:true,repairDiffAndUndo:true,hoverContrast:true,outlineAndSearch:'PASS',stableFrames:90,browserErrors:errors,temporaryScriptId:script.id};
  await writeFile(`${output}/verification.json`,JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
} finally {
  await browser?.close();
  if(script) {await api(`/data-science/python/scripts/${script.id}`,{method:'DELETE'});console.log('Temporary Python script deleted; execution audit retained.');}
  await http.dispose();
}
