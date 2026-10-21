// Real deployed SQL Studio, user-space uploads, accepted plan, Cline and SQLGlot. No HTTP fixtures.
import {chromium} from 'playwright-core';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import path from 'node:path';
const [authFile,base,evidence]=process.argv.slice(2);
const auth=JSON.parse(await readFile(authFile,'utf8'));await mkdir(evidence,{recursive:true});
let executablePath;
for(const file of ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']){try{await access(file);executablePath=file;break;}catch{}}
const browser=await chromium.launch({executablePath,headless:true});
const uploads=[],errors=[];let prepared,requestBody,projectId;
try {
  const page=await browser.newPage({viewport:{width:1680,height:1080}});
  page.on('pageerror',error=>errors.push(error.message));
  page.on('response',async response=>{
    const url=new URL(response.url()).pathname;
    if(url==='/api/v1/data-science/user-assets'&&response.request().method()==='POST'&&response.ok())uploads.push((await response.json()).data);
  });
  await page.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),auth);
  await page.goto(base+'/#/w/data-science/sqlStudio',{waitUntil:'domcontentloaded'});
  const header=page.locator('.sql-studio-bar');await header.waitFor({timeout:40000});
  await header.getByRole('button',{name:'新建工程',exact:true}).click();
  const dialog=page.locator('.sql-dialog-backdrop form').filter({has:page.getByRole('heading',{name:'新建 SQL 工程'})});
  await dialog.getByLabel('工程名称').fill('用户空间文档验收 '+Date.now());
  const projectCreated=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/v1/data-science/sql/projects'&&response.request().method()==='POST');
  await dialog.getByRole('button',{name:'创建工程',exact:true}).click();
  projectId=(await (await projectCreated).json()).data.id;
  await writeFile(path.join(evidence,'created-project.json'),JSON.stringify({projectId}));
  const documents=page.locator('.sql-user-documents');await documents.waitFor({timeout:30000});
  assert.equal(await page.getByText('已关联 SQL 案例',{exact:false}).count(),0);
  await header.getByLabel('SQL 方言').selectOption('postgresql');
  const assistant=page.locator('.sql-assistant-panel');await assistant.locator('.sql-chat-settings').evaluate(element=>element.open=true);
  await assistant.locator('.sql-execution-modes').getByRole('button',{name:'模型',exact:true}).click();
  await assistant.locator('.sql-agent-choice').filter({hasText:/^模型/}).locator('select').selectOption('qwen3.7-plus');
  const marker='policy_'+randomUUID().replaceAll('-','').slice(0,10);
  const ruleName=`reference-rules-${marker}.md`,factName=`analysis-definitions-${marker}.json`;
  const rule=Buffer.from(`# SQL 参考规则\n生成 PostgreSQL 聚合查询，禁止 SELECT *。输出必须包含字符串常量 '${marker}' AS reference_policy。金额按币种分组。notes 必须说明所引用文件的名称及 SHA-256。\n`);
  const facts=Buffer.from(JSON.stringify({table:'sales_fact',columns:{currency:'币种',gross_amount:'销售原额',discount_amount:'折扣额，可空'},net_sales:'SUM(gross_amount - COALESCE(discount_amount, 0))',grain:'每币种一行'},null,2));
  await documents.getByLabel('上传用户空间文档').setInputFiles([{name:ruleName,mimeType:'text/markdown',buffer:rule},{name:factName,mimeType:'application/json',buffer:facts}]);
  await documents.getByLabel('引用文档 '+ruleName,{exact:true}).waitFor({timeout:30000});
  await documents.getByLabel('文档用途 '+factName,{exact:true}).waitFor({timeout:30000});
  await documents.getByLabel('文档用途 '+factName,{exact:true}).selectOption('EVIDENCE');
  assert.equal(await documents.locator('input[type=checkbox]:checked').count(),2);
  assert.ok((await assistant.locator('.sql-chat-context').textContent()).includes('2 文档'));
  assert.ok((await assistant.locator('.sql-chat-context').textContent()).includes('参考规则'));
  assert.ok((await assistant.locator('.sql-chat-context').textContent()).includes('分析依据'));
  await header.getByLabel('SQL 方言').selectOption('postgresql');
  await page.screenshot({path:path.join(evidence,'sql-user-documents-before-generation.png'),fullPage:true});
  const prepareResponse=page.waitForResponse(response=>new URL(response.url()).pathname===`/api/v1/data-science/sql/projects/${projectId}/generations`&&response.request().method()==='POST',{timeout:180000});
  await assistant.getByLabel('发送给 AI 工程助手').fill('请根据选中的分析依据计算每个币种的净销售额，并严格遵循参考规则，生成一个完整的 PostgreSQL SQL 文件；请说明假设和引用来源。');
  await assistant.getByRole('button',{name:'发送',exact:true}).click();
  const response=await prepareResponse;assert.equal(response.status(),200);requestBody=response.request().postDataJSON();prepared=(await response.json()).data;
  assert.deepEqual(requestBody.userDocuments.map(item=>item.purpose),['RULES','EVIDENCE']);
  assert.equal(requestBody.referenceIds.length,0);assert.equal(requestBody.modelName,'qwen3.7-plus');
  assert.equal(requestBody.dialect,'postgresql');
  const frozen=prepared.taskRequest.toolInput.sqlUserDocuments;
  assert.equal(frozen.length,2);assert.ok(prepared.taskRequest.query.includes(marker));
  for(const [name,bytes] of [[ruleName,rule],[factName,facts]]){
    const original=uploads.find(file=>file.fileName===name);assert.ok(original);
    assert.equal(original.sha256,createHash('sha256').update(bytes).digest('hex'));
    assert.equal(frozen.find(file=>file.fileId===original.id).sha256,original.sha256);
  }
  const headers={Authorization:`Bearer ${auth.token}`,'X-Workspace-Id':'data-science'};
  let result;const until=Date.now()+900000;
  while(Date.now()<until){
    const current=await page.request.get(base+`/api/v1/data-science/sql/projects/${projectId}/generations/${prepared.generationId}`,{headers});
    assert.equal(current.status(),200);result=(await current.json()).data;
    if(result.task&&['SUCCESS','FAILED','CANCELLED','REJECTED'].includes(result.task.status))break;
    await new Promise(resolve=>setTimeout(resolve,2000));
  }
  await writeFile(path.join(evidence,'result-private.json'),JSON.stringify(result,null,2));
  assert.equal(result?.task?.status,'SUCCESS');assert.ok(result.files.length);
  const source=result.files.map(file=>file.content).join('\n');
  assert.ok(source.includes(marker));assert.match(source,/sales_fact/i);assert.match(source,/gross_amount/i);assert.match(source,/discount_amount/i);assert.match(source,/GROUP\s+BY/i);assert.doesNotMatch(source,/SELECT\s+\*/i);
  assert.ok(result.validation.includes('PARSED'));assert.ok(result.validation.includes('SQL_NOT_EXECUTED'));
  assert.equal(result.generation.applied,false);assert.deepEqual(errors,[]);
  // The original files are user assets, not imported ds_sql_file copies.
  const projectFiles=(await (await page.request.get(base+`/api/v1/data-science/sql/projects/${projectId}/files`,{headers})).json()).data;
  assert.equal(projectFiles.length,0);
  const ruleFile=uploads.find(file=>file.fileName===ruleName);
  await documents.getByLabel('引用文档 '+ruleName,{exact:true}).uncheck();
  assert.equal((await page.request.get(base+`/api/v1/data-science/user-assets/${ruleFile.id}`,{headers})).status(),200);
  await page.screenshot({path:path.join(evidence,'sql-user-documents-generated.png'),fullPage:true});
  const report={status:'PASSED',projectId,generationId:prepared.generationId,taskId:result.task.taskId,marker,
    uploads:uploads.map(file=>({id:file.id,fileName:file.fileName,sha256:file.sha256,size:file.size})),
    purposes:requestBody.userDocuments.map(item=>item.purpose),selectedDocumentsFrozen:true,originalHashesMatch:true,
    ruleMarkerApplied:true,evidenceDefinitionsApplied:true,sqlParsed:true,sqlExecuted:false,filesApplied:false,
    noProjectDocumentCopies:true,unselectRetainsOriginal:true,proposedPaths:result.files.map(file=>file.path),pageErrors:errors};
  await writeFile(path.join(evidence,'browser-acceptance.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));
} finally{await browser.close();}
