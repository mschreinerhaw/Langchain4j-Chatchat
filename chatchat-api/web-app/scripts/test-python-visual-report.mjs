import {createServer} from 'vite';import {chromium} from 'playwright-core';
import {readFile,mkdir,writeFile,access} from 'node:fs/promises';import {resolve} from 'node:path';import assert from 'node:assert/strict';
const fixture=resolve(process.env.VISUAL_REPORT_FIXTURES||'../../target/assets-local/visual-report-fixtures');
const output=resolve('../../target/assets-local/visual-report-local-browser');await mkdir(output,{recursive:true});
const png=await readFile(resolve(fixture,'sample.png')),svg=await readFile(resolve(fixture,'sample.svg')),plotly=await readFile(resolve(fixture,'sample.plotly.json'));
const facts=JSON.parse(await readFile(resolve(fixture,'facts.json'),'utf8'));
const ref=(fileId,bytes,mediaType)=>({fileId,size:bytes.length,mediaType});
const report={schemaVersion:'python_visual_report.v1',reportMode:'visual',status:'SUCCESS',title:'Python 真实计算产物测试',summary:`测试样本合计 ${facts.total}，使用同批计算结果绘图。`,
 metrics:[{id:'total',label:'样本合计',value:facts.total,unit:'测试单位',calculation:'Python Decimal sum'}],
 tables:[{id:'table',title:'计算结果',columns:['分类','数值'],rows:facts.labels.map((label,i)=>[label,facts.values[i]])}],sections:[],
 charts:[{id:'png',title:'Python 静态柱状图',kind:'png',artifact:ref('chart-png',png,'image/png')},{id:'svg',title:'Python SVG 图表',kind:'svg',artifact:ref('chart-svg',svg,'image/svg+xml')},{id:'plotly',title:'Python Plotly 交互图',kind:'plotly',artifact:ref('chart-plotly',plotly,'application/json')}],
 dataSources:['fixture:python-sandbox'],validation:{checks:[{name:'computed-total',status:'PASSED',detail:'7.50'}],warnings:[]},markdown:'测试数据由 Python 计算；不是生产业务结论。'};
