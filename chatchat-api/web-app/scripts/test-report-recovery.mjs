// Actual archived PNG + real Java-admitted manifests + real Vue/Plotly. Only HTTP asset transport is a fixture.
import {createServer} from 'vite';
import {chromium} from 'playwright-core';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const repo=resolve('../..'),out=resolve('../../target/report-remediation-browser');await mkdir(out,{recursive:true});
const legacyDir=resolve('../../target/report-browser-legacy');await mkdir(legacyDir,{recursive:true});
const base='3eaa75b0f',source=path=>execFileSync('git',['show',`${base}:chatchat-api/web-app/src/${path}`],{cwd:repo,encoding:'utf8'});
await writeFile(resolve(legacyDir,'LegacyReport.vue'),source('components/ReportRenderer.vue').replace('../js/components/PythonVisualReport.js','./LegacyReport.js').replace('../styles/components/python-visual-report.css','./LegacyReport.css'));
await writeFile(resolve(legacyDir,'LegacyReport.css'),source('styles/components/python-visual-report.css'));
await writeFile(resolve(legacyDir,'LegacyReport.js'),source('js/components/PythonVisualReport.js')
  .replaceAll('"../utils/pythonVisualReport.js"','"/src/js/utils/pythonVisualReport.js"')
  .replaceAll('"../../components/PythonReportChart.vue"','"/src/components/PythonReportChart.vue"')
  .replaceAll('"../utils/reportNarrative.js"','"/src/js/utils/reportNarrative.js"')
  .replaceAll("'../utils/reportLayout.js'","'./LegacyLayout.js'"));
