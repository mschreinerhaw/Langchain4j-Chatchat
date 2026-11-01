// Submit through the real Agent management -> conversation UI. No mocked routes or submitted plans.
import {chromium} from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const {E2E_ORIGIN:origin,E2E_AUTH_FILE:authFile,E2E_CASE_FILE:caseFile,E2E_OUTPUT:output}=process.env;
assert.ok(origin&&authFile&&caseFile&&output,'Set E2E_ORIGIN, E2E_AUTH_FILE, E2E_CASE_FILE and E2E_OUTPUT');
const auth=JSON.parse(fs.readFileSync(authFile)),scenario=JSON.parse(fs.readFileSync(caseFile));
const out=path.resolve(output);fs.mkdirSync(out,{recursive:true});
const save=(n,v)=>fs.writeFileSync(path.join(out,n+'.json'),JSON.stringify(v,null,2));
assert.ok(!fs.existsSync(path.join(out,'agent-task.json')),'Case already submitted; preserve it and poll, do not duplicate');
async function api(route){const r=await fetch(origin+'/api/v1'+route,{headers:{Authorization:'Bearer '+auth.token,'X-Workspace-Id':'zhice'}});const j=await r.json();assert.ok(r.ok&&j.code===200,`${route}: ${r.status}/${j.code}`);return j.data;}
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try {
 const context=await browser.newContext({viewport:{width:1680,height:1100}});
 await context.addInitScript(a=>{
  if(window!==window.top)return;
  localStorage.setItem('chatchat.auth.session',JSON.stringify(a));
  window.__sqlChartEvents=[];window.__sqlChartDiagnostics=[];
  window.addEventListener('runtime:report-chart-rendered',e=>window.__sqlChartEvents.push(e.detail));
  window.addEventListener('runtime:report-presentation-diagnostic',e=>window.__sqlChartDiagnostics.push(e.detail));
 },auth);
 const page=await context.newPage();
 await page.goto(origin+'/index.html#/w/zhice/agents');
 await page.getByPlaceholder('名称、场景、标签或工具').fill(scenario.agentName);
 const card=page.locator('.agent-card').filter({has:page.getByRole('heading',{name:scenario.agentName,exact:true})});
 await card.getByRole('button',{name:'设置',exact:true}).click();
 await page.locator('.agent-dialog').waitFor();await page.screenshot({path:path.join(out,'agent-config.png')});
 await page.locator('.agent-dialog').getByRole('button',{name:'取消',exact:true}).click();
 save('agent-config',await api('/agents/workshop/'+scenario.agentId));
 await card.getByRole('button',{name:'对话使用',exact:true}).click();
 const input=page.locator('.composer-box textarea');await input.waitFor();
 if(scenario.visual)await page.getByRole('switch',{name:'图文报告',exact:true}).click();
 const submitted=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/agent/tasks'&&r.request().method()==='POST',{timeout:60000});
 await input.fill(scenario.question);await input.press('Enter');
 const response=await submitted,payload=response.request().postDataJSON(),envelope=await response.json();
 assert.equal(envelope.code,200);assert.equal(payload.agentId,scenario.agentId);assert.equal(payload.query,scenario.question);
 assert.ok(payload.sessionId,'Real UI conversation ID required');
 save('submission',payload);save('agent-task',envelope.data);
 fs.writeFileSync(path.join(out,'request.md'),scenario.question+'\n');
 await page.screenshot({path:path.join(out,'conversation-start.png')});
 let task=envelope.data,lastLog=0;
 const deadline=Date.now()+Number(process.env.E2E_TIMEOUT_MS||1200000);
 while(!['SUCCEEDED','FAILED','PARTIAL','CANCELLED','REJECTED'].includes(task.canonicalState)) {
  assert.ok(Date.now()<deadline,'Task is still running; do not claim pass or silently cancel');
  await new Promise(r=>setTimeout(r,3000));task=await api('/agent/tasks/'+task.taskId+'?tenantId='+auth.user.tenantId);save('agent-result',task);
  if(Date.now()-lastLog>30000){console.log(JSON.stringify({caseId:scenario.caseId,taskId:task.taskId,state:task.canonicalState}));lastLog=Date.now();}
 }
 save('agent-result',task);
 const events=await api('/agent/tasks/'+task.taskId+'/events?tenantId='+auth.user.tenantId+'&limit=1000');save('agent-trace',events);
 const runs=[...new Set(events.flatMap(e=>{try{return [JSON.parse(e.payload).runId].filter(Boolean);}catch{return [];}}))];
 const monitors=[];
 for(const run of runs){try{save('observations-'+run,await api('/agent/runtime/runs/'+run+'/observations?limit=1000'));const monitor=await api('/agent/runtime/runs/'+run+'/coding');monitors.push(monitor);save('coding-'+run,monitor);}catch(error){save('run-unavailable-'+run,{message:error.message});}}
 fs.writeFileSync(path.join(out,'agent-answer.md'),task.answerSummary||'');
 const needle=(task.answerSummary||'').replace(/\\n/g,'\n').split('\n').find(line=>line.trim())?.replace(/^#+\s*/,'').replace(/[*`]/g,'').replace(/\s+/g,'').slice(0,50);
 let answerVisible=false;
 if(needle)try{await page.waitForFunction(n=>document.body.innerText.replace(/\s+/g,'').includes(n),needle,{timeout:20000});answerVisible=true;}catch{}
 const resultEvent=events.filter(e=>(e.eventType||e.type)==='RESULT').at(-1);
 const uiResult=resultEvent?JSON.parse(resultEvent.payload):null;
 const artifact=uiResult?.uiArtifact||uiResult?.uiResponse?.uiArtifact;
 // Structured reports intentionally use their own title/blocks rather than repeating Markdown.
 // Bind the visible report to the exact server-produced artifact, not a guessed title substring.
 if(scenario.visual&&artifact?.artifactId){
  save('ui-result-payload',uiResult);
  const shell=page.locator(`[data-artifact-id="${artifact.artifactId}"]`);
  await shell.waitFor({timeout:60000});
  const actualManifest=await api('/ui-artifacts/'+artifact.artifactId);
  save('ui-artifact-manifest',actualManifest);
  const hasVisualReport=JSON.stringify(actualManifest.spec).includes('"VisualReport"');
  if(hasVisualReport){
   await shell.locator('.report-renderer').waitFor({timeout:60000});
   answerVisible=await shell.locator('.report-renderer').isVisible();
  }else{
   // A visible error is evidence of failure, not a missing report renderer timeout.
   answerVisible=await shell.isVisible()&&Boolean((await shell.innerText()).trim());
  }
 }
 await page.screenshot({path:path.join(out,'conversation-result.png'),fullPage:true});
 save('ui-receipt',{caseId:scenario.caseId,taskId:task.taskId,sessionId:payload.sessionId,agentId:scenario.agentId,state:task.canonicalState,answerVisible,runs,capturedAt:new Date().toISOString()});
 console.log(JSON.stringify({caseId:scenario.caseId,taskId:task.taskId,state:task.canonicalState,answerVisible}));
 assert.equal(answerVisible,true,'Final answer not verified on the actual conversation page');
 if(scenario.visual){
  try {
   assert.ok(artifact?.artifactId,'No actual server-produced report artifact');
   const manifest=await api('/ui-artifacts/'+artifact.artifactId);save('ui-artifact-manifest',manifest);
   const resources=new Set();
   function inspect(value){if(!value||typeof value!=='object')return;if(value.type==='VisualReport'&&value.props?.resourceId)resources.add(value.props.resourceId);Object.values(value).filter(v=>v&&typeof v==='object').forEach(inspect);}
   inspect(manifest.spec);assert.ok(resources.size,'No VisualReport resource in the actual UI artifact');
   let report;
   for(const resource of resources){const value=await api('/ui-artifacts/'+artifact.artifactId+'/resources/'+resource);save('ui-report-resource-'+resource,value);if(value?.schemaVersion==='python_visual_report.v1')report=value;}
   assert.ok(report,'No registered Python visual report');save('visual-report',report);
   assert.equal(report.artifactValidationStatus,'PASSED');
   const execution=report.execution;assert.ok(execution?.operationId&&execution.codeSha256);assert.equal(execution.executorStopped,true);
   const actual=monitors.flatMap(m=>m.tasks||[]).flatMap(t=>t.executions||[]).find(e=>e.executionId===execution.operationId);
   assert.ok(actual,'Missing durable Python Sandbox execution receipt');assert.equal(actual.status,'SUCCEEDED');assert.equal(actual.exitCode,0);assert.equal(actual.stage,'VALIDATED');assert.equal(actual.resultAvailable,true);assert.equal(actual.codeHash,execution.codeSha256);
   const artifacts=[];
   for(const [kind,item]of [['code',execution.codeArtifact],...(report.charts||[]).map(c=>[c.kind,c.artifact])]){
    assert.ok(item?.fileId,'Missing uploaded artifact');
    const response=await fetch(origin+'/api/v1/interactions/user-assets/'+item.fileId+'/content',{headers:{Authorization:'Bearer '+auth.token}});assert.equal(response.status,200);
    const bytes=Buffer.from(await response.arrayBuffer()),sha256=crypto.createHash('sha256').update(bytes).digest('hex');assert.equal(sha256,item.sha256??execution.codeSha256);
    const extension={code:'py',plotly:'json',png:'png',svg:'svg'}[kind]||'artifact';fs.writeFileSync(path.join(out,item.fileId+'.'+extension),bytes);
    artifacts.push({kind,fileId:item.fileId,sha256,bytes:bytes.length});
   }
   save('python-and-artifact-validation',{status:'REAL_PYTHON_AND_UPLOADED_BYTES_VERIFIED',execution,artifacts,businessValidation:report.validation,analysisStatus:report.analysisStatus});
   const charts=page.locator('.python-report-chart');await charts.first().waitFor({timeout:60000});
   const rendered=[];
   for(let i=0;i<await charts.count();i++){
    const chart=charts.nth(i);await chart.scrollIntoViewIfNeeded();
    const id=await chart.getAttribute('data-chart-id');
    await chart.locator('iframe,img').first().waitFor({timeout:60000});
    if(await chart.locator('iframe').count()){
     const frame=await(await chart.locator('iframe').elementHandle()).contentFrame();
     await frame.locator('.main-svg').first().waitFor({timeout:60000});
     rendered.push({id,kind:'plotly',figure:await frame.evaluate(()=>({data:document.getElementById('plot').data,layout:document.getElementById('plot').layout}))});
    }else{
     await page.waitForFunction(index=>{const img=document.querySelectorAll('.python-report-chart')[index]?.querySelector('img');return img?.complete&&img.naturalWidth>0;},i,{timeout:60000});
     rendered.push({id,kind:'static',image:await chart.locator('img').evaluate(img=>({width:img.naturalWidth,height:img.naturalHeight,title:img.alt}))});
    }
    await chart.screenshot({path:path.join(out,'rendered-chart-'+i+'.png')});
   }
   save('chart-rendering',{status:'RENDERED_IN_SYSTEM_PAGE',charts:rendered,...await page.evaluate(()=>({events:window.__sqlChartEvents,diagnostics:window.__sqlChartDiagnostics}))});
  }catch(error){save('chart-rendering',{status:'FAILED',reason:error.message,...await page.evaluate(()=>({events:window.__sqlChartEvents,diagnostics:window.__sqlChartDiagnostics}))});throw error;}
 }
}finally{await browser.close();}
