/** Real deployed exploration/Agent/Native/Silo acceptance; no mocked routes or authored analysis code. */
import assert from 'node:assert/strict';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {resolve,basename} from 'node:path';
import {createHash} from 'node:crypto';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {explorationDeliveryStages} from '../chatchat-api/web-app/src/js/utils/explorationWorkspace.js';
import {executionWorkbench} from '../chatchat-api/web-app/src/js/utils/runtimeObservationPresentation.js';
const origin=process.env.RUNTIME_TEST_ORIGIN||'http://192.168.195.221:18100';
const out=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/native-exploration-live');
await mkdir(out,{recursive:true});
let auth,browser;
const result={passed:false,scope:'REAL_DEPLOYED_EXPLORATION_NATIVE_SILO',startedAt:new Date().toISOString()};
async function api(path,body,raw=false){
 const response=await fetch(origin+'/api/v1'+path,{method:body?'POST':'GET',headers:{...(auth?{Authorization:'Bearer '+auth.token}:{}),'X-Workspace-Id':'data-exploration',...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});
 assert.equal(response.status,200,`${path}: ${response.status}`);
 if(raw)return Buffer.from(await response.arrayBuffer());
 const value=await response.json();assert.equal(value.code,200,`${path}: ${value.message}`);return value.data;
}
try{
 assert.ok(process.env.RUNTIME_TEST_USER&&process.env.RUNTIME_TEST_PASSWORD,'Provide login through environment variables');
 auth=await api('/enterprise/auth/login',{username:process.env.RUNTIME_TEST_USER,password:process.env.RUNTIME_TEST_PASSWORD});
 const options=await api('/data-science/explorations/agents'),agentId=process.env.RUNTIME_TEST_AGENT||'financial-news-agent';
 assert.ok(options.some(a=>a.id===agentId),'Agent must be published and authorized');
 const input=process.env.RUNTIME_TEST_INPUT?await readFile(process.env.RUNTIME_TEST_INPUT):Buffer.from('category,amount\nA,12.5\nB,20\nC,15\n'),form=new FormData();
 form.append('file',new Blob([input],{type:'text/csv'}),process.env.RUNTIME_TEST_INPUT?basename(process.env.RUNTIME_TEST_INPUT):'SYNTHETIC-category-amount.csv');
 const asset=await api('/data-science/explorations/attachments',form);
 assert.equal(asset.sha256,createHash('sha256').update(input).digest('hex'));
 let space=await api('/data-science/explorations',{title:'Native 图文分析与活动记录验证'});
 space=await api(`/data-science/explorations/${space.id}/nodes`,{version:space.version,agentId,fileIds:[asset.id],question:process.env.RUNTIME_TEST_QUESTION||'分析附件中明确标记为 SYNTHETIC 的验收数据。核对行数、amount 总和与均值，比较各类别，给出简洁的分析报告和结构化数值结果。数据没有声明单位，不要推断货币或单位；本次不需要联网。',...(process.env.RUNTIME_TEST_STYLE_PROFILE?{reportPresentation:{styleProfile:process.env.RUNTIME_TEST_STYLE_PROFILE,scenario:process.env.RUNTIME_TEST_STYLE_PROFILE.startsWith('financial-')?'financial-analysis':'general-analysis'}}:{})});
 const nodeId=space.nodes.at(-1).id;result.workspaceId=space.id;result.nodeId=nodeId;result.inputSha256=asset.sha256;
 space=await api(`/data-science/explorations/${space.id}/nodes/${nodeId}/execute`,{});
 const seen=new Map(),deadline=Date.now()+1200000;
 let receipt;
 while(Date.now()<deadline){
  space=await api(`/data-science/explorations/${space.id}/nodes/${nodeId}/status`);
  receipt=space.nodes.find(n=>n.id===nodeId).receipt;
  await writeFile(resolve(out,'progress.json'),JSON.stringify({workspaceId:space.id,nodeId,agentTaskId:receipt.agentTaskId,status:receipt.status,eventCount:receipt.analysisEvents?.length||0}));
  for(const event of receipt.analysisEvents||[])seen.set(event.sequence,event);
  if(receipt.terminal)break;
  await new Promise(r=>setTimeout(r,3000));
 }
 await writeFile(resolve(out,'receipt.json'),JSON.stringify(receipt,null,2));
 const events=[...seen.values()].sort((a,b)=>a.sequence-b.sequence);
 await writeFile(resolve(out,'events.json'),JSON.stringify(events,null,2));
 assert.ok(receipt.terminal&&receipt.resultAvailable,'Task must finish with a registered deliverable');
 const types=events.map(e=>e.payload?.metadata?.workerActivity?.normalizedType).filter(Boolean);
 for(const required of ['MODEL_REQUEST_STARTED','MODEL_REQUEST_COMPLETED','TOOL_COMPLETED','COMMAND_STARTED','COMMAND_COMPLETED'])assert.ok(types.includes(required),`Missing real ${required}`);
 const chart=receipt.visualReport?.charts?.find(c=>c.kind==='png');assert.ok(chart?.artifact?.fileId,'Need a registered actual PNG chart');
 const bytes=await api('/interactions/user-assets/'+chart.artifact.fileId+'/content',null,true);
 assert.equal(createHash('sha256').update(bytes).digest('hex'),chart.artifact.sha256);
 await writeFile(resolve(out,'real-chart.png'),bytes);
 if(process.env.RUNTIME_TEST_STYLE_PROFILE){
  assert.equal(receipt.visualReport.presentation.styleProfile,process.env.RUNTIME_TEST_STYLE_PROFILE);
  const charts=[];let i=0;
  for(const item of receipt.visualReport.charts.filter(c=>c.kind==='png')){
   const data=await api('/interactions/user-assets/'+item.artifact.fileId+'/content',null,true);
   assert.equal(createHash('sha256').update(data).digest('hex'),item.artifact.sha256);
   const path=`chart-${++i}.png`;await writeFile(resolve(out,path),data);charts.push({path,title:item.title,sha256:item.artifact.sha256});
  }
  result.styleProfile=process.env.RUNTIME_TEST_STYLE_PROFILE;result.charts=charts;
  const code=[];
  for(const taskId of new Set(events.map(e=>e.payload?.metadata?.taskId).filter(Boolean))){
   const files=await api('/interactions/user-assets?kind=CODE&personalOnly=false&taskId='+encodeURIComponent(taskId)+'&limit=100');
   for(const file of files){const data=await api('/interactions/user-assets/'+file.id+'/content',null,true);assert.equal(createHash('sha256').update(data).digest('hex'),file.sha256);const path='source-'+code.length+'-'+basename(file.fileName);await writeFile(resolve(out,path),data);code.push({path,fileName:file.fileName,sha256:file.sha256});}
  }
  result.sourceFiles=code;
 }
 const stages=explorationDeliveryStages(events,receipt),workbench=executionWorkbench(events,{canonicalState:receipt.status,createTime:receipt.analysisStartedAt,finishedAt:receipt.analysisFinishedAt});
 assert.equal(stages.find(s=>s.key==='model').value,'已响应');assert.equal(stages.find(s=>s.key==='execution').value,'进程已结束');
 assert.ok(workbench.visibleActivities.length>0);assert.ok(workbench.visibleActivities.some(a=>a.title.includes('分析计算')));
 const restored=await api('/data-science/explorations/'+space.id),saved=restored.nodes.find(n=>n.id===nodeId).receipt;
 assert.deepEqual(saved.visualReport,receipt.visualReport);assert.deepEqual(saved.analysisEvents,receipt.analysisEvents);
 browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
 const context=await browser.newContext({viewport:{width:1536,height:1100}});await context.addInitScript(value=>localStorage.setItem('chatchat.auth.session',JSON.stringify(value)),auth);
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 const url=origin+'/index.html#/w/data-exploration/dataExploration?exploration='+space.id+'&node='+nodeId;
 await page.goto(url);await page.locator('.exploration-chart-card img').waitFor({timeout:90000});
 await page.waitForFunction(()=>[...document.querySelectorAll('.exploration-chart-card img')].some(img=>img.naturalWidth>0));
 const toggle=page.locator('.analysis-activity-toggle');if(await toggle.getAttribute('aria-expanded')!=='true')await toggle.click();
 await page.locator('.analysis-event-table tbody tr').first().waitFor();
 assert.match(await page.locator('[aria-label="分析交付状态"]').textContent(),/已响应/);
 await page.screenshot({path:resolve(out,'exploration.png'),fullPage:true});
 await page.reload();await page.locator('.exploration-chart-card img').waitFor({timeout:60000});
 await page.waitForFunction(()=>[...document.querySelectorAll('.exploration-chart-card img')].some(img=>img.naturalWidth>0));
 assert.deepEqual(errors,[]);
 Object.assign(result,{passed:true,agentTaskId:receipt.agentTaskId,chartSha256:chart.artifact.sha256,eventCount:events.length,nativeTypes:[...new Set(types)],stages,actualProductionPage:true,refreshRetainsChartAndEvents:true,pageErrors:errors});
}catch(error){result.error=error.message;throw error;}
finally{await browser?.close();result.finishedAt=new Date().toISOString();await writeFile(resolve(out,'verification.json'),JSON.stringify(result,null,2));}
