/** Export the verified published Python figure; this is an artifact, not an application screenshot. */
import {chromium} from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const [proofPath,outPath]=process.argv.slice(2);
assert.ok(outPath,'Usage: render-studio-python-result.mjs verified-proof.json output.png');
const proof=JSON.parse(fs.readFileSync(proofPath));
assert.equal(proof.status,'EXACT_MATCH');assert.ok(proof.executionId);assert.ok(proof.templateId);
const figure=proof.result.chart;assert.ok(figure?.data?.length);
const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try{
 const page=await browser.newPage({viewport:{width:1440,height:900},deviceScaleFactor:1});
 await page.setContent('<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0;padding:28px;background:#f6f8fc;color:#172c4b;font:16px Arial,sans-serif}main{padding:24px;background:white;border:1px solid #dce5f3;border-radius:14px}h1{font-size:22px;margin:0 0 12px}p{color:#526782;font-size:13px}#chart{width:100%;height:650px}</style></head><body><main><h1>TPC-DS：已发布 Python 模板实际分析结果</h1><p>商品主键 1 · 1999 年 · 原始金额单位未声明 · 三月无观测，保留断点</p><div id="chart"></div><p id="receipt"></p></main></body></html>');
 await page.addScriptTag({path:fileURLToPath(new URL('../node_modules/plotly.js-dist-min/plotly.min.js',import.meta.url))});
 await page.evaluate(async({figure,proof})=>{
  const layout=structuredClone(figure.layout);
  // Plotly 3 uses title objects; preserve the produced text and all original data.
  if(typeof layout.title==='string')layout.title={text:layout.title};
  for(const name of ['xaxis','yaxis']){
   layout[name]??={};if(typeof layout[name].title==='string')layout[name].title={text:layout[name].title};
  }
  layout.yaxis.tickformat=',.2f';
  await window.Plotly.newPlot('chart',figure.data,{...layout,margin:{l:115,r:35,t:80,b:60}},{staticPlot:true,responsive:false});
  document.querySelector('#receipt').textContent='Published template: '+proof.templateId+' | execution: '+proof.executionId+' | JDBC comparison: '+proof.status;
 },{figure,proof});
 await page.locator('#chart .main-svg').first().waitFor();fs.mkdirSync(path.dirname(path.resolve(outPath)),{recursive:true});
 await page.screenshot({path:outPath,fullPage:true});console.log('Exported actual verified Python figure.');
}finally{await browser.close();}
