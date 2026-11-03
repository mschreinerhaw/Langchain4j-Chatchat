import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const base=process.env.CHATCHAT_STAGE_URL||'http://192.168.195.221:18100';
const output=process.env.CHATCHAT_BROWSER_OUTPUT||'target/exploration-v2/browser';mkdirSync(output,{recursive:true});
const baseline=JSON.parse(readFileSync('target/exploration-v2/baseline-workspace.json','utf8'));
const root=baseline.nodes[0],cancelled=baseline.nodes.find(n=>n.receipt?.status==='CANCELLED'||n.status==='CANCELLED');
assert.ok(process.env.CHATCHAT_LOGIN_PASSWORD);assert.ok(cancelled);
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
let context,changed=false,initialTypes,chartId,headers;
async function restorePresentation(){
 if(!changed||!headers||!chartId)return;
 const latest=await (await context.request.get(base+`/api/v1/data-science/explorations/${baseline.id}`,{headers})).json();const node=latest.data.nodes.find(n=>n.id===root.id);
 if(node.chartTypes[chartId]==='donut'){const types={...node.chartTypes};if(initialTypes[chartId])types[chartId]=initialTypes[chartId];else delete types[chartId];const restored=await (await context.request.put(base+`/api/v1/data-science/explorations/${baseline.id}/nodes/${root.id}`,{headers,data:{version:latest.data.version,saved:node.saved,chartTypes:types}})).json();assert.equal(restored.code,200);}changed=false;
}
try{
 context=await browser.newContext({viewport:{width:1600,height:1000},locale:'zh-CN'});
 const login=await (await context.request.post(base+'/api/v1/enterprise/auth/login',{data:{username:'admin',password:process.env.CHATCHAT_LOGIN_PASSWORD}})).json();assert.ok(login.data?.token);
 headers={Authorization:'Bearer '+login.data.token};
 await context.addInitScript(s=>{if(window===window.top)localStorage.setItem('chatchat.auth.session',JSON.stringify(s));},login.data);
 const page=await context.newPage();page.setDefaultTimeout(60000);const errors=[],executions=[];
 page.on('pageerror',error=>errors.push(error.message));
 page.on('request',r=>{if(r.method()==='POST'&&/\/(?:nodes|execute|agent\/tasks)$/.test(new URL(r.url()).pathname))executions.push(new URL(r.url()).pathname);});
 await page.goto(base+`/#/w/data-science/dataExploration?exploration=${baseline.id}&node=${cancelled.id}`,{waitUntil:'domcontentloaded'});
 await page.locator('.exploration-node-state[data-state=CANCELLED]').waitFor();
 await page.locator('.exploration-chart-card iframe').waitFor();
 assert.ok((await page.locator('.exploration-node-state').innerText()).includes('画布保留'));
 await page.screenshot({path:output+'/cancelled-preserves-result.png',fullPage:true});
 await page.locator(`[data-node-id="${root.id}"]`).click();
 await page.waitForFunction(()=>!!document.querySelector('.exploration-chart-tabs'));
 await page.waitForFunction(()=>document.querySelector('.exploration-chart-card iframe')?.dataset.pdfImage?.startsWith('data:image/png'));
 await page.locator('.exploration-chart-card iframe').evaluate(el=>el.dataset.identityCheck='retained');
 const before=await page.locator('.exploration-scroll-region').evaluate(el=>{el.scrollTop=100;return el.scrollTop;});
 const refreshed=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/v1/data-science/explorations/${baseline.id}`);
 await page.getByRole('button',{name:'刷新分析',exact:true}).click();await refreshed;
 await page.waitForFunction(()=>!Array.from(document.querySelectorAll('.exploration-toolbar-actions button')).find(b=>b.textContent.includes('刷新分析'))?.disabled);
 assert.equal(await page.locator('.exploration-chart-card iframe').getAttribute('data-identity-check'),'retained');
 assert.equal(await page.locator('.exploration-scroll-region').evaluate(el=>el.scrollTop),before);
 const current=await (await context.request.get(base+`/api/v1/data-science/explorations/${baseline.id}`,{headers})).json();
 initialTypes=root.chartTypes||{};
 const category=root.receipt.visualReport.charts.find(c=>c.id==='chart_allocation');assert.ok(category);chartId=category.id;
 await page.getByRole('tab',{name:category.title,exact:true}).click();
 await page.waitForFunction(()=>{const s=document.querySelector('select[aria-label="图表类型"]');return s&&!s.disabled&&[...s.options].some(o=>o.value==='donut'&&!o.disabled);});
 const saved=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname.endsWith('/nodes/'+root.id));
 await page.getByRole('combobox',{name:'图表类型'}).selectOption('donut');changed=true;
 assert.equal((await (await saved).json()).code,200);
 const frame=await (await page.locator('.exploration-chart-card iframe').elementHandle()).contentFrame();
 for(let turn=0;turn<100;turn++){if(await frame.evaluate(()=>document.querySelector('#plot')?.data?.[0]?.hole===.55))break;await new Promise(resolve=>setTimeout(resolve,100));}
 const data=await frame.evaluate(()=>{const p=document.querySelector('#plot').data[0];return {labels:p.labels,values:p.values,hole:p.hole};});
 assert.ok(data.values.length>0);assert.equal(data.hole,.55);
 await frame.evaluate(()=>{const plot=document.querySelector('#plot'),point=plot.data[0];plot.emit('plotly_click',{points:[{label:point.labels[0],value:point.values[0]}]});});
 await page.waitForFunction(()=>document.querySelector('.prompt-card textarea')?.value.includes('进一步分析'));
 assert.ok((await page.locator('.prompt-card textarea').inputValue()).includes(String(data.labels[0])));
 const height=await page.locator('.prompt-card textarea').evaluate(el=>{el.value='';el.dispatchEvent(new Event('input',{bubbles:true}));return getComputedStyle(el).minHeight;});assert.ok(parseFloat(height)<=40);
 await page.locator('.exploration-scroll-region').evaluate(el=>el.scrollTop=0);
 await page.screenshot({path:output+'/workspace-donut.png',fullPage:true});
 const collapse=page.locator('.chain-item').filter({has:page.locator(`[data-node-id="${root.id}"]`)}).locator('.chain-branch');await collapse.click();
 await page.reload({waitUntil:'domcontentloaded'});
 await page.locator(`[data-node-id="${root.id}"]`).waitFor();assert.equal(await page.locator('.chain-item[aria-selected=true] [data-node-id]').getAttribute('data-node-id'),root.id);
 assert.equal(await page.locator(`[data-node-id="${baseline.nodes[1].id}"]`).count(),0);
 assert.equal(await page.getByRole('combobox',{name:'图表类型'}).inputValue(),'donut');
 assert.deepEqual(executions,[]);assert.deepEqual(errors,[]);
 writeFileSync(output+'/browser.json',JSON.stringify({status:'PASSED',workspaceId:baseline.id,cancelledNodeRetainsHistoricalResult:true,refreshPreservesIframeAndScroll:true,chartTypePersisted:true,pointSelectionDraftOnly:true,selectionAndCollapsedBranchRestored:true,compactTextareaMinHeight:height,chartData:data,analysisExecutions:executions,pageErrors:errors},null,2));
 console.log('Historical navigation, cancelled-node fallback, stable chart, donut, selection drafting and refresh restoration PASSED');
 // Restore only the presentation value changed by this test, without overwriting concurrent edits.
 await restorePresentation();
}finally{try{await restorePresentation();}finally{await browser.close();}}
