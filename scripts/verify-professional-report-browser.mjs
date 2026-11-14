// Run with Playwright installed (the existing preview worker provides it).
import {chromium} from 'playwright';
import {readFile,writeFile,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
const root=resolve(process.argv[2]);
const browser=await chromium.launch({headless:true,chromiumSandbox:process.platform!=='win32'});
const result={status:'PASSED',offline:true,checks:[],errors:[]};
try{
 for(const name of (await readdir(root,{withFileTypes:true})).filter(d=>d.isDirectory()).map(d=>d.name)){
  const folder=resolve(root,name),html=await readFile(folder+'/report.html');
  const model=JSON.parse(await readFile(folder+'/report_model.json','utf8'));
  const entry={name,htmlSha256:createHash('sha256').update(html).digest('hex'),viewports:[]};
  for(const [label,width,height] of [['desktop',1440,900],['mobile',390,844]]){
   const context=await browser.newContext({viewport:{width,height},serviceWorkers:'block'});
   await context.route(/^https?:/,route=>{result.errors.push('Unexpected network request');return route.abort();});
   const page=await context.newPage();page.on('pageerror',e=>result.errors.push(e.message));page.on('console',m=>{if(m.type()==='error')result.errors.push(m.text());});
   await page.goto(pathToFileURL(folder+'/report.html').href);await page.waitForFunction(()=>Array.isArray(window.__reportCharts));
   const actual=await page.evaluate(()=>({count:window.__reportCharts.length,values:window.__reportCharts.map(c=>c.getOption().series.map(s=>s.data)),dimensionsValid:window.__reportCharts.every(c=>c.getWidth()>0&&c.getHeight()>0),gapsPreserved:window.__reportCharts.every(c=>c.getOption().series.every(s=>s.type!=='line'||s.connectNulls===false)),overflow:document.documentElement.scrollWidth>innerWidth+1,summaryBottom:document.querySelector('.summary').getBoundingClientRect().bottom}));
   if(actual.count!==model.charts.length||!actual.dimensionsValid||!actual.gapsPreserved||actual.overflow||JSON.stringify(actual.values)!==JSON.stringify(model.charts.map(c=>c.series.map(s=>s.values))))result.errors.push(name+'/'+label+': chart data/count/layout mismatch');
   if(label==='desktop'&&actual.summaryBottom>900)result.errors.push(name+': summary outside first viewport');
   await page.screenshot({path:folder+'/'+label+'.png',fullPage:true});
   if(label==='desktop'){await page.emulateMedia({media:'print'});await page.pdf({path:folder+'/report.pdf',format:'A4',printBackground:true});const valid=await page.locator('.print-chart').evaluateAll(imgs=>imgs.every(i=>i.complete&&i.naturalWidth>0));if(!valid)result.errors.push(name+': missing print chart');}
   entry.viewports.push({label,...actual});await context.close();
  }
  result.checks.push(entry);
 }
}finally{await browser.close();}
result.status=result.errors.length?'FAILED':'PASSED';await writeFile(root+'/browser-validation.json',JSON.stringify(result,null,2));
if(!result.errors.length)for(const entry of result.checks){
 const folder=resolve(root,entry.name);
 await writeFile(folder+'/browser_validation.json',JSON.stringify({...entry,status:'PASSED',offline:true},null,2));
 const quality=JSON.parse(await readFile(folder+'/quality_report.json','utf8'));
 quality.pending=quality.pending.filter(p=>p!=='independent browser desktop/mobile/print validation required');
 quality.browserCheckedHtmlSha256=entry.htmlSha256;
 quality.status=quality.errors.length?'FAILED':quality.pending.length?'INCOMPLETE':'PASSED';
 await writeFile(folder+'/quality_report.json',JSON.stringify(quality,null,2));
 const manifest=JSON.parse(await readFile(folder+'/report_manifest.json','utf8'));manifest.qualityStatus=quality.status;
 await writeFile(folder+'/report_manifest.json',JSON.stringify(manifest,null,2));
}
console.log(JSON.stringify(result));if(result.errors.length)process.exitCode=1;
