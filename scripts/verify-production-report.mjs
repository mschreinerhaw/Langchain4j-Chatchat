// Render the authenticated, hash-verified report bundle without external network access.
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createServer} from 'node:http';
import {resolve,posix} from 'node:path';
import {createHash} from 'node:crypto';
import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';

const root=resolve(process.env.REPORT_OUTPUT||'../target/production-server-20261118/real-model-retest');
const bundle=JSON.parse(await readFile(resolve(root,'client-assets-bundle.json'),'utf8'));
const files=new Map();
for(const file of bundle.files){
 const bytes=Buffer.from(file.content,'base64');
 assert.equal(createHash('sha256').update(bytes).digest('hex'),file.sha256);
 files.set('/'+file.path.replace(/^\/+/,''),{bytes,type:file.mimeType||file.contentType});
}
const html=[...files.keys()].find(p=>p.endsWith('/index.html'))||[...files.keys()].find(p=>p.endsWith('.html'));
assert.ok(html,'An archived HTML entry is required');
const server=createServer((req,res)=>{
 const path=posix.normalize(decodeURIComponent(new URL(req.url,'http://localhost').pathname));
 if(path==='/favicon.ico'){res.writeHead(204).end();return;}
 const file=files.get(path);if(!file){res.writeHead(404).end();return;}
 const type=file.type||(path.endsWith('.html')?'text/html; charset=utf-8':path.endsWith('.png')?'image/png':path.endsWith('.svg')?'image/svg+xml':'application/octet-stream');
 res.writeHead(200,{'Content-Type':type});res.end(file.bytes);
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
let browser;const report={passed:false,entry:html,files:files.size,externalRequests:[],pageErrors:[],views:[]};
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--no-proxy-server']});
 const origin='http://127.0.0.1:'+server.address().port;
 const context=await browser.newContext();
 await context.route('**/*',route=>{
  const url=route.request().url();if(url.startsWith(origin)||url.startsWith('data:'))return route.continue();
  report.externalRequests.push(url);return route.abort();
 });
 const page=await context.newPage();page.on('pageerror',e=>report.pageErrors.push(e.message));
 await mkdir(resolve(root,'render'),{recursive:true});
 for(const [name,width,height]of [['desktop',1440,1000],['mobile',390,844]]){
  await page.setViewportSize({width,height});await page.goto(origin+html,{waitUntil:'networkidle'});
  const metrics=await page.evaluate(()=>({title:document.title,body:document.body.innerText,
   images:[...document.images].map(i=>({loaded:i.complete&&i.naturalWidth>0})),
   overflow:document.documentElement.scrollWidth>innerWidth+2}));
  assert.match(metrics.body,/synthetic/i);assert.match(metrics.body,/100/);
  assert.ok(metrics.images.every(i=>i.loaded),'Archived chart image failed to load');
  assert.equal(metrics.overflow,false,'Report overflows the viewport');
  await page.screenshot({path:resolve(root,'render',name+'.png'),fullPage:true});
  report.views.push({name,title:metrics.title,images:metrics.images.length,overflow:metrics.overflow});
 }
 assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.externalRequests,[]);report.passed=true;
}finally{
 await browser?.close();await new Promise(r=>server.close(r));
 await writeFile(resolve(root,'render-verification.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}
