import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import { readFile, mkdir, access } from 'node:fs/promises';
import assert from 'node:assert/strict';
// Real Vue/Monaco in Chromium, HTTP fixtures only. No model or production access.
const manifests={};for(const id of ['zhice','data-science'])manifests[id]=JSON.parse(await readFile(`../src/main/resources/workspace/${id}.json`,'utf8'));
let executablePath;for(const candidate of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']){try{await access(candidate);executablePath=candidate;break;}catch{}}
assert.ok(executablePath,'Local Chromium required');
const server=await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});let browser;
try {
  await server.listen();browser=await chromium.launch({executablePath,headless:true});
  const page=await browser.newPage({viewport:{width:1560,height:980}});const errors=[];page.on('pageerror',error=>{errors.push(error.message);console.error(error.stack);});
  const user={id:'sql-browser-user',username:'admin',tenantId:'sql-browser-tenant',permissionCodes:[]};
  await page.addInitScript(user=>localStorage.setItem('chatchat.auth.session',JSON.stringify({token:'same-fixture-token',user})),user);
  const project={id:'project',name:'Spark customer ETL',dialect:'spark',sessionId:'session',tenantId:user.tenantId,ownerId:user.id};
  let files=[{id:'a',projectId:'project',path:'sql/a.sql',kind:'SQL',content:'SELECT 1;',version:1,editable:true},{id:'b',projectId:'project',path:'sql/b.sql',kind:'SQL',content:'SELECT 2;',version:1,editable:true}];
  let generation=null,task=null,applied=false,submitted=0,uploadBoundary=false;
  const proposed=[{path:'sql/generated.sql',content:'INSERT OVERWRITE TABLE dim_customer SELECT customer_id FROM stg_customer;'}];
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),url=new URL(request.url()),p=url.pathname,method=request.method();let data=[];
    if(p.endsWith('/auth/me'))data=user;
    else if(p.endsWith('/workspaces')||p.endsWith('/workspaces/catalog'))data=Object.values(manifests).map(m=>({...m,defaultWorkspace:m.workspaceId==='zhice'}));
    else if(p.endsWith('/workspaces/preferences'))data={lastWorkspaceId:'data-science'};
    else if(p.endsWith('/manifest')){const manifest=manifests[p.split('/').at(-2)];data={manifest,menus:[{id:'work',title:'Workspace',type:'directory',children:manifest.pages.map(item=>({id:item.menuCode,title:item.id,type:'menu',children:[]}))}]};}
    else if(p==='/api/v1/data-science/sql/projects')data=[project];
    else if(p.endsWith('/sql/reference-files'))data=files.filter(f=>f.kind==='SQL'||f.kind==='REFERENCE');
    else if(p.endsWith('/project/files')){
      if(method==='PUT'){
        const body=request.postDataJSON();const file=files.find(f=>f.path===body.path);assert.equal(body.expectedVersion,file.version);
        Object.assign(file,{content:body.content,version:file.version+1});data=file;
      }else data=files;
    }
    else if(p.endsWith('/project/uploads')){
      uploadBoundary=request.headers()['content-type'].includes('multipart/form-data; boundary=');
      data={id:'mapping',projectId:'project',path:'mapping/customer.csv',kind:'MAPPING',content:'source,target\nstg_customer.customer_id,dim_customer.customer_id',version:1,editable:true};files.push(data);
    }
    else if(p.endsWith('/project/generations')){
      if(method==='POST'){
        const body=request.postDataJSON();assert.equal(body.dialect,'spark');assert.deepEqual(body.mappingIds,['mapping']);assert.ok(body.referenceIds.includes('a'));
        generation={id:'generation',taskId:null,applied:false,prepared:{generationId:'generation',taskRequest:{query:'frozen Mapping and historical SQL context',mode:'agent_chat',sessionId:'session',idempotencyKey:'sql-generation:generation'}}};data=generation.prepared;
      }else data=generation?[generation]:[];
    }
    else if(p==='/api/v1/agent/tasks'&&method==='POST'){submitted++;assert.equal(request.postDataJSON().sessionId,'session');task={taskId:'task',sessionId:'session',status:'SUCCESS'};data=task;}
    else if(p.endsWith('/generation/task')){generation.taskId=request.postDataJSON().taskId;data=generation;}
    else if(p.endsWith('/generation/apply')){
      assert.deepEqual(request.postDataJSON().paths,['sql/generated.sql']);assert.equal(applied,false);applied=true;generation.applied=true;
      data=proposed.map((f,index)=>({...f,id:'generated-'+index,projectId:'project',kind:'SQL',version:1,editable:true,sourceTaskId:'task'}));files.push(...data);
    }
    else if(p.endsWith('/generation'))data={generation,task,events:task?[{sequence:1,type:'ANSWER',status:'SUCCESS'}]:[],files:task?proposed:[],rawAnswer:JSON.stringify({schemaVersion:'sql-studio.output.v1',files:proposed}),outputError:'',validation:'NOT_RUN'};
    await route.fulfill({json:{code:200,message:'Success',data}});
  });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/#/w/data-science/sqlStudio`,{waitUntil:'networkidle'});
  await page.locator('.sql-studio').waitFor();
  const open=path=>page.locator(`.sql-tree-file button[title="${path}"]`).click();
  await open('sql/a.sql');const input=page.locator('.sql-monaco-host textarea.inputarea');await input.waitFor();
  await input.focus();await input.press('Control+A');await input.pressSequentially('select a,b from t');
  await page.getByRole('button',{name:'格式化',exact:true}).click();
  assert.match(await page.locator('.sql-monaco-host .view-lines').innerText(),/SELECT\s+a/);
  await page.getByLabel('编辑器缩进空格').selectOption('4');
  assert.equal(await page.getByLabel('编辑器缩进空格').inputValue(),'4');
  await input.focus();await input.press('Control+A');await input.press('Backspace');await input.pressSequentially('SELECT 42;');await input.press('Escape');
  await open('sql/b.sql');assert.match(await page.locator('.sql-monaco-host .view-lines').innerText(),/SELECT\s+2/);
  await open('sql/a.sql');assert.match(await page.locator('.sql-monaco-host .view-lines').innerText(),/SELECT\s+42/);
  await page.locator('.user-account-trigger').click();
  await page.locator('#workspace-switch').selectOption('zhice');await page.waitForURL('**/#/w/zhice/chat');
  if (await page.locator('.user-account:not([open])').count()) await page.locator('.user-account-trigger').click();
  await page.locator('#workspace-switch').selectOption('data-science');await page.waitForURL('**/#/w/data-science/sqlStudio');
  await page.evaluate(()=>location.hash='/w/data-science/sqlStudio');await page.locator('.sql-studio').waitFor();
  assert.match(await page.locator('.sql-monaco-host .view-lines').innerText(),/SELECT\s+42/);
  await page.getByRole('button',{name:'保存文件',exact:true}).click();await page.getByRole('status').filter({hasText:'文件已保存'}).waitFor();assert.equal(files.find(f=>f.id==='a').content,'SELECT 42;');
  await page.locator('.sql-upload-actions input[type=file]').first().setInputFiles({name:'customer.csv',mimeType:'text/csv',buffer:Buffer.from('source,target\na,b')});
  await page.locator('input[aria-label="关联 Mapping mapping/customer.csv"]').waitFor();assert.equal(uploadBoundary,true);
  assert.match(await page.locator('.sql-monaco-host .view-lines').innerText(),/stg_customer/);
  await page.locator('.sql-reference-picker label').filter({hasText:'sql/a.sql'}).locator('input').check();
  await page.locator('.sql-assistant-prompt textarea').fill('Generate customer ETL using Mapping and historical style.');await page.getByRole('button',{name:'生成 SQL 工程',exact:true}).click();
  await page.locator('.sql-proposed-file button').waitFor();assert.equal(submitted,1);assert.equal(files.some(f=>f.path==='sql/generated.sql'),false);
  await page.locator('.sql-proposed-file button').click();assert.match(await page.locator('.sql-monaco-host .view-lines').innerText(),/INSERT\s+OVERWRITE/);
  await page.getByRole('button',{name:'确认保存生成文件',exact:true}).click();await page.locator('.sql-tree-file button[title="sql/generated.sql"]').waitFor();assert.equal(applied,true);
  await page.reload({waitUntil:'networkidle'});await page.locator('.sql-task-status').waitFor();assert.match(await page.locator('.sql-task-status').innerText(),/SUCCESS/);
  assert.match(await page.locator('.sql-monaco-host .view-lines').innerText(),/INSERT\s+OVERWRITE/);
  const columns=await page.locator('.sql-studio-grid').evaluate(el=>getComputedStyle(el).gridTemplateColumns.split(' ').length);assert.equal(columns,3);
  await mkdir('../../target/sql-studio-audit',{recursive:true});await page.screenshot({path:'../../target/sql-studio-audit/sql-studio.png',fullPage:true});
  assert.deepEqual(errors,[]);console.log('PASS: three columns, Monaco multi-tabs, draft recovery, Spark save, multipart Mapping preview, historical context, original task API, reviewed apply, persisted task recovery (HTTP/model fixtures only).');
} finally {await browser?.close();await server.close();}
