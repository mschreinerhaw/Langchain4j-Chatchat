import express from 'express';
import {chromium} from 'playwright';
import {mkdir,writeFile,readFile,cp,rm} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';
import {dirname} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {themes,binder} from './catalog.mjs';
const app=express(),jobs=new Map();let active=0;
app.use(express.json({limit:'2mb'}));
app.get('/health',(_,res)=>res.json({status:'ok',active}));
app.use((req,res,next)=>{if(!process.env.HUB_TOKEN||req.get('authorization')!==`Bearer ${process.env.HUB_TOKEN}`)return res.sendStatus(401);next()});
const origin=process.env.HUB_ORIGIN||'http://frontend-resource-hub:8080';
const localPath=value=>{const u=new URL(value,origin);if(u.origin!==new URL(origin).origin||!/^\/(assets|themes)\/[a-zA-Z0-9_./@-]+$/.test(u.pathname)||u.pathname.includes('..')||u.search)throw Error('Unregistered dependency '+value);return u.pathname};
async function asset(path){
 if(path==='/assets/hub/bind.js')return Buffer.from(binder);
 if(path.startsWith('/themes/')){const t=themes.find(t=>path===`/themes/${t.id}.css`);if(!t)throw Error('Unknown theme');return Buffer.from(t.css)}
 return readFile('public'+path);
}
async function run(job,html){
 active++;let browser;const requestedAssets=new Set(),dir=`jobs/${job.id}`;
 const report={status:'failed',viewports:[],errors:[],htmlSha256:createHash('sha256').update(html).digest('hex'),checks:['console','resources','external-dependencies','horizontal-overflow','echarts','required-elements','declared-interactions'],businessValidation:'Not evaluated by browser'};
 try{
 await mkdir(dir,{recursive:true});
 await writeFile(`${dir}/index.html`,html);
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,chromiumSandbox:process.platform!=='win32',env:Object.fromEntries(['PATH','HOME','LANG'].filter(k=>process.env[k]).map(k=>[k,process.env[k]])),args:['--disable-dev-shm-usage']});
 const timer=setTimeout(()=>browser.close(),45000);
 try{for(const [name,width,height] of [['desktop',1440,900],['mobile',390,844]]){
 const context=await browser.newContext({viewport:{width,height},serviceWorkers:'block',acceptDownloads:false});
 const page=await context.newPage();page.setDefaultTimeout(10000);
 page.on('pageerror',e=>report.errors.push({viewport:name,type:'javascript',message:e.message}));
 page.on('console',m=>{if(m.type()==='error')report.errors.push({viewport:name,type:'console',message:m.text()})});
 await context.route('**/*',async route=>{const url=route.request().url();try{
 if(url===origin+'/index.html')return route.fulfill({status:200,contentType:'text/html',body:html});
 if(url.startsWith('data:'))return route.continue();
 const path=localPath(url),body=await asset(path);
 requestedAssets.add(path);
 const ext=path.split('.').at(-1),types={css:'text/css',js:'application/javascript',mjs:'application/javascript',svg:'image/svg+xml',woff2:'font/woff2'};
 await route.fulfill({status:200,contentType:types[ext]||'application/octet-stream',body});
 }catch(e){report.errors.push({viewport:name,type:'resource',url,message:e.message});await route.abort()}});
 await context.routeWebSocket('**/*',ws=>{report.errors.push({viewport:name,type:'network',message:'WebSocket denied'});ws.close()});
 await page.goto(origin+'/index.html',{waitUntil:'networkidle',timeout:15000});
 await page.evaluate(()=>document.fonts.ready);
 const issues=await page.evaluate(()=>{
 const out=[];if(document.documentElement.scrollWidth>innerWidth+1)out.push('horizontal-overflow');
 document.querySelectorAll('[data-chart],[_echarts_instance_]').forEach(el=>{const c=window.echarts?.getInstanceByDom(el);if(!c||el.clientHeight<30||el.clientWidth<30||!c.getOption()?.series?.length)out.push('chart-not-initialized-or-empty')});
 document.querySelectorAll('[data-preview-required]').forEach(el=>{const r=el.getBoundingClientRect();if(!r.width||!r.height)out.push('required-element-invisible');else{const hit=document.elementFromPoint(Math.min(innerWidth-1,r.x+r.width/2),Math.min(innerHeight-1,r.y+r.height/2));if(r.y>=0&&r.y<innerHeight&&hit&&!el.contains(hit))out.push('required-element-covered')}});return out});
 for(const message of issues)report.errors.push({viewport:name,type:'layout',message});
 for(const el of await page.locator('[data-preview-click]').all())await el.click();
 await page.screenshot({path:`${dir}/${name}.png`,fullPage:true,timeout:10000});
 report.viewports.push({name,width,height,screenshot:`/api/v1/preview/${job.id}/${name}.png`});await context.close();
 }}finally{clearTimeout(timer)}
 report.status=report.errors.length?'failed':'passed';
 }catch(e){report.errors.push({type:'worker',message:e.message})}finally{await browser?.close();active--}
 await writeFile(`${dir}/validation-report.json`,JSON.stringify(report,null,2));
 job.report=report;job.status=report.status;
 try{
 // Preserve original HTML and validation evidence; standalone is a separate file.
 // Include actually loaded dependencies, including CSS imports and dynamic JS;
 // navigation links are not files to copy into the delivery bundle.
 const paths=[...requestedAssets];
 const deps=[],seen=new Set();
 async function copyDependency(value){const path=localPath(value);if(seen.has(path))return;seen.add(path);deps.push(path);
 const bytes=await asset(path);await mkdir(dirname(dir+path),{recursive:true});await writeFile(dir+path,bytes);
 if(path.endsWith('.css'))for(const m of bytes.toString().matchAll(/url\(\s*["']?([^\s"')]+)["']?\s*\)/g))if(!m[1].startsWith('data:'))await copyDependency(new URL(m[1],origin+path).href);
 if(path.endsWith('.mjs'))for(const m of bytes.toString().matchAll(/(?:from\s*|import\s*\(\s*|import\s*)["'](\.[^"']+)["']/g))await copyDependency(new URL(m[1],origin+path).href);
 if(path.startsWith('/assets/')&&!path.startsWith('/assets/hub/')){const name=path.split('/')[2];await cp(`public/assets/${name}/LICENSE`,`${dir}/assets/${name}/LICENSE`)}
 }
 for(const value of paths)await copyDependency(value);
 await writeFile(`${dir}/standalone.html`,html.replace(/((?:src|href)=["'])\/((?:assets|themes)\/)/g,'$1$2').replaceAll(origin+'/assets/','assets/').replaceAll(origin+'/themes/','themes/'));
 await writeFile(`${dir}/dependencies.json`,JSON.stringify({paths:deps,resources:JSON.parse(await readFile('public/resources.json'))},null,2));
 await writeFile(`${dir}/manifest.json`,JSON.stringify({schema:'frontend-delivery.v1',jobId:job.id,validation:report.status,htmlSha256:report.htmlSha256,entry:'standalone.html',runtimeDelivery:'coding_node_delivery_selection.v1',businessValidation:report.businessValidation},null,2));
 await promisify(execFile)('tar',['-czf',`jobs/${job.id}.tgz`,'-C',dir,'.'],{timeout:30000});job.bundle=true;
 }catch(e){job.bundleError=e.message}
}
app.post('/api/v1/preview',(req,res)=>{
 if(typeof req.body?.html!=='string'||!req.body.html.trim())return res.status(400).json({error:'html required'});
 if(active>=2)return res.status(429).json({error:'Preview capacity reached'});
 const job={id:randomUUID(),status:'running',createdAt:Date.now()};jobs.set(job.id,job);
 void run(job,req.body.html).catch(e=>{job.status='failed';job.error=e.message});res.status(202).json(job);
});
app.get('/api/v1/preview/:id/:file',async(req,res)=>{
 const job=jobs.get(req.params.id);if(!job)return res.sendStatus(404);
 if(req.params.file==='result')return res.json(job);
 if(['desktop.png','mobile.png'].includes(req.params.file)&&job.report)return res.sendFile(`${process.cwd()}/jobs/${job.id}/${req.params.file}`);
 if(req.params.file==='bundle'&&job.bundle)return res.type('application/gzip').sendFile(`${process.cwd()}/jobs/${job.id}.tgz`);
 res.sendStatus(404);
});
app.get('/api/v1/preview/:id',(req,res)=>{const j=jobs.get(req.params.id);j?res.json(j):res.sendStatus(404)});
setInterval(()=>{for(const [id,j] of jobs)if(j.status!=='running'&&Date.now()-j.createdAt>3600000){jobs.delete(id);void rm(`jobs/${id}`,{recursive:true,force:true});void rm(`jobs/${id}.tgz`,{force:true})}},60000).unref();
await mkdir('jobs',{recursive:true});app.listen(8081,'0.0.0.0');
