/** Real authenticated exploration rendering and reconnection. No mocked routes or execution. */
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const {chromium}=await import(new URL('../chatchat-api/web-app/node_modules/playwright-core/index.mjs',import.meta.url));
const args=Object.fromEntries(Array.from({length:(process.argv.length-2)/2},(_,i)=>[process.argv[2+i*2].replace(/^--/,''),process.argv[3+i*2]]));
const auth=JSON.parse(await readFile(args.auth,'utf8')),cases=JSON.parse(await readFile(args.cases,'utf8'));
const output=resolve(args.output);await mkdir(output,{recursive:true});
const hash=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
async function api(path){const r=await fetch(args.origin+'/api/v1'+path,{headers:{Authorization:'Bearer '+auth.token,'X-Workspace-Id':'data-exploration'}});assert(r.ok);const j=await r.json();assert.equal(j.code,200);return j.data;}
const browser=await chromium.launch({executablePath:args.chrome||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const evidence={scope:'REAL_AUTHENTICATED_BROWSER_NO_MOCKS',cases:[],pageErrors:[],executions:0};
try{
 const context=await browser.newContext({viewport:{width:1600,height:1000}});
 await context.addInitScript(({session,origin})=>{
  if(window===window.top&&location.origin===origin)localStorage.setItem('chatchat.auth.session',JSON.stringify(session));
 },{session:auth,origin:new URL(args.origin).origin});
 const page=await context.newPage();page.on('pageerror',e=>evidence.pageErrors.push(e.message));
 page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/execute'))evidence.executions++;});
 let previousWorkspaceId=null;
 for(const item of cases){
  const path='/data-science/explorations/'+item.workspaceId;
  const before=await api(path),node=before.nodes.find(n=>n.id===item.nodeId);assert(node);
  const url=args.origin+'/index.html#/w/data-exploration/dataExploration?exploration='+item.workspaceId+'&node='+item.nodeId;
  // Each workspace is an independent external deep-link entry. Within a workspace,
  // retain the mounted component and exercise actual node selection below.
  if(previousWorkspaceId&&previousWorkspaceId!==item.workspaceId)await page.goto('about:blank');
  previousWorkspaceId=item.workspaceId;
  await page.goto(url);await page.locator('.exploration-workspace').waitFor({timeout:60000});
  await page.locator(`[data-node-id="${item.nodeId}"]`).waitFor({timeout:60000});
  await page.locator(`[data-node-id="${item.nodeId}"]`).click();
  if(item.expectedStatusText)await page.getByText(item.expectedStatusText,{exact:false}).first().waitFor();
  const receipt=node.receipt||{},record={case:item.case,workspaceId:item.workspaceId,nodeId:item.nodeId,taskId:receipt.taskId||receipt.agentTaskId,apiStatus:receipt.status,renderedCharts:[]};
  if(item.expectedFailure){
   await page.getByText('预算已耗尽',{exact:false}).first().waitFor();
   assert.equal(await page.locator('.exploration-chart-card').count(),0);
   record.budgetFailureVisible=true;record.noBorrowedChart=true;
  }else{
   const charts=receipt.visualReport?.charts||[];
   for(let i=0;i<charts.length;i++){
    if(charts.length>1)await page.getByRole('tab').nth(i).click();
    const chart=charts[i],figure=page.locator(`figure[data-chart-id="${chart.id}"]`);
    await figure.waitFor({timeout:60000});
    if(chart.kind==='unavailable'){
     await figure.getByRole('alert').waitFor();record.renderedCharts.push({id:chart.id,kind:chart.kind,rejectionVisible:true});continue;
    }
    await page.waitForFunction(id=>document.querySelector(`figure[data-chart-id="${id}"]`)?.getAttribute('aria-busy')==='false',chart.id,{timeout:60000});
    if(chart.kind==='plotly'){
     const frame=await (await figure.locator('iframe').elementHandle()).contentFrame();
     await frame.locator('.main-svg').first().waitFor({timeout:60000});
    }else assert(await figure.locator('img').evaluate(img=>img.complete&&img.naturalWidth>0));
    assert.equal(await figure.getByRole('alert').count(),0);
    record.renderedCharts.push({id:chart.id,kind:chart.kind,rendered:true,fileId:chart.artifact.fileId});
   }
  }
  await page.screenshot({path:resolve(output,item.case+'.png'),fullPage:true});
  await context.setOffline(true);await page.waitForTimeout(item.expectedInProgress?1700:250);await context.setOffline(false);await page.reload();
  await page.locator('.exploration-workspace').waitFor({timeout:60000});
  await page.locator(`[data-node-id="${item.nodeId}"]`).waitFor({timeout:60000});
  if(item.expectedStatusText)await page.getByText(item.expectedStatusText,{exact:false}).first().waitFor();
  if(item.expectedFailure)await page.getByText('预算已耗尽',{exact:false}).first().waitFor();
  else if(receipt.visualReport?.charts?.length)await page.locator('.exploration-chart-card figure').waitFor({timeout:60000});
  const after=await api(path),restored=after.nodes.find(n=>n.id===item.nodeId);
  if(item.expectedInProgress){
   assert.equal(restored.receipt?.taskId,receipt.taskId);
   assert(Number(restored.receipt?.eventCursor||0)>=Number(receipt.eventCursor||0));
   record.sameTaskAndMonotonicCursor=true;
  }else{record.receiptUnchanged=hash(restored.receipt)===hash(receipt);assert(record.receiptUnchanged);}
  record.offlineReconnectAndRefresh=true;evidence.cases.push(record);
 }
 assert.equal(evidence.executions,0);assert.deepEqual(evidence.pageErrors,[]);evidence.status='PASS';
}catch(error){evidence.status='FAIL';evidence.error=error.message;throw error;}
finally{await writeFile(resolve(output,'browser-public.json'),JSON.stringify(evidence,null,2)+'\n');await browser.close();}
