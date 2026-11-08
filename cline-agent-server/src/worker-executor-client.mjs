import http from 'node:http';

// Capability client only. No Docker paths, specifications, container IDs or socket fallback.
export class WorkerExecutorClient {
 constructor(config){if(!config?.socketPath||!config?.token)throw Error('Restricted Executor configuration required');this.config=config;}
 request(method,path,grant,value){return new Promise((resolve,reject)=>{
  const req=http.request({socketPath:this.config.socketPath,path,method,headers:{authorization:'Bearer '+this.config.token,'x-cline-task':grant,'content-type':'application/json'}},res=>{
   const chunks=[];res.on('data',b=>chunks.push(b));res.on('end',()=>{try{const data=JSON.parse(Buffer.concat(chunks));if(res.statusCode>=400)reject(Object.assign(Error(data.message??'Executor rejected'),{status:res.statusCode}));else resolve(data);}catch(e){reject(e);}});res.on('error',reject);
  });req.setTimeout(30000,()=>req.destroy(Error('Executor response unknown; reconcile before retry')));req.on('error',reject);req.end(value===undefined?undefined:JSON.stringify(value));
 });}
 create(grant,value){return this.request('POST','/internal/workers',grant,value);}
 inspect(grant,id){return this.request('GET','/internal/workers/'+encodeURIComponent(id),grant);}
 start(grant,id){return this.request('POST','/internal/workers/'+encodeURIComponent(id)+'/start',grant,{});}
 stop(grant,id){return this.request('POST','/internal/workers/'+encodeURIComponent(id)+'/stop',grant,{});}
}
