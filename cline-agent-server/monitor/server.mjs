import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {Backend} from './backend.mjs';
import {parseCursor,cursorId,safe} from './projection.mjs';

const COOKIE='native_monitor_session';
const error=(message,status=400)=>Object.assign(Error(message),{status});
async function body(req){let n=0,text='';for await(const chunk of req){n+=chunk.length;if(n>65536)throw error('Request exceeds 64 KiB',413);text+=chunk;}try{return JSON.parse(text||'{}');}catch{throw error('Invalid JSON');}}
export function createMonitor(config){
 for(const value of [config.runtimeUrl,config.native?.url].filter(Boolean)){const u=new URL(value);if(u.protocol!=='http:'||!['127.0.0.1','localhost'].includes(u.hostname)||u.pathname!=='/')throw Error('Monitor requires configured private loopback backends');}
 if(!config.operator?.userId||!config.operator?.tenantId)throw Error('Explicit operator binding required');
 const backend=new Backend(config),sessions=new Map(),streams=new Set();
 const security={'cache-control':'no-store','x-content-type-options':'nosniff','referrer-policy':'no-referrer','content-security-policy':"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"};
 const json=(res,code,value,extra={})=>{res.writeHead(code,{...security,'content-type':'application/json; charset=utf-8',...extra});res.end(JSON.stringify(value));};
 const session=req=>{const token=String(req.headers.cookie??'').split(';').map(x=>x.trim()).find(x=>x.startsWith(COOKIE+'='))?.slice(COOKIE.length+1),s=sessions.get(token);if(!s||s.expiresAt<Date.now()){sessions.delete(token);throw error('Sign in to the authorized Runtime account',401);}return s;};
 const server=http.createServer(async(req,res)=>{
  try{
   const u=new URL(req.url,'http://localhost'),p=u.pathname,method=req.method;
   if(method!=='GET'&&req.headers.origin&&req.headers.origin!==`http://${req.headers.host}`&&req.headers.origin!==`https://${req.headers.host}`)throw error('Origin rejected',403);
   const assets=new Map([['/monitor/',['index.html','text/html']],['/monitor/app.js',['app.js','text/javascript']],['/monitor/style.css',['style.css','text/css']]]);
   if(method==='GET'&&assets.has(p)){const [name,type]=assets.get(p);res.writeHead(200,{...security,'content-type':type+'; charset=utf-8'});res.end(await readFile(new URL('./public/'+name,import.meta.url)));return;}
   if(method==='GET'&&p==='/'){res.writeHead(302,{location:'/monitor/'});res.end();return;}
   if(p==='/healthz')return json(res,200,{service:'native-cline-monitor',executionEngine:false});
   if(p==='/api/login'&&method==='POST'){
    const b=await body(req);if(typeof b.username!=='string'||typeof b.password!=='string')throw error('Username and password required');
    const login=await backend.api(config.runtimeUrl,'/api/v1/enterprise/auth/login','',{username:b.username,password:b.password});
    const user=login.user??login.userInfo;if(!login.token||user?.id!==config.operator.userId||user?.tenantId!==config.operator.tenantId)throw error('Account is not authorized for this diagnostic workspace',403);
    const key=randomBytes(32).toString('hex');if(sessions.size>=64)sessions.delete(sessions.keys().next().value);sessions.set(key,{token:login.token,userId:user.id,tenantId:user.tenantId,expiresAt:Date.now()+8*3600000});
    return json(res,200,{authenticated:true},{'set-cookie':`${COOKIE}=${key}; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800${config.secureCookie?'; Secure':''}`});
   }
   const user=session(req);
   if(p==='/api/logout'&&method==='POST'){sessions.forEach((s,k)=>{if(s===user)sessions.delete(k);});return json(res,200,{authenticated:false},{'set-cookie':`${COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`});}
   if(p==='/api/config'&&method==='GET')return json(res,200,{nativeEnabled:!!config.native,workspaces:config.native?.workspaces??[],models:config.models??[],nativeModel:config.native?.model,userId:user.userId,tenantId:user.tenantId});
   if(p==='/api/sessions'&&method==='GET')return json(res,200,{items:await backend.sessions(user)});
   if(p==='/api/sessions'&&method==='POST')return json(res,202,await backend.create(user,await body(req)));
   const dispatch=/^\/api\/dispatches\/([a-zA-Z0-9_.:-]+)$/.exec(p);
   if(dispatch&&method==='GET'){const row=backend.dispatches.get(dispatch[1]);if(!row||row.userId!==user.userId)throw error('Request observation unavailable; query existing Runtime history',404);const {userId,...view}=row;return json(res,200,view);}
   if(p==='/api/inputs'&&method==='GET'){
    const result=await backend.runtime(user,'/api/v1/data-science/user-assets?limit=100&personalOnly=true');return json(res,200,safe(result,[user.token]));
   }
   const download=/^\/api\/assets\/([a-zA-Z0-9_-]+)\/content$/.exec(p);
   if(download&&method==='GET'){
    // Runtime checks ownership and the asset allowlist. No path from browser reaches a filesystem API.
    const upstream=await fetch(config.runtimeUrl+'/api/v1/data-science/user-assets/'+download[1]+'/content',{headers:{authorization:'Bearer '+user.token},signal:AbortSignal.timeout(60000),redirect:'error'});
    if(!upstream.ok)throw error('Runtime denied artifact download',upstream.status);
    res.writeHead(200,{...security,'content-type':'application/octet-stream','content-disposition':'attachment; filename="'+download[1]+'"'});for await(const chunk of upstream.body){if(!res.write(chunk))await new Promise(resolve=>res.once('drain',resolve));}res.end();return;
   }
   const route=/^\/api\/sessions\/([^/]+)(?:\/([a-z-]+))?$/.exec(p);if(!route)throw error('Route not found',404);
   const key=decodeURIComponent(route[1]),action=route[2];
   if(!action&&method==='GET')return json(res,200,await backend.detail(user,key));
   if(action==='events'&&method==='GET'){
    let cursor=parseCursor(req.headers['last-event-id']??u.searchParams.get('after')??'');await backend.context(user,key);
    res.writeHead(200,{...security,'content-type':'text/event-stream','connection':'keep-alive','x-accel-buffering':'no'});res.write('retry: 2000\n\n');
    let busy=false,closed=false,ticks=0;const stream={res};streams.add(stream);
    const tick=async()=>{
     if(busy||closed)return;busy=true;
     try{
      if(user.expiresAt<Date.now())throw error('Authentication expired',401);
      const page=await backend.events(user,key,cursor);for(const e of page.events){if(!res.write(`event: observation\ndata: ${JSON.stringify(e)}\n\n`)){closed=true;res.end();break;}}
      cursor=page.cursor;if(!closed)res.write(`id: ${cursorId(cursor)}\nevent: cursor\ndata: ${JSON.stringify({cursor:cursorId(cursor)})}\n\n`);
      if(ticks++%3===0&&!closed)res.write(`event: snapshot\ndata: ${JSON.stringify(await backend.detail(user,key))}\n\n`);
     }catch(e){if(!closed)res.write(`event: diagnostic\ndata: ${JSON.stringify({message:e.message,status:e.status??503})}\n\n`);}
     finally{busy=false;}
    };
    const timer=setInterval(tick,1500);timer.unref();res.on('close',()=>{closed=true;clearInterval(timer);streams.delete(stream);});await tick();return;
   }
   if(method==='GET'&&action==='assets')return json(res,200,await backend.assets(user,key));
   if(method==='GET'&&action==='trace')return json(res,200,await backend.trace(user,key,u.searchParams.get('operationId')??'',u.searchParams.get('kind')??''));
   if(method==='GET'&&action==='runtime-events')return json(res,200,await backend.runtimeEvents(user,key,u.searchParams.get('after')??'0'));
   if(method==='GET'&&action==='code')return json(res,200,await backend.code(user,key));
   if(method==='POST'&&['messages','cancel','permission','reconcile'].includes(action)){
    const b=await body(req);if(action==='messages'&&(typeof b.text!=='string'||!b.text.trim()||b.text.length>16000||typeof b.commandId!=='string'||b.commandId.length>128))throw error('Message text and stable command ID required');
    if(action==='permission'&&!['approve','deny'].includes(b.decision))throw error('Invalid permission decision');
    return json(res,202,await backend.control(user,key,action,b));
   }
   throw error('Route not found',404);
  }catch(e){if(!res.headersSent)json(res,e.status??502,{message:safe(e.message,[config.native?.token]),code:'MONITOR_REQUEST_FAILED'});else res.end();}
 });
 server.on('close',()=>{for(const {res} of streams)res.end();sessions.clear();});return server;
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
 const config=JSON.parse(await readFile(process.env.CLINE_MONITOR_CONFIG,'utf8'));const server=createMonitor(config);server.listen(config.port??18346,config.host??'127.0.0.1',()=>console.log('Native Monitor listening'));
 for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>{server.close();server.closeAllConnections();});
}
