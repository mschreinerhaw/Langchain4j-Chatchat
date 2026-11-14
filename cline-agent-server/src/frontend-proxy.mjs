// Task-bound transport over the existing loopback broker; no sandbox egress grant.
import {mkdir,writeFile,chown,lstat} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
const fail=(message,status=403)=>Object.assign(Error(message),{status});
export async function stageFrontendTransport(workspace,token,uid){
 const dir=join(workspace,'input');await mkdir(dir,{recursive:true});
 if((await lstat(dir)).isSymbolicLink())throw fail('Frontend transport symlink denied');
 const file=join(dir,'frontend-transport.json');
 await writeFile(file,JSON.stringify({url:'http://127.0.0.1:18329/frontend',token}),{flag:'wx',mode:0o400});
 await chown(file,uid,uid);
}
export async function proxyFrontend(req,res,config,task,{fetcher=fetch,save=()=>{}}={}){
 if(!req.url.startsWith('/frontend/'))return false;
 if(!config?.url||!config?.token)throw fail('Frontend transport unavailable',503);
 const path=req.url.slice('/frontend'.length);
 const catalog=/^\/api\/v1\/(resources|themes|templates|components)(?:\/[a-z0-9-]+)?(?:\?q=[^#]*)?$/.test(path);
 const resource=req.method==='GET'&&/^\/assets\/[A-Za-z0-9_./-]+$/.test(path)&&task.frontendResources?.[path];
 const create=path==='/api/v1/preview'&&req.method==='POST';
 const job=/^\/api\/v1\/preview\/([a-f0-9-]{36})(?:\/(result|bundle|desktop\.png|mobile\.png))?$/.exec(path);
 if(!(req.method==='GET'&&catalog)&&!create&&!(req.method==='GET'&&job)&&!resource)throw fail('Frontend route denied');
 if(job&&!task.frontendJobs?.includes(job[1]))throw fail('Preview belongs to another task',404);
 let body;
 if(create){let n=0;const chunks=[];for await(const chunk of req){n+=chunk.length;if(n>2*1024*1024)throw fail('Frontend body too large',413);chunks.push(chunk)}body=Buffer.concat(chunks)}
 const response=await fetcher(config.url.replace(/\/$/,'')+path,{method:req.method,headers:{authorization:'Bearer '+config.token,'content-type':'application/json'},body,redirect:'error',signal:AbortSignal.timeout(65000)});
 if(path==='/api/v1/resources'&&req.method==='GET'&&response.ok){
  const value=await response.json(),allowed={};
  for(const item of value)for(const file of item.files??[])if(/^\/assets\/[A-Za-z0-9_./-]+$/.test(file.path)&&!file.path.split('/').includes('..')&&/^[a-f0-9]{64}$/.test(file.sha256))allowed[file.path]=file.sha256;
  task.frontendResources=allowed;save(task);res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(value));return true;
 }
 if(resource){
  if(!response.ok)throw fail('Registered frontend asset unavailable',502);
  const chunks=[];let size=0;
  for await(const chunk of response.body??[]){size+=chunk.length;if(size>4*1024*1024)throw fail('Frontend asset exceeds limit',413);chunks.push(Buffer.from(chunk));}
  const bytes=Buffer.concat(chunks);if(createHash('sha256').update(bytes).digest('hex')!==resource)throw fail('Frontend asset digest differs from catalog',502);
  res.writeHead(200,{'content-type':response.headers.get('content-type')||'application/octet-stream','cache-control':'no-store'});res.end(bytes);return true;
 }
 if(create){const value=await response.json();if(response.ok){if(!/^[a-f0-9-]{36}$/.test(value.id))throw fail('Invalid frontend job',502);task.frontendJobs??=[];task.frontendJobs.push(value.id);save(task)}res.writeHead(response.status,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(value));return true}
 res.writeHead(response.status,{'content-type':response.headers.get('content-type')||'application/json','cache-control':'no-store'});
 for await(const chunk of response.body??[]){if(res.destroyed)break;res.write(chunk)}res.end();return true;
}