let executablePath;for(const candidate of [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','/usr/bin/chromium'].filter(Boolean)){try{await access(candidate);executablePath=candidate;break;}catch{}}
if(!executablePath)throw new Error('Chromium executable required');
const server=await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});let browser;const checks={},errors=[],external=[];
try{
 await server.listen();const origin=`http://127.0.0.1:${server.httpServer.address().port}`;
 browser=await chromium.launch({executablePath,headless:true,args:['--no-proxy-server']});const context=await browser.newContext();const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
 page.on('request',request=>{if(/^https?:/.test(request.url())&&!request.url().startsWith(origin))external.push(request.url());});
 await page.route('**/api/v1/interactions/user-assets/*/content',route=>{const id=route.request().url().split('/').at(-2);const bytes={'chart-png':png,'chart-svg':svg,'chart-plotly':plotly}[id];return route.fulfill({status:bytes?200:403,contentType:'application/octet-stream',body:bytes||Buffer.from('Denied')});});
 const manifest={schemaVersion:'enterprise_ui_artifact_v1',spec:{root:'report',elements:{report:{type:'Report',props:{},children:['visual']},visual:{type:'VisualReport',props:{resourceId:'visual-report'},children:[]}}},resources:{'visual-report':{}}};
 await page.route(/\/api\/v1\/ui-artifacts\/visual-test(?:\/.*)?$/,route=>route.fulfill({contentType:'application/json',body:JSON.stringify(route.request().url().includes('/resources/')?report:manifest)}));
 for(const [name,width] of [['desktop',1280],['mobile',390]]){
  await page.setViewportSize({width,height:1000});await page.goto(origin+'/tests/python-visual-report.html');
  const toggle=page.getByRole('switch',{name:'图文报告'});assert.equal(await toggle.getAttribute('aria-checked'),'false');await toggle.click();await page.getByRole('button',{name:'发送问题'}).click();assert.equal((await page.evaluate(()=>window.visualTestSends))[0].reportMode,'visual');assert.equal(await toggle.getAttribute('aria-checked'),'false');checks.requestToggle=true;
  await page.evaluate(value=>window.visualTestState.report=value,report);
  if(name==='desktop'){
   const downloaded=page.waitForEvent('download');
   await page.evaluate(async()=>{const {exportRenderedAnswerToPdf}=await import('/src/js/utils/answerPdfExport.js');await exportRenderedAnswerToPdf({sourceElement:document.querySelector('.python-visual-report'),message:{agentName:'报告验收'},fileName:'visual-report.pdf'});});
   const pdf=await downloaded;await pdf.saveAs(resolve(output,'visual-report.pdf'));const bytes=await readFile(resolve(output,'visual-report.pdf'));assert.ok(bytes.subarray(0,5).toString()==='%PDF-');assert.ok(bytes.length>30000);checks.pdfDownloadedWithLazyCharts=true;
   const pdfCheck=await page.evaluate(async raw=>{
    const pdfjs=await import('/node_modules/pdfjs-dist/build/pdf.mjs');pdfjs.GlobalWorkerOptions.workerSrc='/node_modules/pdfjs-dist/build/pdf.worker.mjs';
    const document=await pdfjs.getDocument({data:Uint8Array.from(raw)}).promise;
    const first=await document.getPage(1),canvas=window.document.createElement('canvas'),viewport=first.getViewport({scale:1});canvas.width=viewport.width;canvas.height=viewport.height;
    await first.render({canvasContext:canvas.getContext('2d'),viewport}).promise;
    return {pages:document.numPages,image:canvas.toDataURL('image/png')};
   },[...bytes]);
   assert.ok(pdfCheck.pages>=2);await writeFile(resolve(output,'pdf-first-page.png'),Buffer.from(pdfCheck.image.split(',')[1],'base64'));checks.pdfParsedAndRendered=true;
   assert.equal(await page.locator('.report-code,.report-provenance').count(),0);checks.internalDetailsHidden=true;
  }
  const images=page.locator('.python-report-chart img');await images.first().waitFor();for(let i=0;i<2;i++){await images.nth(i).scrollIntoViewIfNeeded();await page.waitForFunction(element=>element.complete&&element.naturalWidth>0,await images.nth(i).elementHandle());}checks.pythonPngAndSvgDisplayed=true;
  const iframe=page.locator('.python-report-chart iframe');await page.locator('[data-chart-id="plotly"]').scrollIntoViewIfNeeded();await iframe.waitFor();const frame=page.frameLocator('.python-report-chart iframe');await frame.locator('.main-svg').first().waitFor({timeout:30000});
  assert.equal(await iframe.getAttribute('sandbox'),'allow-scripts');assert.equal(await iframe.evaluate(element=>{try{return Boolean(element.contentWindow.document);}catch{return false;}}),false);checks.opaqueSandbox=true;
  const child=await (await iframe.elementHandle()).contentFrame();assert.ok(child);
  assert.deepEqual(await child.evaluate(()=>document.getElementById('plot').data[0].y),facts.values.map(Number));
  await child.evaluate(()=>Plotly.relayout('plot',{'yaxis.range':[0,5]}));assert.deepEqual(await child.evaluate(()=>document.getElementById('plot').layout.yaxis.range),[0,5]);checks.plotlyInteraction=true;
  assert.equal(await page.locator('.python-report-metrics dd').innerText(),`${facts.total} 测试单位`);assert.equal(await page.locator('tbody tr').count(),3);checks.valuesMatchPythonFacts=true;
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await page.screenshot({path:resolve(output,name+'.png'),fullPage:true});
  await page.evaluate(()=>window.visualTestState.archived=true);await page.locator('.enterprise-ui-artifact .python-visual-report').waitFor();checks.externalizedReportUsesSameRenderer=true;
 }
 assert.deepEqual(errors,[]);assert.deepEqual(external,[]);checks.noExternalNetworkOrPageErrors=true;
 console.log(JSON.stringify({checks,scope:'REAL_PYTHON_ARTIFACTS_WITH_API_FIXTURES; NOT_CLINE_E2E'}));
}finally{await writeFile(resolve(output,'acceptance.json'),JSON.stringify({checks,errors,external,scope:'REAL_PYTHON_ARTIFACTS_WITH_API_FIXTURES; NOT_CLINE_E2E'},null,2));await browser?.close();await server.close();}
