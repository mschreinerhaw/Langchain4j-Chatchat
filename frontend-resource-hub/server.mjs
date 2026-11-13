import express from 'express';
import {readFile} from 'node:fs/promises';
import {themes,templates,components,binder} from './catalog.mjs';
const app=express(), resources=JSON.parse(await readFile('public/resources.json'));
let requests=0;
app.use((req,res,next)=>{requests++;next()});
app.get('/metrics',(_,res)=>res.type('text/plain').send(`frontend_hub_requests_total ${requests}\nfrontend_hub_resources ${resources.length}\n`));
app.disable('x-powered-by');
app.use((req,res,next)=>{const start=Date.now();res.on('finish',()=>console.log(JSON.stringify({method:req.method,path:req.path,status:res.statusCode,ms:Date.now()-start})));next()});
app.get('/api/v1/health',(_,res)=>res.json({status:'ok',version:'1.0.0'}));
app.use('/assets',express.static('public/assets',{immutable:true,maxAge:'1y',dotfiles:'deny'}));
app.get('/assets/hub/bind.js',(_,res)=>res.type('js').send(binder));
app.get('/themes/:id.css',(req,res)=>{const t=themes.find(t=>t.id===req.params.id);t?res.type('css').send(t.css):res.sendStatus(404)});
app.get('/api/v1/resources',(req,res)=>{const base=process.env.PUBLIC_URL||`${req.protocol}://${req.get('host')}`;res.json(resources.map(r=>({...r,files:r.files.map(f=>({...f,url:base+f.path})),loadOrder:[...r.dependencies,r.id]})))});
for(const [name,items] of Object.entries({themes,templates,components})){
 app.get(`/api/v1/${name}`,(req,res)=>res.json(items.filter(x=>!req.query.q||JSON.stringify(x).toLowerCase().includes(String(req.query.q).toLowerCase()))));
 app.get(`/api/v1/${name}/:id`,(req,res)=>{const x=items.find(x=>x.id===req.params.id);x?res.json(x):res.sendStatus(404)});
}
// Worker is private; caller authentication is enforced at this boundary.
app.use('/api/v1/preview',express.json({limit:'2mb'}),(req,res,next)=>{if(!process.env.HUB_TOKEN||req.get('authorization')!==`Bearer ${process.env.HUB_TOKEN}`)return res.sendStatus(401);next()});
app.use('/api/v1/preview',async(req,res)=>{try{const response=await fetch((process.env.WORKER_URL||'http://frontend-preview-worker:8081')+'/api/v1/preview'+req.url,{method:req.method,headers:{'content-type':'application/json','authorization':`Bearer ${process.env.HUB_TOKEN}`},body:req.method==='POST'?JSON.stringify(req.body):undefined,signal:AbortSignal.timeout(65000)});res.status(response.status).type(response.headers.get('content-type')||'application/json').send(Buffer.from(await response.arrayBuffer()))}catch(e){res.status(502).json({status:'unavailable',error:e.message})}});
app.listen(8080,'0.0.0.0');
