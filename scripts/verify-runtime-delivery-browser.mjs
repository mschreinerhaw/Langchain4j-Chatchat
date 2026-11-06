// Read-only UI verification against an existing real scenario, never submits execute.
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(process.argv[2]),origin=process.argv[3]??'http://127.0.0.1:28180';
const auth=JSON.parse(await readFile(root+'/private/auth-private.json','utf8'));
const prepared=JSON.parse(await readFile(root+'/private/scenario-prepared-private.json','utf8'));
const chosen=process.argv[4]??prepared.agent.nodeId;
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const context=await browser.newContext({viewport:{width:1600,height:1000}});
await context.addInitScript(value=>{if(window===window.top)localStorage.setItem('chatchat.auth.session',JSON.stringify(value));},auth);
const page=await context.newPage(),errors=[];let executes=0;
page.on('pageerror',error=>errors.push(error.message));
page.on('request',request=>{if(request.method()==='POST'&&request.url().endsWith('/execute'))executes++;});
const proof={readOnly:true,workspaceId:prepared.workspaceId,nodeId:chosen};
try{
 await page.goto(origin+'/index.html#/w/data-exploration/dataExploration?exploration='+prepared.workspaceId+'&node='+chosen);
 const panel=page.locator('[aria-label="分析交付状态"]');await panel.waitFor({timeout:60000});
 if(process.env.RUNTIME_VERIFY_PROGRESS==='1'){
  await page.waitForFunction(()=>document.querySelector('[aria-label="分析交付状态"]')?.textContent.includes('已响应'),{},{timeout:60000});
  const response=await context.request.get(origin+'/api/v1/data-science/explorations/'+prepared.workspaceId+'/nodes/'+chosen+'/status',{headers:{Authorization:'Bearer '+auth.token}});
  assert.ok(response.ok());const value=await response.json();assert.equal(value.code,200);
  const receipt=value.data.nodes.find(node=>node.id===chosen).receipt;
  assert.ok(receipt.progressSnapshotChecked);assert.ok(receipt.analysisEvents.length>0);
  proof.progress={taskId:receipt.taskId,eventCount:receipt.analysisEvents.length,historyStatus:receipt.progressHistoryStatus,readOnlyStatusHydrated:true};
 }
 const text=await panel.textContent();for(const label of ['模型响应','数据供给','Python 执行','报告交付'])assert.ok(text.includes(label));
 const lifecycle=await page.locator('[aria-label="当前分析阶段"]').textContent();
 assert.ok(lifecycle.trim());if(process.argv[5])assert.ok(lifecycle.includes(process.argv[5]));
 const expectedCharts=Number(process.argv[6]??0);
 if(expectedCharts>0){
  const tabs=page.locator('[aria-label="图形分析"] [role="tab"]');
  assert.equal(await tabs.count(),expectedCharts);proof.renderedPlotlyCharts=[];
  for(let index=0;index<expectedCharts;index++){
   await tabs.nth(index).click();
   const chart=page.locator('figure.python-report-chart');await chart.scrollIntoViewIfNeeded();
   const frame=chart.locator('iframe');await frame.waitFor({state:'visible',timeout:60000});
   const content=await (await frame.elementHandle()).contentFrame();
   await content.locator('.js-plotly-plot').waitFor({state:'visible',timeout:60000});
   proof.renderedPlotlyCharts.push({id:await chart.getAttribute('data-chart-id'),title:await frame.getAttribute('title')});
  }
  assert.equal(new Set(proof.renderedPlotlyCharts.map(chart=>chart.id)).size,expectedCharts);
 }
 await mkdir(root+'/browser',{recursive:true});await panel.screenshot({path:root+'/browser/delivery-stages.png'});
 assert.equal(executes,0);assert.deepEqual(errors,[]);
 Object.assign(proof,{status:'PASSED',labels:text,lifecycle,executeRequests:executes,browserErrors:errors});
}catch(error){Object.assign(proof,{status:'FAILED',error:error.message,executeRequests:executes,browserErrors:errors});process.exitCode=1;}
finally{await writeFile(root+'/browser-proof.json',JSON.stringify(proof,null,2));await browser.close();console.log(JSON.stringify(proof));}
