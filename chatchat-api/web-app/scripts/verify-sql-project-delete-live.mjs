// Delete only the temporary regression project named in TASK_FILE, using the real UI and APIs.
import {chromium} from 'playwright-core';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const [authFile,base,output,taskFile]=process.argv.slice(2);
if(!taskFile)throw new Error('AUTH_FILE BASE_URL OUTPUT TASK_FILE required');
const auth=JSON.parse(await readFile(authFile,'utf8')),task=JSON.parse(await readFile(taskFile,'utf8'));
await mkdir(output,{recursive:true});
let executablePath;
for(const path of [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','/usr/bin/chromium'].filter(Boolean)){
  try{await access(path);executablePath=path;break;}catch{}
}
const browser=await chromium.launch({executablePath,headless:true});let page;
const errors=[],failures=[];let deleted=false,stalePolls=0;
try{
  page=await browser.newPage({viewport:{width:1560,height:1000}});
  page.on('pageerror',error=>errors.push(error.message));
  page.on('response',response=>{if(response.status()>=400)failures.push({path:new URL(response.url()).pathname,status:response.status()});});
  page.on('request',request=>{if(deleted&&new URL(request.url()).pathname.includes(`/projects/${task.projectId}/generations/`))stalePolls++;});
  await page.addInitScript(value=>{if(window===window.top)localStorage.setItem('chatchat.auth.session',JSON.stringify(value));},auth);
  const root=base+'/api/v1/data-science/sql/projects',headers={Authorization:'Bearer '+auth.token};
  const beforeResponse=await page.request.get(root,{headers});assert.equal(beforeResponse.status(),200);
  const before=(await beforeResponse.json()).data;
  assert.ok(before.some(project=>project.id===task.projectId&&project.name.startsWith('Deletion regression ')),'Only the disposable regression project may be deleted');
  const resultResponse=await page.request.get(`${root}/${task.projectId}/generations/${task.generationId}`,{headers});assert.equal(resultResponse.status(),200);
  const result=(await resultResponse.json()).data;assert.equal(result.task.status,'SUCCESS');assert.equal(result.task.taskId,task.taskId);assert.ok(result.files.length>0);
  await page.goto(base+'/#/w/data-science/sqlStudio',{waitUntil:'domcontentloaded'});
  const selection=page.getByRole('combobox',{name:'SQL 工程'});await selection.waitFor({timeout:60000});
  await page.waitForFunction(id=>[...document.querySelectorAll('select[aria-label="SQL 工程"] option')].some(option=>option.value===id),task.projectId);
  if(await selection.inputValue()!==task.projectId)await selection.selectOption(task.projectId);
  await page.getByRole('button',{name:`审查 ${result.files.length} 个文件变更`}).waitFor({timeout:30000});
  const responseReady=page.waitForResponse(response=>response.request().method()==='DELETE'&&new URL(response.url()).pathname===`/api/v1/data-science/sql/projects/${task.projectId}`);
  await page.getByRole('button',{name:'删除工程',exact:true}).click();
  await page.getByRole('dialog').getByRole('button',{name:'确认删除',exact:true}).click();
  const response=await responseReady;assert.equal(response.status(),200);deleted=true;
  await page.waitForFunction(id=>{const selection=document.querySelector('select[aria-label="SQL 工程"]');return selection?.value!==id&&!selection?.disabled;},task.projectId);
  assert.equal(await selection.locator(`option[value="${task.projectId}"]`).count(),0);
  for(const candidate of result.files)assert.ok(!(await page.locator('.sql-assistant-panel').innerText()).includes(candidate.path),'Deleted project proposal must not remain in the assistant');
  assert.equal(await page.getByText('工程已删除',{exact:true}).count(),1);
  assert.ok(!(await page.locator('.sql-studio').innerText()).includes('Task not found'));
  await page.waitForTimeout(2000);assert.equal(stalePolls,0);
  const afterResponse=await page.request.get(root,{headers});assert.equal(afterResponse.status(),200);
  const after=(await afterResponse.json()).data;
  assert.deepEqual(after.map(project=>project.id).sort(),before.filter(project=>project.id!==task.projectId).map(project=>project.id).sort());
  assert.deepEqual(errors,[]);assert.deepEqual(failures,[]);
  await page.screenshot({path:resolve(output,'deleted-project.png')});
  const proof={status:'PASSED',realDeployment:true,httpFixtures:false,realClineGeneration:true,projectId:task.projectId,taskId:task.taskId,
    deleteHttpStatus:response.status(),otherProjectsPreserved:true,deletedTaskResultCleared:true,staleGenerationPolls:stalePolls,pageErrors:errors,httpFailures:failures};
  await writeFile(resolve(output,'acceptance.json'),JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));
}catch(error){await writeFile(resolve(output,'failure-private.json'),JSON.stringify({error:error.message,deleted,errors,failures,body:page?await page.locator('body').innerText():''},null,2));throw error;}
finally{await browser.close();}