const schema='/@fs/'+resolve(repo,'chatchat-common/src/main/resources/runtime/report-layout.schema.json').replaceAll('\\','/');
await writeFile(resolve(legacyDir,'LegacyLayout.js'),source('js/utils/reportLayout.js').replace('"../../../../../chatchat-common/src/main/resources/runtime/report-layout.schema.json"',JSON.stringify(schema)));
const oldRecords=JSON.parse(await readFile(resolve(repo,'target/report-current-records.private.json'),'utf8'));
const original=oldRecords.filter(row=>row.entry_id==='coding:bc22250826c23320f9d95829365b399a328251d92379ba497a09833ccb398fe1:visual-report').map(row=>JSON.parse(row.record_json).payload)[0];
assert.ok(original,'Original screenshot report required');
const updated=JSON.parse(await readFile(resolve(repo,'target/report-scheduling-replayed.json'),'utf8'));
const chartCases=JSON.parse(await readFile(resolve(repo,'target/report-chart-cases-replayed.json'),'utf8'));
const png=await readFile(resolve(repo,'chatchat-api/src/test/resources/visual-report/scheduling-trend.png'));
assert.equal(createHash('sha256').update(png).digest('hex'),original.charts[0].artifact.sha256);
const assets=new Map([[original.charts[0].artifact.fileId,png],[updated.charts[0].artifact.fileId,png]]);
for(const chart of chartCases.report.charts)assets.set(chart.artifact.fileId,Buffer.from(chartCases.files[chart.artifact.fileName],'utf8'));
let executablePath;for(const candidate of ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','C:/Program Files/Google/Chrome/Application/chrome.exe','/usr/bin/chromium']){try{await access(candidate);executablePath=candidate;break;}catch{}}
assert.ok(executablePath,'Chromium required');
const server=await createServer({resolve:{alias:{vue:resolve('node_modules/vue/dist/vue.esm-bundler.js')}},server:{host:'127.0.0.1',port:0,fs:{allow:[repo]}},logLevel:'error'});
let browser;const proof={mode:'REAL_ARCHIVED_PNG_AND_JAVA_ADMISSION_WITH_HTTP_FIXTURES',mockedHttp:true,modelCalled:false,checks:{},errors:[],external:[]};
try{
 await server.listen();const origin=`http://127.0.0.1:${server.httpServer.address().port}`;
 browser=await chromium.launch({executablePath,headless:true,args:['--no-proxy-server']});
 const page=await browser.newPage({viewport:{width:1200,height:1000}});page.on('pageerror',error=>proof.errors.push(error.message));
 page.on('request',request=>{if(/^https?:/.test(request.url())&&!request.url().startsWith(origin))proof.external.push(request.url());});
 await page.route('**/api/v1/interactions/user-assets/*/content',route=>{const id=route.request().url().split('/').at(-2),bytes=assets.get(id);return route.fulfill({status:bytes?200:403,body:bytes||Buffer.from('Denied'),contentType:'application/octet-stream'});});
 await page.goto(origin+'/tests/report-recovery.html');await page.waitForFunction(()=>Boolean(window.setRecoveryReport));
 await page.evaluate(({report,url})=>window.setRecoveryReport(report,url),{report:original,url:'/@fs/'+resolve(legacyDir,'LegacyReport.vue').replaceAll('\\','/')});
 const report=page.locator('.report-renderer');await report.waitFor();
 assert.equal(await report.locator(':scope > p.report-notice').count(),2);
 const beforeChart=report.locator('.python-report-chart');await beforeChart.scrollIntoViewIfNeeded();
 await page.waitForFunction(()=>{const image=document.querySelector('.python-report-chart img');return image?.complete&&image.naturalWidth>0;});
 proof.beforeChartTop=await beforeChart.evaluate(el=>el.getBoundingClientRect().top+scrollY);
 proof.checks.originalChartBytesWereAlreadyValid=true;await page.screenshot({path:resolve(out,'report-before.png'),fullPage:true});
 await page.evaluate(report=>window.setRecoveryReport(report),updated);await report.locator('[data-report-ref="duration_trend_chart"]').waitFor();
 await page.waitForFunction(()=>{const image=document.querySelector('.python-report-chart img');return image?.complete&&image.naturalWidth>0;});
 proof.afterChartTop=await report.locator('.python-report-chart').evaluate(el=>el.getBoundingClientRect().top+scrollY);
 assert.ok(proof.afterChartTop<proof.beforeChartTop,'Recovered placement brings the real chart ahead of unplaced KPI cards');
 assert.equal(await report.locator(':scope > p.report-notice').count(),0);
 assert.equal(await report.locator('.python-report-metrics').count(),5);assert.equal(await report.locator('.python-report-table tbody tr').count(),7);
 assert.ok((await report.textContent()).includes('76.0'));assert.equal(updated.layoutValidationStatus,'RECOVERED');
 proof.checks.realPngRenderedAndExactDataPreserved=true;proof.checks.recoveredLayoutHasNoUnresolvedLayoutBanner=true;
 await page.screenshot({path:resolve(out,'report-after.png'),fullPage:true});
 await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
 await page.screenshot({path:resolve(out,'report-mobile.png'),fullPage:true});await page.setViewportSize({width:1200,height:1000});
 await page.evaluate(report=>window.setRecoveryReport(report),chartCases.report);
 for(const type of ['scatter','bar','pie']){
   const chart=page.locator(`[data-chart-id="${type}"]`);await chart.scrollIntoViewIfNeeded();
   const frame=await (await chart.locator('iframe').elementHandle()).contentFrame();await frame.waitForSelector('.main-svg',{timeout:30000});
   const values=await frame.evaluate(()=>document.getElementById('plot').data[0].y||document.getElementById('plot').data[0].values);
   assert.deepEqual(values,[1.25,2.5,3.75]);proof.checks[`${type}ActualPlotlyData`]=true;
 }
 const unsupported=page.locator('[data-chart-id="unsupported-test-type"]');await unsupported.scrollIntoViewIfNeeded();
 await unsupported.getByRole('alert').filter({hasText:'已保留同一数据的表格'}).waitFor({timeout:30000});
 assert.equal(await unsupported.locator('table tbody tr').count(),3);assert.equal(await unsupported.locator('iframe').count(),0);
 const empty=page.locator('[data-chart-id="empty"]');await empty.scrollIntoViewIfNeeded();await empty.getByText('此图表暂无可绘制数据。',{exact:true}).waitFor();
 assert.equal(await empty.locator('iframe').count(),0);assert.equal(await page.locator('.python-report-chart iframe').count(),3);
 assert.equal(await page.locator('.report-renderer > p.report-notice').count(),0);
 proof.checks.unsupportedChartUsesSameValidatedRows=true;proof.checks.singleFailureDoesNotDiscardValidCharts=true;proof.checks.emptyChartIsExplicit=true;
 proof.diagnostics=await page.evaluate(()=>window.reportDiagnostics);
 await page.screenshot({path:resolve(out,'chart-local-fallback.png'),fullPage:true});
 assert.deepEqual(proof.errors,[]);assert.deepEqual(proof.external,[]);proof.passed=true;console.log(JSON.stringify(proof));
}finally{await writeFile(resolve(out,'report-acceptance.json'),JSON.stringify(proof,null,2));await browser?.close();await server.close();}
